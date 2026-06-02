package com.example.ibmmq.messaging;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

/**
 * Broker-free {@link ReceivePort} fake (ADR-0008 AC1/AC5). Delegates to the shared {@link InMemoryBroker}:
 *
 * <ul>
 *   <li>{@link #receiveWithinUnitOfWork} consumes the head business message, invokes the handler, and
 *       <b>commits</b> on a normal return (the broker then fires the COD) or <b>rolls back</b> on a throw
 *       (the message returns; no COD) — modelling SESSION_TRANSACTED exactly.</li>
 *   <li>{@link #receiveReport} reads the head report (COA/COD) under AUTO_ACKNOWLEDGE semantics
 *       (behavior-preserving — no unit of work).</li>
 * </ul>
 *
 * <p>The queue-name arguments are accepted for interface fidelity but ignored by the single-queue-pair fake
 * model; the broker is wired to one business queue and one report queue.</p>
 *
 * <p><b>Bean.</b> Present only when {@code messaging.adapter=fake}; in production the
 * {@link PooledJmsReceiveAdapter} is wired instead.</p>
 */
@Singleton
@Requires(property = "messaging.adapter", value = "fake")
public class InMemoryReceivePort implements ReceivePort {

    private final InMemoryBroker broker;

    public InMemoryReceivePort(InMemoryBroker broker) {
        this.broker = broker;
    }

    @Override
    public String receiveWithinUnitOfWork(String queueName, long timeoutMillis, UnitOfWorkHandler handler) {
        String body = broker.receive();
        if (body == null) {
            return null; // timeout — nothing consumed.
        }
        String result;
        try {
            result = handler.handle(body);
        } catch (Exception handlerFailure) {
            // Rollback: the message returns to the queue; no COD is fired.
            broker.rollback();
            throw new IllegalStateException("Unit-of-work handler failed — rolled back", handlerFailure);
        }
        // Commit: releases the COD.
        broker.commit();
        return result;
    }

    @Override
    public ReportEnvelope receiveReport(String queueName, long timeoutMillis) {
        return broker.receiveReport();
    }
}
