package com.example.ibmmq.messaging;

import com.example.ibmmq.config.MqProperties;
import com.example.ibmmq.consumer.BusinessMessageConsumer;
import com.example.ibmmq.consumer.ReportMessageConsumer;
import com.example.ibmmq.correlation.InMemoryCorrelationStore;
import com.example.ibmmq.model.DeliveryEvent;
import com.example.ibmmq.model.ReportType;
import com.example.ibmmq.producer.BusinessMessageProducer;
import com.example.ibmmq.report.ReportFeedbackRouter;
import com.ibm.mq.constants.MQConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste unitario BROKER-FREE (sem Docker, sem IBM MQ) do ciclo COA/COD completo atraves do seam (ADR-0008,
 * AC5). Os tres entry points de PRODUCAO — {@link BusinessMessageProducer}, {@link BusinessMessageConsumer},
 * {@link ReportMessageConsumer} — sao exercitados de verdade, ligados aos adapters FAKE em memoria
 * ({@link InMemorySendPort}/{@link InMemoryReceivePort} sobre o {@link InMemoryBroker}) e a um
 * {@link InMemoryCorrelationStore} real.
 *
 * <p>Prova que o seam preserva fielmente a semantica IBM MQ que importa:
 * <ul>
 *   <li><b>produce -> consume -> receive-report -> reconcile -> remove</b> roda inteiro sem broker;</li>
 *   <li>o fake gera COA(259) e COD(260) com {@code CorrelId == MessageId} (default
 *       {@code MQRO_COPY_MSG_ID_TO_CORREL_ID});</li>
 *   <li><b>COD-released-only-after-commit:</b> o COD so aparece na fila de relatorios DEPOIS do commit do
 *       consumo de negocio;</li>
 *   <li><b>rollback-yields-no-COD:</b> se a unidade de trabalho do consumo sofrer rollback, nenhum COD e
 *       gerado.</li>
 * </ul>
 */
@DisplayName("Fluxo COA/COD broker-free atraves do seam (produce -> consume -> report -> reconcile)")
class BrokerFreeCoaCodFlowTest {

    private static final String BUSINESS_QUEUE = "DEV.QUEUE.1";
    private static final String REPORT_QUEUE = "DEV.QUEUE.2";

    private InMemoryBroker broker;
    private InMemoryCorrelationStore correlationStore;
    private BusinessMessageProducer producer;
    private BusinessMessageConsumer consumer;
    private ReportMessageConsumer reportConsumer;

    @BeforeEach
    void setUp() {
        broker = new InMemoryBroker();
        correlationStore = new InMemoryCorrelationStore();

        MqProperties props = new MqProperties();
        props.setBusinessQueue(BUSINESS_QUEUE);
        props.setReportQueue(REPORT_QUEUE);

        SendPort sendPort = new InMemorySendPort(broker);
        ReceivePort receivePort = new InMemoryReceivePort(broker);

        producer = new BusinessMessageProducer(sendPort, props, correlationStore);
        consumer = new BusinessMessageConsumer(receivePort, props);
        // auditRepository=null: sem datasource neste teste unitario, a persistencia de auditoria fica inerte.
        reportConsumer = new ReportMessageConsumer(
                receivePort, props, correlationStore, new ReportFeedbackRouter(), null);
    }

    @Test
    @DisplayName("Ciclo completo: produz, consome (commit), recebe COA+COD com CorrelId==MessageId e reconcilia")
    void fullCycleProducesCoaAndCodAndReconciles() {
        // (a) PRODUZIR — registra a pendencia e (no fake) ja enfileira o COA na chegada.
        String messageId = producer.send("pedido-1", "{\"pedido\":1}");
        assertThat(messageId).as("messageId atribuido pelo send").startsWith("ID:");
        assertThat(correlationStore.pendingCount()).as("uma pendencia registrada").isEqualTo(1);

        // (b) CONSUMIR destrutivamente — o commit (na UoW) libera o COD.
        String body = consumer.receiveOne(1_000L);
        assertThat(body).as("corpo consumido").isEqualTo("{\"pedido\":1}");

        // (c) RECEBER os relatorios atraves do report consumer real (classify/correlate/reconcile).
        DeliveryEvent first = reportConsumer.receiveOneReport(1_000L);
        DeliveryEvent second = reportConsumer.receiveOneReport(1_000L);
        DeliveryEvent none = reportConsumer.receiveOneReport(1_000L);

        assertThat(first).as("primeiro relatorio presente").isNotNull();
        assertThat(second).as("segundo relatorio presente").isNotNull();
        assertThat(none).as("apenas dois relatorios; o terceiro receive da timeout (null)").isNull();

        // COA(259) e COD(260), ambos com CorrelId == MessageId (default MQRO_COPY_MSG_ID_TO_CORREL_ID).
        assertThat(first.feedbackCode()).as("COA chega primeiro (na chegada)").isEqualTo(MQConstants.MQFB_COA);
        assertThat(first.reportType()).isEqualTo(ReportType.COA);
        assertThat(first.correlationId()).as("CorrelId do COA == MessageId").isEqualTo(messageId);

        assertThat(second.feedbackCode()).as("COD chega apos o commit").isEqualTo(MQConstants.MQFB_COD);
        assertThat(second.reportType()).isEqualTo(ReportType.COD);
        assertThat(second.correlationId()).as("CorrelId do COD == MessageId").isEqualTo(messageId);

        // (d) RECONCILIAR — COA+COD confirmados removem a pendencia (drena a zero).
        assertThat(correlationStore.pendingCount())
                .as("COA+COD reconciliados -> pendencia removida").isZero();
    }

