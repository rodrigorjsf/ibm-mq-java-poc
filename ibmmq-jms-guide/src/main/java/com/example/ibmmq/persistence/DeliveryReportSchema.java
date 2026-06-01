package com.example.ibmmq.persistence;

import io.micronaut.context.annotation.Requires;
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Creates the append-only {@code delivery_report} audit table on startup, on the {@code default}
 * (writer/primary) datasource.
 *
 * <p>This mirrors {@code JdbcCorrelationStore.ensureSchema()} deliberately: a {@code @PostConstruct}
 * {@code CREATE TABLE IF NOT EXISTS} with a bounded retry/backoff loop. {@code IF NOT EXISTS} is
 * idempotent and safe under N concurrent report-consumer replicas (the first pod creates it, the rest
 * are no-ops); the backoff is defence-in-depth for a Postgres that is not yet ready when a pod boots
 * (belt-and-suspenders with the {@code wait-deps} initContainer).</p>
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
 * </pre>
 *
 * <p>The streaming-replicated standby receives this table (and every appended row) by physical
 * replication — no DDL runs against the replica.</p>
 *
 * <p><b>Bean gating:</b> active only when a {@code default} datasource is configured
 * ({@code datasources.default.url} present — same gate as {@link DeliveryReportWriteRepository}), so a
 * bare unit-test {@code ApplicationContext} (no datasources) never tries to create the table.</p>
 */
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

    private final DataSource dataSource;

    public DeliveryReportSchema(DataSource dataSource) {
        // Unwrap Micronaut Data's contextual DataSource proxy so this @PostConstruct can run a raw
        // CREATE TABLE via plain JDBC: with micronaut-data-jdbc on the classpath the injected DataSource is
        // wrapped (DelegatingDataSourceResolver) and a getConnection() outside a @Connectable/@Transactional
        // scope throws NoConnectionException. unwrapDataSource is a no-op when already unwrapped. Mirrors the
        // same fix in JdbcCorrelationStore. See research-output/micronaut-data-cqrs-readwrite-split.md (F6).
        this.dataSource = DelegatingDataSource.unwrapDataSource(dataSource);
    }

    /**
     * Creates the table if absent. Runs on each pod's startup; {@code CREATE TABLE IF NOT EXISTS} is
     * safe under N concurrent replicas (first creates, rest are no-ops). Retries with backoff while
     * Postgres is still coming up.
     */
    @PostConstruct
    void ensureSchema() {
        final int maxAttempts = 10;
        SQLException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(DDL)) {
                ps.execute();
                LOG.info("[stage=AUDIT-INIT] Delivery-report audit schema ensured (delivery_report)");
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
