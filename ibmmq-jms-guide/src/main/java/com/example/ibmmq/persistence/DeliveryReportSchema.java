package com.example.ibmmq.persistence;

import io.micronaut.context.annotation.Requires;
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Owns the DDL of the append-only {@code delivery_report} audit table (plus its additive issue-#19 /
 * issue-#21 columns) on the {@code default} (writer/primary) datasource, and creates it via the public
 * {@link #ensureSchema()} entry point.
 *
 * <p><b>Schema-on-first-write (ADR-0010):</b> {@code ensureSchema()} is NOT triggered eagerly (no
 * {@code @PostConstruct}, no {@code @Context}, no {@code StartupEvent}). It is invoked <b>lazily, on the
 * first audit write</b>, behind a one-time idempotent guard inside {@code ReportMessageConsumer}'s
 * audit-write path ({@code persistAudit}). The first persisted report ensures the schema once and every
 * later write skips straight through. This connects the writer datasource only when a real report is
 * actually persisted — which happens only in the live-DB report-consumer pod, never in the smoke/unit
 * contexts (no report processed there ⇒ no insert ⇒ no connect) — so a configured-but-not-live datasource
 * (e.g. {@code CorrelationStoreNamedDatasourcesSmokeTest}) never trips the connecting backoff. The retry
 * loop is the same {@code CREATE TABLE IF NOT EXISTS} with a bounded backoff that
 * {@code JdbcCorrelationStore.ensureSchema()} uses. {@code IF NOT EXISTS} is idempotent and safe under N
 * concurrent report-consumer replicas (the first pod creates it, the rest are no-ops); the backoff is
 * defence-in-depth for a Postgres that is not yet ready when a pod boots (belt-and-suspenders with the
 * {@code wait-deps} initContainer).</p>
 *
 * <p><b>Why a separate schema bean (not Micronaut Data schema generation):</b> the writer repository is
 * a {@code GenericRepository} with a hand-written idempotent {@code INSERT}, so Micronaut Data's
 * {@code schema-generate} is not driving DDL here — and even if it were, it would not emit the UNIQUE
 * constraint we depend on for dedup. The DDL is owned here so the UNIQUE {@code (correlation_id,
 * feedback)} constraint (AC4) is explicit, auditable, and created on the WRITER table.</p>
 *
 * <p><b>Schema:</b></p>
 * <pre>
 *   CREATE TABLE delivery_report (
 *       id                  BIGSERIAL PRIMARY KEY,
 *       correlation_id      VARCHAR(256) NOT NULL,
 *       original_message_id VARCHAR(256),
 *       report_type         VARCHAR(32)  NOT NULL,
 *       feedback            INTEGER      NOT NULL,
 *       observed_at         TIMESTAMP    NOT NULL,
 *       CONSTRAINT uq_delivery_report_correl_feedback UNIQUE (correlation_id, feedback)
 *   );
 *   -- Issue #19 additive, idempotent (ADD COLUMN IF NOT EXISTS), all NULLABLE:
 *   ALTER TABLE delivery_report
 *       ADD COLUMN IF NOT EXISTS appl_identity_data        VARCHAR(64),
 *       ADD COLUMN IF NOT EXISTS accounting_token_hex      VARCHAR(64),
 *       ADD COLUMN IF NOT EXISTS correlation_id_bytes_hex  VARCHAR(96),
 *       ADD COLUMN IF NOT EXISTS message_id_bytes_hex      VARCHAR(96),
 *       ADD COLUMN IF NOT EXISTS put_timestamp_utc         TIMESTAMP,
 *       ADD COLUMN IF NOT EXISTS report_type_char          CHAR(1);
 * </pre>
 *
 * <p>The streaming-replicated standby receives this table (and every appended row) by physical
 * replication — no DDL runs against the replica.</p>
 *
 * <p><b>Bean gating:</b> active only when a {@code default} datasource is configured
 * ({@code datasources.default.url} present — same gate as {@link DeliveryReportWriteRepository}). The bean
 * DEFINITION resolves whenever the datasource is configured (the smoke test asserts exactly that), but the
 * lazy {@code @Singleton} only INSTANTIATES on demand and only CONNECTS inside {@link #ensureSchema()} — so
 * a configured-but-not-live datasource never opens a connection until the first audit write actually calls
 * the ensure.</p>
 */
// @Singleton (lazy) gated on datasources.default.url. ADR-0010 schema-on-first-write: the schema is ensured
// lazily on the FIRST audit write, behind a one-time idempotent guard in ReportMessageConsumer.persistAudit,
// which injects this bean @Nullable and calls ensureSchema() before the first insertIfAbsent. NO eager
// trigger: @PostConstruct / @Context / StartupEvent were all rejected because they connect as soon as a
// datasource is configured, hanging the 3 s x 10 backoff in CorrelationStoreNamedDatasourcesSmokeTest (a
// datasource configured against a non-live DB). Folding the ensure into the write path connects only when a
// real report is persisted (the live-DB report-consumer pod), satisfying both "run before the first write"
// and "do not connect merely because a datasource is configured".
@Singleton
@Requires(property = "datasources.default.url")
public class DeliveryReportSchema {

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryReportSchema.class);

    // BIGSERIAL matches @Id @GeneratedValue Long; the UNIQUE constraint is what makes report
    // redelivery / concurrent double-processing idempotent (dedup fails closed on the WRITER table).
    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS delivery_report (
                id                  BIGSERIAL    PRIMARY KEY,
                correlation_id      VARCHAR(256) NOT NULL,
                original_message_id VARCHAR(256),
                report_type         VARCHAR(32)  NOT NULL,
                feedback            INTEGER      NOT NULL,
                observed_at         TIMESTAMP    NOT NULL,
                CONSTRAINT uq_delivery_report_correl_feedback UNIQUE (correlation_id, feedback)
            )""";

    // Issue #19: ADDITIVELY adds the six recovered-MQMD audit columns. ADD COLUMN IF NOT EXISTS is
    // idempotent and safe under N concurrent replicas (first pod adds, the rest are no-ops); all columns
    // are NULLABLE so existing rows and the (correlation_id, feedback) dedup are unaffected. A single
    // ALTER statement adds all six (Postgres supports multiple ADD COLUMN clauses in one ALTER). Run
    // AFTER the CREATE so the table always exists first.
    private static final String ALTER_ADD_MQMD_COLUMNS = """
            ALTER TABLE delivery_report
                ADD COLUMN IF NOT EXISTS appl_identity_data       VARCHAR(64),
                ADD COLUMN IF NOT EXISTS accounting_token_hex     VARCHAR(64),
                ADD COLUMN IF NOT EXISTS correlation_id_bytes_hex VARCHAR(96),
                ADD COLUMN IF NOT EXISTS message_id_bytes_hex     VARCHAR(96),
                ADD COLUMN IF NOT EXISTS put_timestamp_utc        TIMESTAMP,
                ADD COLUMN IF NOT EXISTS report_type_char         CHAR(1)""";

    // Issue #21: ADDITIVELY add the per-message send instant so produce->COA / produce->COD latency can be
    // computed at SQL level over this APPEND-ONLY table — the pending_message ledger is deleted on
    // reconciliation, so it cannot retain latency (see ADR-0007). NULLABLE: the rare COA-before-register
    // case leaves it null and is excluded from the percentiles (a low-tail sample, so p95/p99 stay robust).
    // Idempotent (ADD COLUMN IF NOT EXISTS), safe under N concurrent replicas (first pod adds, rest no-op).
    // WRITE-ONLY audit column: populated by the writer @Query (insertIfAbsent), queried by `make load-verify`
    // via psql — intentionally NOT mapped on DeliveryReportRecord (no read path needs it).
    private static final String ALTER_ADD_SENT_AT = """
            ALTER TABLE delivery_report
                ADD COLUMN IF NOT EXISTS sent_at TIMESTAMP""";

    private final DataSource dataSource;

    public DeliveryReportSchema(DataSource dataSource) {
        // Unwrap Micronaut Data's contextual DataSource proxy so ensureSchema() can run a raw CREATE TABLE
        // via plain JDBC: with micronaut-data-jdbc on the classpath the injected DataSource is wrapped
        // (DelegatingDataSourceResolver) and a getConnection() outside a @Connectable/@Transactional scope
        // throws NoConnectionException. unwrapDataSource is a no-op when already unwrapped. Mirrors the same
        // fix in JdbcCorrelationStore. See research-output/micronaut-data-cqrs-readwrite-split.md (F6).
        this.dataSource = DelegatingDataSource.unwrapDataSource(dataSource);
    }

    /**
     * Creates the table if absent and then ADDITIVELY ensures the issue-#19 MQMD columns. Per ADR-0010 this
     * is the public schema-on-first-write entry point: invoked lazily by {@code ReportMessageConsumer} on
     * the FIRST audit write (behind a one-time idempotent guard), never from an eager startup hook. Both
     * {@code CREATE TABLE IF NOT EXISTS} and {@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS} are idempotent
     * and safe under N concurrent replicas (first pod creates/adds, rest are no-ops). The ALTER runs AFTER
     * the CREATE in the same connection so the table always exists first. Retries with backoff while
     * Postgres is still coming up; on exhaustion it throws {@link IllegalStateException}, which the
     * first-write guard catches as a best-effort failure (WARN, retried on the next write).
     */
    public void ensureSchema() {
        final int maxAttempts = 10;
        SQLException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement createPs = conn.prepareStatement(DDL);
                 PreparedStatement alterPs = conn.prepareStatement(ALTER_ADD_MQMD_COLUMNS);
                 PreparedStatement alterSentAtPs = conn.prepareStatement(ALTER_ADD_SENT_AT)) {
                createPs.execute();
                // Additive (issue #19): add the six recovered-MQMD columns if not already present.
                alterPs.execute();
                // Additive (issue #21): add the sent_at latency column if not already present.
                alterSentAtPs.execute();
                LOG.info("[stage=AUDIT-INIT] Delivery-report audit schema ensured "
                        + "(delivery_report + recovered-MQMD columns + sent_at)");
                return;
            } catch (SQLException e) {
                last = e;
                LOG.warn("[stage=AUDIT-INIT] Postgres unavailable while ensuring delivery_report schema "
                        + "(attempt {}/{}): {}", attempt, maxAttempts, e.getMessage());
                try {
                    Thread.sleep(3_000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new IllegalStateException(
                "Failed to ensure the delivery_report audit schema after " + maxAttempts + " attempts", last);
    }
}
