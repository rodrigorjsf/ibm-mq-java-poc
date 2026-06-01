package com.example.ibmmq.harness;

import com.example.ibmmq.producer.BusinessMessageProducer;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Runner do papel <b>publisher</b>: aciona repetidamente o {@link BusinessMessageProducer#send}
 * EXISTENTE para alimentar a fila de negocio com mensagens COA+COD, dirigindo o fluxo
 * producer -> queue -> competing consumers -> reports do harness k3s.
 *
 * <p>Ativo apenas quando {@code harness.role=publisher}. Single-thread por pod (ver
 * {@link AbstractHarnessRunner}); para mais throughput, escale o Deployment do publisher.</p>
 */
@Singleton
@Requires(property = "harness.role", value = "publisher")
public class PublisherHarnessRunner extends AbstractHarnessRunner {

    private static final Logger LOG = LoggerFactory.getLogger(PublisherHarnessRunner.class);

    private final BusinessMessageProducer producer;
    private final HarnessProperties harness;
    private final AtomicLong counter = new AtomicLong();

    public PublisherHarnessRunner(BusinessMessageProducer producer, HarnessProperties harness) {
        this.producer = producer;
        this.harness = harness;
    }

    @Override
    protected String roleName() {
        return "publisher";
    }

    @Override
    protected Logger log() {
        return LOG;
    }

    @Override
    protected void runOnce() throws InterruptedException {
        long n = counter.incrementAndGet();
        String businessKey = harness.getBusinessKeyPrefix() + "-" + n;
        String payload = "{\"businessKey\":\"" + businessKey + "\",\"seq\":" + n + "}";
        producer.send(businessKey, payload);
        Thread.sleep(harness.getPublishIntervalMillis());
    }
}
