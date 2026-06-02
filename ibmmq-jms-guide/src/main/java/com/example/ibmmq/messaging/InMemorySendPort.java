package com.example.ibmmq.messaging;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

/**
 * Broker-free {@link SendPort} fake (ADR-0008 AC1/AC5). Delegates to the shared {@link InMemoryBroker}, which
 * models a put as "enqueue the business message + fire the COA report on arrival". The paired
 * {@link InMemoryReceivePort} reads back from the same broker.
 *
 * <p><b>Bean.</b> Present only when {@code messaging.adapter=fake}; in production the
 * {@link PooledJmsSendAdapter} is wired instead.</p>
 */
@Singleton
@Requires(property = "messaging.adapter", value = "fake")
public class InMemorySendPort implements SendPort {

    private final InMemoryBroker broker;

    public InMemorySendPort(InMemoryBroker broker) {
        this.broker = broker;
    }

    @Override
    public String send(OutboundMessage message) {
        return broker.put(message);
    }
}
