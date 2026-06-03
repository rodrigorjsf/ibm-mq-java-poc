package com.example.ibmmq.harness;

import com.example.ibmmq.consumer.ReportMessageConsumer;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runner do papel <b>report-consumer</b>: aciona repetidamente o
 * {@link ReportMessageConsumer#receiveOneReport} EXISTENTE para drenar a fila de relatorios (COA/COD),
 * classificar e reconciliar contra o {@code CorrelationStore} compartilhado (JDBC) — fechando o ciclo
 * de correlacao cluster-wide (AC2).
 *
 * <p><b>Competing consumers + store compartilhado:</b> com N replicas deste runner, COA e COD de uma
 * mesma mensagem podem ser processados por pods diferentes. Como todos compartilham o
 * {@code JdbcCorrelationStore} (a mesma tabela {@code pending_message}), as marcacoes idempotentes
 * convergem para uma unica linha — e a confirmacao e contabilizada exatamente uma vez por flag.
 * (Ver a limitacao de ordenacao documentada em {@code JdbcCorrelationStore} e no README do deploy.)</p>
 *
 * <p>Ativo apenas quando {@code harness.role=report-consumer}.</p>
 */
@Singleton
@Requires(property = "harness.role", value = "report-consumer")
public class ReportConsumerHarnessRunner extends AbstractHarnessRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ReportConsumerHarnessRunner.class);

    private final ReportMessageConsumer reportConsumer;
    private final HarnessProperties harness;

    public ReportConsumerHarnessRunner(ReportMessageConsumer reportConsumer,
                                       HarnessProperties harness) {
        this.reportConsumer = reportConsumer;
        this.harness = harness;
    }

    @Override
    protected String roleName() {
        return "report-consumer";
    }

    @Override
    protected Logger log() {
        return LOG;
    }

    @Override
    protected void runOnce() {
        // receiveOneReport bloqueia ate o timeout; null = nenhum relatorio no periodo -> tenta de novo.
        reportConsumer.receiveOneReport(harness.getReceiveTimeoutMillis());
    }
}
