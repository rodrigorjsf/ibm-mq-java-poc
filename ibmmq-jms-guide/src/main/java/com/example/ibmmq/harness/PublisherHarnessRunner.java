package com.example.ibmmq.harness;

import com.example.ibmmq.producer.BusinessMessageProducer;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
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
    private final AtomicBoolean doneLogged = new AtomicBoolean();

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
        long max = harness.getPublishMaxCount();
        if (max > 0 && counter.get() >= max) {
            // Quota atingida (run de carga LIMITADO): para de PRODUZIR mas mantem a JVM/pod viva (ociosa),
            // de modo que este pod publisher emita EXATAMENTE `max` mensagens — o denominador-N conhecido da
            // assercao de zero-perda de #21 (ADR-0007) — sem o Deployment reiniciar um pod concluido para
            // outra leva. A thread worker nao-daemon segue iterando aqui (JVM viva, liveness probe passa).
            if (doneLogged.compareAndSet(false, true)) {
                log().info("[stage=PUBLISH-DONE] Quota de carga atingida: {} mensagens enviadas; ocioso ate o shutdown",
                        counter.get());
            }
            Thread.sleep(1_000L);
            return;
        }
        long n = counter.get() + 1;
        String businessKey = harness.getBusinessKeyPrefix() + "-" + n;
        String payload = "{\"businessKey\":\"" + businessKey + "\",\"seq\":" + n + "}";
        producer.send(businessKey, payload);
        // Conta apenas envios BEM-SUCEDIDOS: se send() lancar, o catch do AbstractHarnessRunner re-tenta SEM
        // incrementar — assim um pod que loga PUBLISH-DONE enviou EXATAMENTE `max` mensagens (denominador-N
        // exato para a assercao de zero-perda de #21, ADR-0007).
        counter.incrementAndGet();
        Thread.sleep(harness.getPublishIntervalMillis());
    }
}
