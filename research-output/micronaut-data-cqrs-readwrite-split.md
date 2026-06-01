# Fact sheet — Micronaut Data JDBC + read/write datasource split (Aurora-like writer/reader)

Validated facts for persisting COA/COD delivery reports with a **writer/reader connection split**
backed by a streaming-replicated Postgres primary + hot-standby. Audience: anyone implementing or
adjusting the delivery-report persistence (issues #40, #19, #20). Goal per the repo's zero-re-research
rule: never re-derive these facts.

> Terminology note: this is a **read/write connection split** (Aurora writer endpoint = primary,
> reader endpoint = replica), **not** CQRS command/query model separation. See ADR-0005.

## F1 — The `default` datasource is load-bearing; name the WRITER `default`

When **no** datasource is named `default`, Micronaut makes **none** of them `@Primary`, so:
- every unqualified `javax.sql.DataSource` injection becomes unsatisfied/ambiguous, and
- any bean with around-advice (`@Transactional`) fails with *"Multiple possible bean candidates"*
  because there are multiple `DataSourceTransactionManager` beans and no primary.

**Decision that follows:** name the **writer/primary** datasource `default` and add a second named
`reader` datasource. Consequences:
- The existing `JdbcCorrelationStore` injects a bare `DataSource` (constructor param) → it resolves to
  `default` = writer with **no code change**. Exactly-once reconciliation stays on the writer
  (correct: read-after-write critical, must never hit a lagging replica).
- The k3s harness env `DATASOURCES_DEFAULT_*` (`deploy/k3s/31-app-config.yaml`, `10-secrets.yaml`)
  stays valid unchanged. Add `DATASOURCES_READER_*` for the replica.
- Unqualified `@Transactional` resolves to the `default` (writer) transaction manager.

## F2 — A repository selects its datasource via `@Repository("name")`

```java
// Writer repo — uses the default (writer) datasource + its transaction manager.
@JdbcRepository(dialect = Dialect.POSTGRES)
public interface DeliveryReportWriteRepository extends CrudRepository<DeliveryReportRecord, Long> { }

// Reader repo — bound to the "reader" (replica) datasource.
@Repository("reader")
@JdbcRepository(dialect = Dialect.POSTGRES)
public interface DeliveryReportReadRepository extends GenericRepository<DeliveryReportRecord, Long> {
    List<DeliveryReportRecord> findByCorrelationId(String correlationId);
}
```

The `@Repository` annotation's optional string value names the connection/datasource; default is
`default`. `@JdbcRepository(dialect = …)` selects the SQL dialect. (There is **no** `dataSource`
attribute on `@JdbcRepository` in this line — the binding is the `@Repository` value.)

## F3 — Transaction-manager selection with multiple datasources

Each datasource yields its own `DataSourceTransactionManager`. Resolution:
- Repos annotated `@Repository("reader")` use the `reader` transaction manager for their methods.
- Unqualified `@Transactional` → the `default` (primary) manager (safe because writer = `default`).
- To force a manager explicitly, qualify by name: `@Transactional("reader")` /
  `@Transactional(transactionManager = "reader")` (resolved via `Qualifiers.byName`).
- Mark replica reads read-only (`@Transactional(value = "reader", readOnly = true)` or Micronaut's
  `@io.micronaut.transaction.annotation.ReadOnly` on the read path).

**Invariant (writer-only gating):** any read whose result gates a write/dedup decision MUST use the
writer. The `reader` is for query/projection only — replica lag makes a just-written row invisible
for tens of ms, which at ~334 report-inserts/s is a wide race window. Enforce dedup with a UNIQUE
constraint on the **writer** table (so duplicates fail-closed), never with a read against `reader`.

## F4 — Maven coordinates (versions aligned by micronaut-platform BOM 4.9.4)

```xml
<!-- runtime -->
<dependency>
  <groupId>io.micronaut.data</groupId>
  <artifactId>micronaut-data-jdbc</artifactId>
</dependency>
<!-- micronaut-jdbc-hikari + org.postgresql:postgresql already present -->

<!-- annotation processor: ADD to the micronaut-maven-plugin annotationProcessorPaths,
     alongside the existing micronaut-inject-java path -->
<path>
  <groupId>io.micronaut.data</groupId>
  <artifactId>micronaut-data-processor</artifactId>
</path>
```

The processor path is required for repository metadata generation; it coexists with the existing
`micronaut-inject-java` processor (both must be listed — adding one does not drop the other).

## F5 — Streaming replication in Testcontainers (candidate recipe — confirm during implementation)

Physical primary→standby streaming replication for the IT can be stood up with two
`bitnami/postgresql` containers on a shared network, env-driven:
- primary: `POSTGRESQL_REPLICATION_MODE=master`, `POSTGRESQL_REPLICATION_USER`,
  `POSTGRESQL_REPLICATION_PASSWORD`, `POSTGRESQL_USERNAME/PASSWORD/DATABASE`.
- standby: `POSTGRESQL_REPLICATION_MODE=slave`, `POSTGRESQL_MASTER_HOST`, `POSTGRESQL_MASTER_PORT_NUMBER`,
  same replication user/password.

Testing rules (avoid flake):
- **Never** assert read-from-reader immediately after write-to-writer. Use poll-with-timeout
  (Awaitility-style) bounded a few seconds, tolerating replica lag.
- Keep **persistence-logic** tests (does the consumer write the row? is dedup enforced?) on a
  **single deterministic container** so they can run in the default `verify` gate.
- Reserve the **two-container physical-replication** test (lag behavior, read-from-reader) for a
  `@Tag`-gated suite EXCLUDED from default `verify` (mirror the `-Pload` pattern in
  `ibmmq-jms-guide/src/test/CLAUDE.md`); today the failsafe config runs ALL `*IT.java`, so an
  ungated replication IT would land in CI.

Open to confirm at implementation time: exact bitnami image tag compatible with TC 2.0.5, and the
standby "caught-up" wait strategy. Record the final recipe back here.

## Sources

- Micronaut Data — Repositories guide (datasource name on `@Repository`, `micronaut-data-processor`):
  https://micronaut-projects.github.io/micronaut-data/latest/guide/
- Micronaut Data JDBC access guide (deps, `datasources.default.*` YAML):
  https://guides.micronaut.io/latest/micronaut-data-jdbc-repository-maven-java.html
- Micronaut SQL — Configuring JDBC (the `default` datasource is injected unless qualified; if none is
  named `default`, all injections must be qualified):
  https://micronaut-projects.github.io/micronaut-sql/latest/guide/
- micronaut-data #657 / #822 / #1285 — multiple-datasource transaction-manager ambiguity when no
  `default` exists; `@Transactional` candidate resolution by named qualifier:
  https://github.com/micronaut-projects/micronaut-data/issues/657
