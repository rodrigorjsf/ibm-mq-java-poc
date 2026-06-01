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

### F5.1 — CONFIRMED recipe (issue #40 implementation, validated by compilation + correctness-by-construction)

The open items from F5 are now resolved and the recipe is frozen here (zero-re-research):

- **No dedicated `PostgreSQLContainer` on the TC 2.0.5 line.** Testcontainers **2.x dropped the
  separate `org.testcontainers:postgresql` module** — `org.testcontainers:postgresql:2.0.5` does **not
  exist in Maven Central**, and the 2.0.5 **core** jar (`org.testcontainers:testcontainers`) does **not**
  bundle `org.testcontainers.containers.PostgreSQLContainer` (verified by inspecting the jar). Do **not**
  add a `postgresql` module dependency, and do **not** downgrade TC (the 2.0.5 pin is the fix for the
  Docker engine 29.x HTTP-400 incompatibility — see project `CLAUDE.md`). Instead use the **core
  `org.testcontainers.containers.GenericContainer`** (the same base class `MQContainer` extends) with the
  Postgres image and build the JDBC URL from `getHost()` + `getMappedPort(5432)`. Classes present in the
  2.0.5 core and used here: `org.testcontainers.containers.GenericContainer`,
  `org.testcontainers.containers.Network`, `org.testcontainers.containers.wait.strategy.Wait`.
- **Deterministic persistence IT** (`DeliveryReportPersistenceIT`, in default `verify`): one
  `postgres:16-alpine` `GenericContainer` (same image as the k3s store), env
  `POSTGRES_USER=corr` / `POSTGRES_PASSWORD=corrpass` / `POSTGRES_DB=correlation`, wait strategy
  `Wait.forLogMessage(".*database system is ready to accept connections.*", 2)` (the message is logged
  twice — once during internal bootstrap before the port is reachable — so require **2** occurrences).
  Both `datasources.default` and `datasources.reader` point at the **same** container (single instance ⇒
  reads are immediately consistent ⇒ no poll needed).
- **Physical-replication IT** (`DeliveryReportReplicationIT`, `@Tag("replication")`, EXCLUDED from
  `verify`, run via `mvn -pl ibmmq-jms-guide verify -Preplication`): two **`bitnamilegacy/postgresql:16`**
  `GenericContainer`s on a shared `Network.newNetwork()`.
  - **Image namespace (CONFIRMED 2026-06-01, do not re-discover):** use **`bitnamilegacy/postgresql:16`**,
    NOT `bitnami/postgresql:16*`. Bitnami moved its free Docker Hub images to the `bitnamilegacy/` namespace
    in 2025 (Bitnami Secure Images went paid); `docker manifest inspect bitnami/postgresql:16` → not found,
    `bitnamilegacy/postgresql:16` → available. The replication env contract
    (`POSTGRESQL_REPLICATION_MODE` master/slave, etc.) is identical in the legacy image.
  - **`-Preplication` profile gotcha (CONFIRMED):** the opt-in profile's failsafe config needs
    `<groups>replication</groups>` AND `<excludedGroups>none</excludedGroups>` — an EMPTY
    `<excludedGroups></excludedGroups>` does NOT override the base `<excludedGroups>replication</excludedGroups>`
    (Maven merges plugin config, it does not blank an inherited value), which would keep both filters and run
    ZERO tests. The sentinel `none` (matches no `@Tag`) overrides the inherited exclusion.
  - primary: `withNetworkAliases("postgres-primary")`, `POSTGRESQL_REPLICATION_MODE=master`,
    `POSTGRESQL_REPLICATION_USER=repluser` / `POSTGRESQL_REPLICATION_PASSWORD=replpass`,
    `POSTGRESQL_USERNAME=corr` / `POSTGRESQL_PASSWORD=corrpass` / `POSTGRESQL_DATABASE=correlation`.
    Wait: `Wait.forLogMessage(".*database system is ready to accept connections.*", 1)`.
  - replica: `POSTGRESQL_REPLICATION_MODE=slave`, same repl user/password,
    `POSTGRESQL_MASTER_HOST=postgres-primary` (resolves via the shared network alias — the classic
    two-container gotcha; **must** share a `Network` and set the alias), `POSTGRESQL_MASTER_PORT_NUMBER=5432`,
    `POSTGRESQL_PASSWORD=corrpass`.
  - **standby "caught-up" wait strategy:**
    `Wait.forLogMessage(".*database system is ready to accept read[- ]?only connections.*", 1)` (allow a
    longer startup timeout — 120s — because the standby must first base-backup from the primary). The
    application role `corr` is **not** recreated on the standby — physical replication copies the
    primary's role catalog. The `delivery_report` table + every appended row reach the standby by
    physical replication (no DDL on the replica); `DeliveryReportSchema` runs its `CREATE TABLE` only on
    the `default`/writer (primary).
  - **read-from-reader is asserted by poll-with-timeout** (deadline loop, 250 ms poll, 15 s budget) —
    never a single read immediately after the write.
  - This recipe matches `ibmmq-jms-guide/docker-compose.yml`'s `postgres-primary`/`postgres-replica`
    pair verbatim (same image, env, replication user) so the compose and the IT prove the same topology.

