# 0010 — Audit-schema readiness ensured on first write, not by eager startup or a phantom injection

Status: accepted (2026-06-02) — refines the schema-init mechanism left open by issue #21; implementation
tracked in issue #52, done test-first. Discovered during the architecture-review deepening pass (sibling to
the #24 epic children; the trace-scope cleanup is already tracked as #42).

## Context

`DeliveryReportSchema.ensureSchema()` creates the append-only `delivery_report` audit table (plus its
additive issue-#19 / issue-#21 columns) via a `@PostConstruct` `CREATE TABLE IF NOT EXISTS` +
`ALTER … ADD COLUMN IF NOT EXISTS`, inside a bounded retry/backoff loop, on the `default`/writer
datasource. It must run in the report-consumer pod — where the `default` datasource is live — before the
first COA/COD audit insert, or every `insertIfAbsent` fails `relation "delivery_report" does not exist`.

Two hard constraints pull against each other:

1. **Must run before the first audit write, in the live-DB report-consumer pod.**
2. **Must NOT connect merely because a datasource is configured.**
   `CorrelationStoreNamedDatasourcesSmokeTest` runs an `ApplicationContext` with `datasources.default.url`
   pointed at a non-existent Postgres (no live DB), and asserts on **bean definitions**
   (`getBeanDefinitions` / `containsBean`), never `getBean`, precisely so the schema bean's connecting
   `@PostConstruct` is never triggered — it would hang the test in the 3 s × 10 backoff. This works only
   because Micronaut `@Singleton` is lazy: instantiated on demand, not at context start.

Mechanisms tried or considered:

- **Lazy `@Singleton` alone — rejected.** Nothing injects the schema bean (consumers inject the
  *repository*, not the schema), so it would never instantiate in production and the table would never be
  created (the ITs masked this via an explicit `context.getBean(...)`). The `lazy-singleton-never-runs`
  failure.
- **`@Context` — rejected.** Eager-instantiates at context start → connects in the smoke test (datasource
  configured, no live DB) → hangs. This is *why* the smoke test asserts definitions, not beans.
- **`ApplicationEventListener<StartupEvent>` gated on `datasources.default.url` — rejected.** Same failure:
  `ApplicationContext.run(...)` publishes `StartupEvent`, and the smoke test configures
  `datasources.default.url`, so the listener would activate, connect, and hang. Gating on the datasource
  does not distinguish "datasource configured" from "DB live."
- **Phantom injection (current, issue #21).** A `@SuppressWarnings("unused") @Nullable DeliveryReportSchema`
  field on `ReportConsumerHarnessRunner` (gated `@Requires(harness.role=report-consumer)`) pulls the lazy
  bean exactly in the live-DB pod and nowhere else. Correct, but it leaks a persistence-init concern into an
  unrelated harness runner as a load-bearing *unused* field, documented only by a paragraph of comment.

## Decision

**Ensure the audit schema on the first audit write — a property of the persistence write path, not of any
caller's startup.**

Ensure the schema **once, on the first audit write**, behind a one-time idempotent guard: the guard runs
`DeliveryReportSchema.ensureSchema()` before the first `insertIfAbsent`, and every later write skips straight
through. This is a *mechanism, not a new module* — **prefer folding the guard into the existing
`persistAudit` path** (`ReportMessageConsumer`'s audit-write call site) over introducing a
`DeliveryReportWriter` wrapper. A wrapper over the single `DeliveryReportWriteRepository` (one implementation)
would be a **one-adapter seam — mere indirection, since nothing varies across it** (seam discipline; and in
the spirit of ADR-0009: keep the lifecycle composed, do not add a module that earns no leverage).
`DeliveryReportWriteRepository` stays a pure Micronaut Data interface. Schema readiness therefore:

- **connects only when a real report is persisted** — which happens only in the live-DB report-consumer pod,
  never in the smoke/unit contexts (no report is processed there ⇒ no insert ⇒ no connect), satisfying
  constraint 2 without relying on lazy-singleton-plus-a-phantom-puller;
- **runs before the first write by construction** (the guard precedes the first delegate), satisfying
  constraint 1;
- **removes the `@SuppressWarnings("unused")` field** from `ReportConsumerHarnessRunner` — the harness runner
  depends only on what it uses, and the leak across the harness seam closes.

`DeliveryReportSchema` stays the single owner of the DDL (the UNIQUE `(correlation_id, feedback)` constraint,
the additive ALTERs) and its retry/backoff; only its *trigger* moves from `@PostConstruct` to the first-write
guard.

## Consequences

- **The guard is concurrency-safe.** The harness model is single-thread-per-pod (`AbstractHarnessRunner`), so
  contention is nil today; the guard is still an `AtomicBoolean` / double-checked latch so the async
  `MessageListener` scale-up variant (ADR-0006) stays correct.
- **The first report pays a one-time ensure.** `CREATE TABLE IF NOT EXISTS` + idempotent ALTERs are ~ms once
  Postgres is ready; the existing backoff still covers a not-yet-ready Postgres on cold start
  (belt-and-suspenders with the `wait-deps` initContainer). At ~10k rpm this is a single first-message cost,
  not a per-message cost.
- **Best-effort posture preserved (ADR-0005).** The audit insert is already best-effort (caught, logged WARN,
  never aborts the acked reconciliation path). An ensure failure folds into the same best-effort path — it
  does not break reconciliation.
- **Coordinates with ADR-0008 / issue #25.** #25 rewires the *report receive / JMS* seam; this decision sits
  on the *audit write* path (the `persistAudit` call site), which #25 does not touch. Because the preferred
  form folds the guard into `persistAudit` rather than adding a wrapper, implementation should land after or
  alongside #25 to avoid editing that path twice; the change itself is orthogonal (write-path only).
- **Stale comment corrected.** `DeliveryReportSchema`'s class comment cites `ReportMessageConsumer` as the
  injection site; the phantom field actually lives on `ReportConsumerHarnessRunner`. That drift is fixed as
  part of this work (documentation-currency rule).
- **Reversibility:** trivial — the DDL and retry stay in `DeliveryReportSchema`; only the invocation point
  moves. Reverting to the phantom injection costs only re-adding the field.
- New Java in-code text is English (ADR-0004); JUnit `@DisplayName` stays pt-BR.