    @Test
    @DisplayName("COD-released-only-after-commit: o COD nao existe antes do commit; surge somente apos consumir")
    void codReleasedOnlyAfterCommit() {
        producer.send("pedido-2", "{\"pedido\":2}");

        // ANTES de consumir: a fila de relatorios tem apenas o COA (gerado na chegada), NUNCA o COD.
        DeliveryEvent beforeConsume = reportConsumer.receiveOneReport(1_000L);
        assertThat(beforeConsume).as("um relatorio disponivel antes do consumo").isNotNull();
        assertThat(beforeConsume.feedbackCode())
                .as("o unico relatorio antes do commit e o COA — o COD ainda NAO existe")
                .isEqualTo(MQConstants.MQFB_COA);
        assertThat(reportConsumer.receiveOneReport(1_000L))
                .as("nenhum COD disponivel antes do commit do consumo").isNull();

        // CONSUMIR (commit) libera o COD.
        consumer.receiveOne(1_000L);

        DeliveryEvent afterCommit = reportConsumer.receiveOneReport(1_000L);
        assertThat(afterCommit).as("o COD surge somente apos o commit do consumo").isNotNull();
        assertThat(afterCommit.feedbackCode()).isEqualTo(MQConstants.MQFB_COD);
        assertThat(afterCommit.reportType()).isEqualTo(ReportType.COD);
    }

    @Test
    @DisplayName("rollback-yields-no-COD: se a UoW do consumo lanca (rollback), nenhum COD e gerado")
    void rollbackYieldsNoCod() {
        String messageId = producer.send("pedido-3", "{\"pedido\":3}");

        // Consome dentro de uma UoW cujo handler LANCA -> rollback: a mensagem volta e o COD nao e gerado.
        ReceivePort receivePort = new InMemoryReceivePort(broker);
        assertThat(catchUnitOfWorkThrow(receivePort))
                .as("a UoW que lanca propaga uma falha (rollback aplicado)").isTrue();

        // Drena os relatorios: deve haver SOMENTE o COA (chegada), nunca o COD (o consumo deu rollback).
        DeliveryEvent report = reportConsumer.receiveOneReport(1_000L);
        assertThat(report).as("o COA da chegada continua disponivel").isNotNull();
        assertThat(report.feedbackCode()).as("apenas o COA; rollback nao gera COD").isEqualTo(MQConstants.MQFB_COA);
        assertThat(report.correlationId()).isEqualTo(messageId);
        assertThat(reportConsumer.receiveOneReport(1_000L))
                .as("nenhum COD apos o rollback do consumo").isNull();

        // A mensagem voltou para a fila de negocio: um novo consumo (com commit) ainda a recupera.
        String body = consumer.receiveOne(1_000L);
        assertThat(body).as("a mensagem voltou para a fila apos o rollback").isEqualTo("{\"pedido\":3}");
    }

    /**
     * Drives {@code receiveWithinUnitOfWork} with a handler that throws, returning {@code true} when the port
     * surfaced the failure (i.e. the rollback path ran). Isolated here to keep the test body readable.
     */
    private boolean catchUnitOfWorkThrow(ReceivePort receivePort) {
        try {
            receivePort.receiveWithinUnitOfWork(BUSINESS_QUEUE, 1_000L, body -> {
                throw new IllegalStateException("processing failed — force rollback");
            });
            return false;
        } catch (RuntimeException expected) {
            return true;
        }
    }
}
