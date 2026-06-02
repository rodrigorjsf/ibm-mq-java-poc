package com.example.ibmmq.messaging;

/**
 * The SEND side of the messaging seam (ADR-0008): the role-based port over the <b>pooled producer
 * factory</b>. {@link com.example.ibmmq.producer.BusinessMessageProducer} calls through this port instead
 * of opening its own {@code JMSContext}.
 *
 * <p>Only decoded domain envelopes cross the seam — never a {@code javax.jms.Message}. The implementation
 * (the pooled-JMS adapter) owns all JMS construction and the {@code queue:///} destination resolution; the
 * in-memory fake models the same contract without a broker.</p>
 *
 * <h2>Adapters</h2>
 * <ul>
 *   <li>{@code PooledJmsSendAdapter} — production: a {@code createContext} per send over the pooled
 *       {@code JmsPoolConnectionFactory} (ADR-0006 producer factory).</li>
 *   <li>{@code InMemorySendPort} — broker-free fake: records the send and generates the COA/COD reports
 *       faithfully on the paired {@code InMemoryReceivePort}.</li>
 * </ul>
 */
public interface SendPort {

    /**
     * Sends one outbound business message, requesting the COA/COD reports described by the envelope.
     *
     * @param message the decoded outbound envelope (payload + businessKey + report options + replyTo +
     *                persistence).
     * @return the assigned {@code messageId} — the correlation key for the future COA/COD reports (under the
     *         default {@code MQRO_COPY_MSG_ID_TO_CORREL_ID}, the report's {@code CorrelationId == messageId}).
     */
    String send(OutboundMessage message);
}
