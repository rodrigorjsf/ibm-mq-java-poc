package com.example.ibmmq.persistence;

import com.example.ibmmq.model.ReportType;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Append-only AUDIT record of a single COA/COD delivery report (issue #40).
 *
 * <p>Unlike the transient {@code pending_message} reconciliation ledger (which is deleted the moment a
 * message's COA+COD pair is confirmed), this {@code delivery_report} row is <b>never deleted</b>: it is
 * the durable, queryable history of "what reports did we receive, when, with what feedback?". At the
 * standing ~10k rpm (~334 report inserts/s) this is the read-model the {@code reader} (replica)
 * connection serves; writes go to the {@code default} (writer/primary) connection.</p>
 *
 * <p><b>Scope:</b> issue #40 persists the report type, the raw feedback code, the correlation id, the
 * correlated original message id, and the observation instant. Issue #19 ADDITIVELY appends the six
 * recovered MQMD fields (application identity data, accounting token hex, correlation-id bytes hex,
 * message-id bytes hex, put-timestamp UTC, report-type char) — all NULLABLE, mapped to columns added by
 * the idempotent {@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS} in {@link DeliveryReportSchema}. The
 * additive columns do not change the {@code (correlation_id, feedback)} dedup key.</p>
 *
 * <p><b>Idempotency (reports are at-least-once):</b> the writer table carries a UNIQUE constraint on
 * {@code (correlation_id, feedback)} (created by {@link DeliveryReportSchema}). Report redelivery and
 * concurrent processing by competing report-consumers therefore never create a duplicate row; the
 * writer repository's {@code INSERT ... ON CONFLICT DO NOTHING} makes a duplicate insert a silent
 * no-op (no thrown exception on the AUTO_ACKNOWLEDGE acked path).</p>
 */
@MappedEntity("delivery_report")
public record DeliveryReportRecord(
        @Id @GeneratedValue Long id,

        /** JMSCorrelationID of the report — with the default MQRO_COPY_MSG_ID_TO_CORREL_ID this equals
         *  the original message id. Part of the UNIQUE (correlation_id, feedback) dedup key. */
        @MappedProperty("correlation_id") String correlationId,

        /** Original business message id correlated back from the store (falls back to correlationId
         *  when the pending row is unknown — same fallback as ReportMessageConsumer). */
        @MappedProperty("original_message_id") String originalMessageId,

        /** Classified report type (COA, COD, ...). Stored by enum name (default Micronaut Data mapping). */
        @MappedProperty("report_type") ReportType reportType,

        /** Raw MQMD feedback code (e.g. 259=COA, 260=COD). Part of the UNIQUE dedup key. */
        @MappedProperty("feedback") int feedback,

        /** Instant the report was observed/processed by the report consumer. Always supplied explicitly by
         *  {@code ReportMessageConsumer} (the single shared event instant) and bound by the
         *  {@code insertIfAbsent} {@code @Query}. There is NO entity persist path (the repos extend
         *  {@code GenericRepository}, never {@code save()}), so {@code @DateCreated} auto-population is
         *  deliberately not used — it would otherwise stamp the DB clock instead of the application instant. */
        @MappedProperty("observed_at") Instant observedAt,

        // ---- Issue #19: six recovered MQMD fields (all NULLABLE, additive) ----

        /** Report's own ApplIdentityData (QMgr-set; may be blank/null). */
        @Nullable @MappedProperty("appl_identity_data") String applIdentityData,

        /** Report's own AccountingToken as lowercase hex (32 bytes => 64 hex chars; may be null). */
        @Nullable @MappedProperty("accounting_token_hex") String accountingTokenHex,

        /** Report's CorrelId bytes == original MsgId under default propagation, as hex (may be null). */
        @Nullable @MappedProperty("correlation_id_bytes_hex") String correlationIdBytesHex,

        /** Report's own MsgId bytes as hex (may be null when MQMD read is not enabled). */
        @Nullable @MappedProperty("message_id_bytes_hex") String messageIdBytesHex,

        /** Report put-time as a UTC wall-clock (mapped to a TIMESTAMP WITHOUT TIME ZONE column; may be null).
         *  Kept as {@link LocalDateTime} end-to-end so no JVM-default-zone is ever applied (AC3). */
        @Nullable @MappedProperty("put_timestamp_utc") LocalDateTime putTimestampUtc,

        /** Derived report-type char ('A'/'D'/sentinel) as a one-char String (CHAR(1) column). Kept as
         *  {@code String}, NOT primitive {@code char}, to map cleanly to a nullable CHAR(1) read-back. */
        @Nullable @MappedProperty("report_type_char") String reportTypeChar
) {

    /**
     * Convenience factory for a brand-new audit record with ONLY the issue-#40 columns (the six issue-#19
     * MQMD columns default to {@code null}). {@code id} is {@code null} (DB-generated).
     */
    public static DeliveryReportRecord of(String correlationId,
                                          String originalMessageId,
                                          ReportType reportType,
                                          int feedback,
                                          Instant observedAt) {
        return new DeliveryReportRecord(null, correlationId, originalMessageId, reportType, feedback,
                observedAt, null, null, null, null, null, null);
    }
}
