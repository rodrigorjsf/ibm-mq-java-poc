package com.example.ibmmq.persistence;

import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Query;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.GenericRepository;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Writer repository for the append-only {@code delivery_report} audit table — bound to the
 * {@code default} (writer/primary) datasource.
 *
 * <p><b>Datasource binding:</b> this repository deliberately OMITS a {@code @Repository("name")} value,
 * so it binds to the {@code default} connection and its transaction manager. Per the read/write split
 * (ADR-0005), the writer datasource is named {@code default} — the same connection that backs
 * {@code JdbcCorrelationStore}'s bare {@code DataSource} injection and the harness
 * {@code DATASOURCES_DEFAULT_*} env. Writes (audit inserts) always go here, never to the lagging
 * replica.</p>
 *
 * <p><b>Idempotent insert (AC4):</b> {@code ReportMessageConsumer} reads the report queue under
 * {@code AUTO_ACKNOWLEDGE}, so a report is acked BEFORE it is processed and the queue manager redelivers
 * reports at-least-once; competing report-consumers may also double-process the same report. A plain
 * {@code CrudRepository.save()} would throw on the UNIQUE {@code (correlation_id, feedback)} violation,
 * breaking the acked reconciliation path. We therefore expose a single explicit
 * {@code INSERT ... ON CONFLICT DO NOTHING} statement: a duplicate is a silent no-op (zero rows, no
 * thrown exception), so redelivery is idempotent. The DB-side {@code observed_at} default is NOT relied
 * upon — the caller passes the observation instant explicitly so the audit timestamp is the
 * application's, not the database clock's.</p>
 *
 * <p><b>Dedup-key assumption:</b> the {@code (correlation_id, feedback)} key is collision-free for
 * distinct legitimate reports ONLY under the default {@code MQRO_COPY_MSG_ID_TO_CORREL_ID} report option
 * — there the report's CorrelationId equals the original (QM-generated, unique) MessageId, so a COA(259)
 * and a COD(260) for one message are distinct rows and two different messages never share a CorrelationId.
 * If a future report path ever used {@code MQRO_PASS_CORREL_ID} with a shared/business CorrelationId across
 * distinct messages, this key would wrongly dedup them — widen it to include {@code original_message_id}.</p>
 *
 * <p><b>Bean gating:</b> active only when a {@code default} datasource is configured
 * ({@code datasources.default.url} present — env-driven in the k3s harness and set by the persistence
 * IT). The committed {@code application.yml} has NO datasources block on purpose, so unit/context tests
 * that run a bare {@code ApplicationContext} never spin up HikariCP against a non-existent DB and this
 * repository stays inert there.</p>
 */
@Requires(property = "datasources.default.url")
@JdbcRepository(dialect = Dialect.POSTGRES)
public interface DeliveryReportWriteRepository extends GenericRepository<DeliveryReportRecord, Long> {

    /**
     * Idempotently inserts one audit row carrying the issue-#40 columns PLUS the six issue-#19 recovered
     * MQMD columns (all nullable). Returns the number of rows actually inserted: {@code 1} for a new
     * {@code (correlationId, feedback)} pair, {@code 0} when the row already exists (duplicate report /
     * concurrent double-processing) — never throws on the conflict. The {@code (correlation_id, feedback)}
     * dedup key is unchanged; the six MQMD columns are purely additive.
     */
    @Query("""
            INSERT INTO delivery_report (
                correlation_id, original_message_id, report_type, feedback, observed_at,
                appl_identity_data, accounting_token_hex, correlation_id_bytes_hex,
                message_id_bytes_hex, put_timestamp_utc, report_type_char, sent_at)
            VALUES (
                :correlationId, :originalMessageId, :reportType, :feedback, :observedAt,
                :applIdentityData, :accountingTokenHex, :correlationIdBytesHex,
                :messageIdBytesHex, :putTimestampUtc, :reportTypeChar, :sentAt)
            ON CONFLICT (correlation_id, feedback) DO NOTHING""")
    int insertIfAbsent(String correlationId,
                       String originalMessageId,
                       String reportType,
                       int feedback,
                       Instant observedAt,
                       @Nullable String applIdentityData,
                       @Nullable String accountingTokenHex,
                       @Nullable String correlationIdBytesHex,
                       @Nullable String messageIdBytesHex,
                       @Nullable LocalDateTime putTimestampUtc,
                       @Nullable String reportTypeChar,
                       @Nullable Instant sentAt);

    /**
     * Backward-compatible 11-arg overload (issue #19 callers / ITs): inserts with the issue-#21
     * {@code sent_at} latency column left {@code null}. Delegates to the 12-arg {@link #insertIfAbsent}.
     */
    default int insertIfAbsent(String correlationId,
                               String originalMessageId,
                               String reportType,
                               int feedback,
                               Instant observedAt,
                               @Nullable String applIdentityData,
                               @Nullable String accountingTokenHex,
                               @Nullable String correlationIdBytesHex,
                               @Nullable String messageIdBytesHex,
                               @Nullable LocalDateTime putTimestampUtc,
                               @Nullable String reportTypeChar) {
        return insertIfAbsent(correlationId, originalMessageId, reportType, feedback, observedAt,
                applIdentityData, accountingTokenHex, correlationIdBytesHex, messageIdBytesHex,
                putTimestampUtc, reportTypeChar, null);
    }

    /**
     * Backward-compatible 5-arg overload (issue #40 callers / ITs): inserts with the six issue-#19 MQMD
     * columns AND the issue-#21 {@code sent_at} column left {@code null}. Delegates to the 11-arg overload.
     */
    default int insertIfAbsent(String correlationId,
                               String originalMessageId,
                               String reportType,
                               int feedback,
                               Instant observedAt) {
        return insertIfAbsent(correlationId, originalMessageId, reportType, feedback, observedAt,
                null, null, null, null, null, null);
    }
}
