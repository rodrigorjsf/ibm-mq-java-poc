package com.example.ibmmq.messaging;

import com.example.ibmmq.model.ReportType;
import com.ibm.mq.constants.MQConstants;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Broker-free, in-memory model of the IBM MQ COA/COD report semantics, shared by {@link InMemorySendPort}
 * and {@link InMemoryReceivePort}. It lets the full produce -> consume -> receive-report -> reconcile flow
 * run with NO broker (ADR-0008 AC5), modelling exactly the behaviours the seam must preserve:
 *
 * <ul>
 *   <li><b>COA on put (259).</b> A {@link #put} (send) immediately enqueues a COA report on the reply-to
 *       queue, mirroring "Confirmation On Arrival" the moment the message lands on the destination.</li>
 *   <li><b>COD on consume + commit (260).</b> A destructive {@link #receive} removes the business message but
 *       the COD is enqueued ONLY when the consume is committed ({@link #commit}); a {@link #rollback} returns
 *       the message and yields NO COD — exactly the SESSION_TRANSACTED semantics of the real consumer.</li>
 *   <li><b>CorrelId == original MessageId.</b> Both reports carry {@code correlationId == messageId} of the
 *       business message, mirroring the default {@code MQRO_COPY_MSG_ID_TO_CORREL_ID} propagation.</li>
 * </ul>
 *
 * <p>The model is intentionally single-broker / single-queue-pair (one business queue, one report queue),
 * sufficient for the unit test of the flow. Thread-safe via a single intrinsic lock; the test drives it from
 * one thread.</p>
 *
 * <p><b>Bean.</b> Present only when {@code messaging.adapter=fake} (broker-free unit tests); absent in
 * production where the pooled-JMS adapters are wired instead.</p>
 */
@Singleton
@Requires(property = "messaging.adapter", value = "fake")
public class InMemoryBroker {

    /** A business message in flight, plus its in-flight consume state. */
    private record InFlight(String messageId, String payload, String replyToQueue,
                            boolean requestCoa, boolean requestCod) {
    }

    private final AtomicLong sequence = new AtomicLong();
    // The single business queue (FIFO) of messages awaiting a destructive consume.
    private final Deque<InFlight> businessQueue = new ArrayDeque<>();
    // The single report queue (FIFO) of generated COA/COD reports.
    private final Deque<ReportEnvelope> reportQueue = new ArrayDeque<>();
    // The message currently consumed-but-not-yet-committed (one single-threaded consumer per pod).
    private InFlight uncommitted;

    /**
     * Models a send/put: assigns a {@code messageId}, enqueues the business message, and (when COA is
     * requested) immediately enqueues the COA report (259) — COA fires on arrival.
     *
     * @return the assigned message id (with the real "ID:" hex-style prefix shape).
     */
    public synchronized String put(OutboundMessage message) {
        String messageId = "ID:" + String.format("%040x", sequence.incrementAndGet());
        InFlight inFlight = new InFlight(
                messageId, message.payload(), message.replyToQueue(),
                message.requestCoa(), message.requestCod());
        businessQueue.addLast(inFlight);
        if (message.requestCoa()) {
            // COA on arrival: correlationId == original messageId.
            reportQueue.addLast(ReportEnvelope.synthetic(
                    MQConstants.MQFB_COA, messageId, "", ReportType.COA));
        }
        return messageId;
    }

    /**
     * Models a destructive GET within a transacted unit of work: removes the head business message and holds
     * it as uncommitted. The COD is NOT generated yet (only on {@link #commit}). Returns {@code null} when the
     * business queue is empty (timeout in the real consumer).
     *
     * @return the payload of the consumed message, or {@code null} when there is nothing to consume.
     */
    public synchronized String receive() {
        InFlight head = businessQueue.pollFirst();
        if (head == null) {
            return null;
        }
        uncommitted = head;
        return head.payload();
    }

    /**
     * Commits the in-flight consume: releases the COD report (260) on the reply-to queue when the original
     * requested COD. COD fires only here — never before commit.
     */
    public synchronized void commit() {
        InFlight committed = uncommitted;
        uncommitted = null;
        if (committed != null && committed.requestCod()) {
            reportQueue.addLast(ReportEnvelope.synthetic(
                    MQConstants.MQFB_COD, committed.messageId(), "", ReportType.COD));
        }
    }

    /**
     * Rolls back the in-flight consume: the business message returns to the head of the queue and NO COD is
     * generated — exactly the backout semantics of SESSION_TRANSACTED.
     */
    public synchronized void rollback() {
        InFlight rolledBack = uncommitted;
        uncommitted = null;
        if (rolledBack != null) {
            businessQueue.addFirst(rolledBack);
        }
    }

    /**
     * Receives the head report from the report queue, or {@code null} when none is available (timeout).
     */
    public synchronized ReportEnvelope receiveReport() {
        return reportQueue.pollFirst();
    }
}
