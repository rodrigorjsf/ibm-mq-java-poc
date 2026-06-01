package com.example.ibmmq.persistence;

import com.example.ibmmq.model.ReportType;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;

import java.time.Instant;

/**
 * Append-only AUDIT record of a single COA/COD delivery report (issue #40).
 *
 * <p>Unlike the transient {@code pending_message} reconciliation ledger (which is deleted the moment a
 * message's COA+COD pair is confirmed), this {@code delivery_report} row is <b>never deleted</b>: it is
 * the durable, queryable history of "what reports did we receive, when, with what feedback?". At the
 * standing ~10k rpm (~334 report inserts/s) this is the read-model the {@code reader} (replica)
 * connection serves; writes go to the {@code default} (writer/primary) connection.</p>
 *
 * <p><b>Scope (issue #40 only):</b> the fields persisted here are exactly those available at report
 * time inside {@code ReportMessageConsumer} — the report type, the raw feedback code, the correlation
 * id, the correlated original message id, and the observation instant. The six recovered MQMD fields
 * are issue #19's additive {@code ALTER} and are deliberately NOT included here.</p>
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
        @MappedProperty("observed_at") Instant observedAt
) {

    /**
     * Convenience factory for a brand-new audit record (id and observedAt assigned by the store):
     * {@code id} is {@code null} (DB-generated) and {@code observedAt} is set to the given instant.
     */
    public static DeliveryReportRecord of(String correlationId,
                                          String originalMessageId,
                                          ReportType reportType,
                                          int feedback,
                                          Instant observedAt) {
        return new DeliveryReportRecord(null, correlationId, originalMessageId, reportType, feedback, observedAt);
    }
}
