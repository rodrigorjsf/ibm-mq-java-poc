package com.example.ibmmq.messaging;

import com.example.ibmmq.model.ReportType;
import com.example.ibmmq.report.ReportDescriptor;

/**
 * Decoded inbound domain envelope that crosses the RECEIVE side of the messaging seam for a COA/COD report
 * (ADR-0008). It carries the already-extracted report data so NO {@code jakarta.jms.Message} reaches the
 * {@link com.example.ibmmq.consumer.ReportMessageConsumer}:
 *
 * <ul>
 *   <li>{@code feedbackCode} — the MQMD feedback (read from {@code JMS_IBM_Feedback}); 259=COA, 260=COD.</li>
 *   <li>{@code correlationId} — the report's {@code JMSCorrelationID} (== the original MsgId under the
 *       default {@code MQRO_COPY_MSG_ID_TO_CORREL_ID}).</li>
 *   <li>{@code body} — the report body text when present (typically empty for COA/COD).</li>
 *   <li>{@code descriptor} — the six recovered MQMD values (issue #19), a fully JMS-free record.</li>
 * </ul>
 *
 * <p>ALL JMS extraction ({@code getIntProperty(JMS_IBM_FEEDBACK)}, {@code getJMSCorrelationID},
 * {@link ReportDescriptor#from}) happens INSIDE the pooled-JMS adapter; the fake adapter synthesizes an
 * equivalent envelope in memory. The report consumer then classifies and reconciles purely on this record.</p>
 *
 * @param feedbackCode  raw MQMD feedback code (e.g. 259=COA, 260=COD; an {@code MQRC_*} for exception reports).
 * @param correlationId the report's correlation id (== original MsgId by default).
 * @param body          the report body text, or {@code null}/empty when the report has no payload.
 * @param descriptor    the six recovered MQMD values; never {@code null} (its fields may individually be null).
 */
public record ReportEnvelope(
        int feedbackCode,
        String correlationId,
        String body,
        ReportDescriptor descriptor
) {

    /**
     * Builds a report envelope, deriving the descriptor's report-type char from the supplied feedback when a
     * descriptor is not otherwise available. Convenience for the in-memory fake, which has no real
     * {@code jakarta.jms.Message} to extract from.
     *
     * @param feedbackCode  the feedback code.
     * @param correlationId the correlation id.
     * @param body          the body (may be {@code null}).
     * @param type          the classified report type, used to derive the descriptor's report-type char.
     * @return a report envelope whose descriptor carries only the derived report-type char (the byte[]/
     *         timestamp MQMD fields are {@code null}, mirroring a report received without MQMD read enabled).
     */
    public static ReportEnvelope synthetic(int feedbackCode, String correlationId, String body, ReportType type) {
        char reportTypeChar = type == null ? ReportType.DOMAIN_CHAR_OTHER : type.toDomainChar();
        ReportDescriptor descriptor =
                new ReportDescriptor(null, null, null, null, null, reportTypeChar);
        return new ReportEnvelope(feedbackCode, correlationId, body, descriptor);
    }
}
