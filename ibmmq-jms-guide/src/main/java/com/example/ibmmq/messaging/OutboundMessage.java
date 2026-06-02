package com.example.ibmmq.messaging;

/**
 * Decoded outbound domain envelope that crosses the SEND side of the messaging seam (ADR-0008).
 *
 * <p>It carries everything the adapter needs to build and send a business message WITHOUT exposing any
 * {@code javax.jms} type to the caller: the payload, a domain {@code businessKey} for traceability, the
 * destination (business) queue, the report options (COA/COD), the reply-to queue (where the QMgr delivers
 * the generated reports), and the {@link DeliveryPersistence persistence mode}. The {@link SendPort} returns
 * the assigned {@code messageId} (the correlation key for the future COA/COD reports under the default
 * {@code MQRO_COPY_MSG_ID_TO_CORREL_ID} propagation).</p>
 *
 * <p>All JMS construction (createTextMessage, {@code setJMSReplyTo}, the {@code JMS_IBM_REPORT_*}
 * properties, {@code DeliveryMode}) and the {@code queue:///} resolution live INSIDE the pooled-JMS adapter;
 * the fake adapter models the same fields purely in memory. Queue names are PLAIN (no {@code queue:///}
 * prefix) — the adapter adds the prefix.</p>
 *
 * @param businessKey      domain identifier (e.g. order id) — for logs/audit, not sent on the wire.
 * @param payload          already-serialized message body (e.g. JSON).
 * @param destinationQueue plain business queue name the message is sent to.
 * @param replyToQueue     plain queue name the reports are delivered to.
 * @param requestCoa       request a Confirmation On Arrival report.
 * @param requestCod       request a Confirmation On Delivery report.
 * @param persistence      the message persistence mode (reports inherit it).
 */
public record OutboundMessage(
        String businessKey,
        String payload,
        String destinationQueue,
        String replyToQueue,
        boolean requestCoa,
        boolean requestCod,
        DeliveryPersistence persistence
) {

    /**
     * Convenience factory for the project's canonical outbound message: a persistent business message with
     * both COA and COD reports requested.
     *
     * @param businessKey      domain identifier.
     * @param payload          serialized body.
     * @param destinationQueue plain business queue name.
     * @param replyToQueue     plain report queue name.
     * @return a persistent COA+COD outbound envelope.
     */
    public static OutboundMessage persistentWithCoaCod(String businessKey, String payload,
                                                       String destinationQueue, String replyToQueue) {
        return new OutboundMessage(
                businessKey, payload, destinationQueue, replyToQueue, true, true, DeliveryPersistence.PERSISTENT);
    }
}