## F6 — Adding micronaut-data-jdbc wraps the injected `DataSource`; raw-JDBC beans MUST unwrap it

The moment `micronaut-data-jdbc` is on the classpath, Micronaut Data's `DelegatingDataSourceResolver`
wraps **every** injected `javax.sql.DataSource` in a contextual proxy
(`io.micronaut.data.connection.jdbc.advice.DelegatingDataSource` / `ContextualConnection`). A bean that
does **raw JDBC** — `dataSource.getConnection().prepareStatement(...)` — **outside** a
`@Connectable`/`@Transactional` scope then throws:

```
io.micronaut.data.connection.exceptions.NoConnectionException:
  No current connection present. Consider declaring @Connectable or @Transactional on the surrounding method
```

This is a **deploy-time / IT-time regression that unit tests do not catch**: it bit `DeliveryReportSchema`
(the new `@PostConstruct CREATE TABLE`) AND — critically — it would have silently broken the
live-validated `JdbcCorrelationStore` (its `ensureSchema`/`register`/`markFlag`/`removeIfFullyConfirmed`
are all raw JDBC, none transactional), surfacing only on the k3s deploy with `correlation.store=jdbc`.
Neither `CoaCodEndToEndIT` (in-memory store) nor any unit test exercised that path.

**Fix (minimal, surgical, version-stable):** unwrap the proxy to the raw target DataSource in the
constructor, then keep the existing raw-JDBC code unchanged:

```java
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource;
// ...
public JdbcCorrelationStore(DataSource dataSource) {
    this.dataSource = DelegatingDataSource.unwrapDataSource(dataSource); // no-op if already unwrapped
}
```

`DelegatingDataSource.unwrapDataSource(DataSource)` is a public static method (verified in
`micronaut-data-connection-jdbc:4.13.5`) that returns the underlying target (the real HikariCP
DataSource), or the argument unchanged if it is not wrapped — so it is correct with or without
micronaut-data-jdbc present. Applied to **both** raw-JDBC beans: `JdbcCorrelationStore` and
`DeliveryReportSchema`. The Micronaut Data **repositories** (`DeliveryReportWriteRepository`,
`DeliveryReportReadRepository`) are unaffected — they go through Data's own connection management and
must NOT unwrap.

**Regression lock:** `JdbcCorrelationStoreIT` runs the full register → markCoa → markCod →
`removeIfFullyConfirmed` cycle against a real Postgres container with `correlation.store=jdbc` and
micronaut-data-jdbc on the classpath — so a future removal of the unwrap fails `mvn verify`, not the next
k3s deploy.

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
