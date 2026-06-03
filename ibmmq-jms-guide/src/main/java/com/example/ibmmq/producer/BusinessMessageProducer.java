package com.example.ibmmq.producer;

import com.example.ibmmq.config.MqProperties;
import com.example.ibmmq.correlation.CorrelationStore;
import com.example.ibmmq.logging.MdcTraceScope;
import com.example.ibmmq.messaging.OutboundMessage;
import com.example.ibmmq.messaging.SendPort;
import com.example.ibmmq.model.PendingMessage;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Produces business messages (JSON) onto the business queue, requesting COA and COD reports.
 *
 * <p><b>Report flow:</b> by enabling COA and COD via the {@code JMS_IBM_Report_*} JMS properties,
 * the queue manager will generate:
 * <ul>
 *   <li><b>COA</b> when the message is PUT onto the destination queue (arrival timing);</li>
 *   <li><b>COD</b> when the consuming app performs a destructive GET (delivery timing).</li>
 * </ul>
 * Both reports are sent to the queue indicated in {@code JMSReplyTo}.</p>
 *
 * <p><b>Correlation:</b> we do not set id-propagation options, so the default
 * {@code MQRO_COPY_MSG_ID_TO_CORREL_ID} applies: this message's MessageId becomes the report's CorrelationId.
 * We register the MessageId in the {@link CorrelationStore} to close the cycle when the report arrives.</p>
 *
 * <p><b>Seam (ADR-0008):</b> this entry point no longer opens its own {@code JMSContext} — it delegates to
 * the {@link SendPort} (the send port over the pooled producer factory, ADR-0006). All {@code jakarta.jms}
 * construction (TextMessage, JMSReplyTo, report options, {@code queue:///} resolution) lives in the
 * pooled-JMS adapter; the producer only assembles the decoded {@link OutboundMessage} and never sees a
 * {@code jakarta.jms.Message}.</p>
 */
@Singleton
public class BusinessMessageProducer {

    private static final Logger LOG = LoggerFactory.getLogger(BusinessMessageProducer.class);

    private final SendPort sendPort;
    private final MqProperties props;
    private final CorrelationStore correlationStore;

    public BusinessMessageProducer(SendPort sendPort,
                                   MqProperties props,
                                   CorrelationStore correlationStore) {
        this.sendPort = sendPort;
        this.props = props;
        this.correlationStore = correlationStore;
    }

    /**
     * Sends a persistent business message with COA+COD enabled.
     *
     * @param businessKey domain identifier (e.g. order id) — for logging/auditing.
     * @param jsonPayload already-serialized JSON body.
     * @return the assigned JMSMessageID (correlation key with the reports).
     */
    public String send(String businessKey, String jsonPayload) {
        // Decoded outbound envelope: a persistent business message with both COA and COD requested,
        // bound for the report (reply-to) queue. The SendPort adapter owns all JMS construction.
        OutboundMessage outbound = OutboundMessage.persistentWithCoaCod(
                businessKey, jsonPayload, props.getBusinessQueue(), props.getReportQueue());

        // The SendPort returns the assigned messageId only after the send. Default
        // MQRO_COPY_MSG_ID_TO_CORREL_ID makes this id the report's CorrelationId.
        String messageId = sendPort.send(outbound);

        // MDC trace context: bind messageId and correlationId so this first lifecycle step already
        // carries the same ids the COA/COD steps will. Because the IBM MQ default is
        // MQRO_COPY_MSG_ID_TO_CORREL_ID, the future report's CorrelationId WILL be this messageId — so
        // we bind correlationId = messageId here (the key that closes the cycle). The try-with-resources
        // guarantees both keys are cleared before the thread (virtual/carrier) returns to the pool, so
        // under ~10k rpm a reused thread cannot leak this message's ids to the next.
        try (var scope = MdcTraceScope.bind(messageId, messageId)) {
            correlationStore.register(PendingMessage.newlySent(messageId, businessKey, jsonPayload));

            LOG.info("[stage=PRODUCE] Business message sent: businessKey={}, messageId={}, replyTo={}",
                    businessKey, messageId, props.getReportQueue());

            return messageId;
        }
    }
}
