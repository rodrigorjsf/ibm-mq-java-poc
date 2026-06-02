package com.example.ibmmq.report;

import com.example.ibmmq.model.ReportType;
import com.ibm.msg.client.wmq.WMQConstants;

import javax.jms.JMSException;
import javax.jms.Message;
import java.time.LocalDateTime;

/**
 * Recovers the six MQMD values of a received COA/COD report from the report's OWN descriptor (issue #19,
 * verdict {@code (R)}-all — see {@code research-output/phase-f-mqmd-field-recovery.md}). No producer change,
 * no {@code WITH_FULL_DATA}, no embedded-original parsing.
 *
 * <p>The six recovered values:</p>
 * <ol>
 *   <li><b>{@code applIdentityData}</b> ({@code String}) — the report's own ApplIdentityData (QMgr-set;
 *       may be blank) via {@code JMS_IBM_MQMD_ApplIdentityData}.</li>
 *   <li><b>{@code accountingToken}</b> ({@code byte[]}, 32 bytes) — the report's own AccountingToken via
 *       {@code JMS_IBM_MQMD_AccountingToken} (a {@code byte[]} object property; a hex {@code String} form
 *       is handled defensively).</li>
 *   <li><b>{@code correlationIdBytes}</b> ({@code byte[]}) — {@code getJMSCorrelationIDAsBytes()}; with the
 *       default {@code MQRO_COPY_MSG_ID_TO_CORREL_ID} this IS the original message's MsgId bytes — the
 *       cross-report link to the original.</li>
 *   <li><b>{@code messageIdBytes}</b> ({@code byte[]}) — the report's OWN MsgId via
 *       {@code JMS_IBM_MQMD_MsgId} (a {@code byte[]} object property; there is no
 *       {@code getJMSMessageIDAsBytes}).</li>
 *   <li><b>{@code putTimestampUtc}</b> ({@link LocalDateTime}) — the report-generation time, parsed from
 *       {@code PutDate}+{@code PutTime} (GMT) with an explicit UTC offset by {@link MqmdTimestamps}.</li>
 *   <li><b>{@code reportTypeChar}</b> ({@code char}) — pure derivation: {@link ReportType#toDomainChar()}.</li>
 * </ol>
 *
 * <p><b>Activation:</b> the {@code JMS_IBM_MQMD_*} properties are populated only when MQMD read is enabled
 * on the consume destination (the report consumer enables it via the {@code queue:///...?mdReadEnabled=true}
 * URI form). When read is NOT enabled, or when running against a bare Mockito mock that only stubs feedback
 * + correlationId, every MQMD getter returns {@code null} — the extractor is therefore fully null-safe and
 * <b>never throws</b>. A throw here would abort the already-acked report path (the outer catch in
 * {@code ReportMessageConsumer.handleReport} would surface it as an {@code IllegalStateException}).</p>
 *
 * <p><b>Allocation discipline (~167 msg/s):</b> a handful of property reads + one parse per report; no
 * reflective lookups, no intermediate collections.</p>
 *
 * @param applIdentityData   the report's own ApplIdentityData (may be {@code null}/blank).
 * @param accountingToken    the report's own AccountingToken bytes (may be {@code null}; typically 32 bytes).
 * @param correlationIdBytes the report's CorrelId bytes == original MsgId under default id propagation (may
 *                           be {@code null}).
 * @param messageIdBytes     the report's own MsgId bytes (may be {@code null} when read is not enabled).
 * @param putTimestampUtc    the report put-time as a UTC wall-clock (may be {@code null}).
 * @param reportTypeChar     the derived report-type char ({@code 'A'}/{@code 'D'}/sentinel).
 */
public record ReportDescriptor(
        String applIdentityData,
        byte[] accountingToken,
        byte[] correlationIdBytes,
        byte[] messageIdBytes,
        LocalDateTime putTimestampUtc,
        char reportTypeChar
) {

    /**
     * Extracts the six MQMD values from a received report. Null-safe and non-throwing: any
     * {@link JMSException} from a property read is swallowed (the property is treated as absent), and every
     * field independently degrades to {@code null} (or the derived char for field #6) when its source is
     * unavailable.
     *
     * @param report the received JMS report message (never {@code null}).
     * @param type   the already-classified report type (drives field #6).
     * @return a fully-populated descriptor; individual fields may be {@code null} when MQMD read is not
     *         enabled or the value is absent.
     */
    public static ReportDescriptor from(Message report, ReportType type) {
        String applIdentity = stringProperty(report, WMQConstants.JMS_IBM_MQMD_APPLIDENTITYDATA);
        byte[] accountingToken = bytesProperty(report, WMQConstants.JMS_IBM_MQMD_ACCOUNTINGTOKEN);
        byte[] correlIdBytes = correlationIdBytes(report);
        byte[] msgIdBytes = bytesProperty(report, WMQConstants.JMS_IBM_MQMD_MSGID);

        String putDate = stringProperty(report, WMQConstants.JMS_IBM_MQMD_PUTDATE);
        String putTime = stringProperty(report, WMQConstants.JMS_IBM_MQMD_PUTTIME);
        LocalDateTime putTimestampUtc = MqmdTimestamps.parse(putDate, putTime);

        char reportTypeChar = type == null ? ReportType.DOMAIN_CHAR_OTHER : type.toDomainChar();

        return new ReportDescriptor(
                applIdentity, accountingToken, correlIdBytes, msgIdBytes, putTimestampUtc, reportTypeChar);
    }

    /** Reads a String property, returning {@code null} (never throwing) when absent/unreadable. */
    private static String stringProperty(Message report, String key) {
        try {
            return report.getStringProperty(key);
        } catch (JMSException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Reads a {@code byte[]} MQMD property. JMS 2.0 has no {@code getBytesProperty}; the MQMD byte field is
     * exposed as a {@code byte[]} object property. Defensively also accepts a hex {@code String} form.
     * Returns {@code null} (never throws) when absent/unreadable.
     */
    private static byte[] bytesProperty(Message report, String key) {
        try {
            Object value = report.getObjectProperty(key);
            if (value instanceof byte[] bytes) {
                return bytes;
            }
            if (value instanceof String hex) {
                return HexBytes.fromHex(hex);
            }
            return null;
        } catch (JMSException | RuntimeException e) {
            return null;
        }
    }

    /** Reads the report's CorrelId bytes via {@code getJMSCorrelationIDAsBytes()}, null-safe. */
    private static byte[] correlationIdBytes(Message report) {
        try {
            return report.getJMSCorrelationIDAsBytes();
        } catch (JMSException | RuntimeException e) {
            return null;
        }
    }

    // ---- Hex accessors (audit/log/equality safety for the byte[] fields) ----

    /** The AccountingToken as a lowercase hex string, or {@code null} when absent. */
    public String accountingTokenHex() {
        return HexBytes.toHex(accountingToken);
    }

    /** The CorrelId bytes (== original MsgId under default propagation) as hex, or {@code null}. */
    public String correlationIdBytesHex() {
        return HexBytes.toHex(correlationIdBytes);
    }

    /** The report's own MsgId bytes as hex, or {@code null}. */
    public String messageIdBytesHex() {
        return HexBytes.toHex(messageIdBytes);
    }
}
