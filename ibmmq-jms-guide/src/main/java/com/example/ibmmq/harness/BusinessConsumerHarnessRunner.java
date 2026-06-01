package com.example.ibmmq.harness;

import com.example.ibmmq.consumer.BusinessMessageConsumer;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runner do papel <b>business-consumer</b>: aciona repetidamente o
 * {@link BusinessMessageConsumer#receiveOne} EXISTENTE para consumir (GET destrutivo) da fila de
 * negocio. Cada consumo confirmado (commit) libera o relatorio COD.
 *
 * <p><b>Competing consumers (AC1):</b> este Deployment e escalado para N replicas; todas leem a MESMA
 * fila de negocio. O IBM MQ distribui as mensagens entre os consumidores concorrentes (um GET por
 * mensagem), realizando o padrao competing-consumer real entre pods.</p>
 *
 * <p>Ativo apenas quando {@code harness.role=business-consumer}.</p>
 */
@Singleton
@Requires(property = "harness.role", value = "business-consumer")
public class BusinessConsumerHarnessRunner extends AbstractHarnessRunner {

    private static final Logger LOG = LoggerFactory.getLogger(BusinessConsumerHarnessRunner.class);

    private final BusinessMessageConsumer consumer;
    private final HarnessProperties harness;

    public BusinessConsumerHarnessRunner(BusinessMessageConsumer consumer, HarnessProperties harness) {
        this.consumer = consumer;
        this.harness = harness;
    }

    @Override
    protected String roleName() {
        return "business-consumer";
    }

    @Override
    protected Logger log() {
        return LOG;
    }

    @Override
    protected void runOnce() {
        // receiveOne bloqueia ate o timeout; null = nada no periodo -> o laco apenas tenta de novo.
        consumer.receiveOne(harness.getReceiveTimeoutMillis());
    }
}
