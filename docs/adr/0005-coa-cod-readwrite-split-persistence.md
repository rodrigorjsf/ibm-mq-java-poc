# 0005 — COA/COD delivery reports persisted via Micronaut Data JDBC with an Aurora-like read/write split

Status: accepted

The consumption flow persists every COA/COD delivery report into Postgres as a durable, queryable
**append-only audit record**, using **Micronaut Data JDBC**, against a **streaming-replicated
primary + hot-standby** pair: writes go to the **writer** (primary) connection, reads (queries,
projections, reporting) go to the **reader** (replica) connection — an Aurora-style writer/reader
endpoint split. Local environment is Docker (compose for dev, two-container Testcontainers for the IT);
the k3s harness gets a primary+replica StatefulSet (the live-cluster validation of that is HITL,
issue #21).

This is a **read/write connection split**, **NOT** CQRS command/query model separation. Out of scope
(explicitly, to cut YAGNI): no separate read-model projection table, no event bus / outbox to
populate a read side, no eventual-consistency materialized views. One `delivery_report` table, written
via the writer, read via the reader.

## Why a new audit table (not the existing correlation store)

The existing `JdbcCorrelationStore` (`pending_message`) is a **transient reconciliation ledger**:
rows are deleted the moment COA+COD are both confirmed (`removeIfFullyConfirmed`), so it cannot answer
"what reports did we receive, and when, with what feedback/MQMD fields?" after reconciliation. The new
`delivery_report` table is **append-only** — one row per received report, never deleted — and is the
read-model the `reader` connection serves at scale (~10k rpm ⇒ ~334 report rows/s). That durable,
queryable history is exactly what persistence buys over the correlation ledger.

## Decisions (each resolves an adversarial-review finding — see research-output/micronaut-data-cqrs-readwrite-split.md)

- **Name the writer datasource `default`, add a `reader` datasource.** If no datasource is named
  `default`, Micronaut leaves none `@Primary` and every unqualified `DataSource` injection +
  `@Transactional` bean breaks ("multiple bean candidates"). Naming the writer `default` keeps
  `JdbcCorrelationStore`'s bare-`DataSource` injection and the harness `DATASOURCES_DEFAULT_*` env
  working **unchanged**, and keeps exactly-once reconciliation on the writer.
- **Exactly-once stays on the writer; the reader is query-only.** `JdbcCorrelationStore` is left as
  raw JDBC on the `default`/writer datasource (its UPSERT/conditional-DELETE exactly-once logic was
  validated live on k3d — do not rewrite). The reader datasource is never used for a read that gates a
  write.
- **Dedup fails closed via a UNIQUE constraint on the writer table** (e.g. `(correlation_id,
  feedback)`), never via a read against the lagging replica.
- **Persistence is best-effort, consistent with the current report path.** `ReportMessageConsumer`
  uses `AUTO_ACKNOWLEDGE`; the report is acked before processing, so the existing flag-marking is
  already "process-after-ack". The audit insert inherits the same at-most-once-after-ack property; the
  UNIQUE constraint makes redelivery (reports are at-least-once from the QM) idempotent. A transacted /
  `CLIENT_ACKNOWLEDGE` report path that acks only after the insert commits is recorded as a documented
  follow-up, deliberately deferred to avoid destabilizing the validated path.
- **Replication IT is gated; persistence-logic IT is deterministic.** The two-container physical
  replication test (read-from-reader, lag behavior) is `@Tag`-gated and excluded from the default
  `verify` gate (mirroring `-Pload`), with read-after-write asserted via poll-with-timeout. The
  "does the consumer write the row / is dedup enforced" tests run on a single deterministic container
  inside `verify`.
- **HikariCP pools are sized deliberately.** Two datasources × N consumer replicas multiply
  connections against the standby; the `default` and `reader` `maximum-pool-size` are set with the
  per-pod × replica math documented against the standby's `max_connections`.

## Consequences

- Adds `io.micronaut.data:micronaut-data-jdbc` + the `micronaut-data-processor` annotation-processor
  path (alongside `micronaut-inject-java`) — the first Micronaut Data surface in the project, which the
  original `JdbcCorrelationStore` deliberately avoided. That avoidance still holds for the
  reconciliation ledger; only the new audit read-model uses Micronaut Data.
- The `delivery_report` schema is introduced minimally (issue #40) with the fields available at report
  time (type, feedback, correlationId, originalMessageId, observedAt) and **additively extended**
  (ALTER) by issue #19 with the six recovered MQMD fields. #40 is therefore real audit content, not a
  stub #19 rewrites.
- New Java in-code text in this work (comments, JavaDoc, log messages) is **English** per ADR-0004;
  JUnit `@DisplayName` stays **pt-BR**.
- A real-Aurora deployment, if ever wanted, maps the `default`/`reader` datasources to the Aurora
  writer/reader endpoints with no application change — the split is already connection-level.
