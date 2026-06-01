package com.example.ibmmq.demo;

import com.example.ibmmq.consumer.BusinessMessageConsumer;
import com.example.ibmmq.consumer.ReportMessageConsumer;
import com.example.ibmmq.model.DeliveryEvent;
import com.example.ibmmq.model.ReportType;
import com.example.ibmmq.producer.BusinessMessageProducer;
import com.ibm.mq.constants.MQConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Teste unitario (surefire, SEM broker, SEM Micronaut, SEM Docker) da ORQUESTRACAO da demo COA/COD
 * (AC#1 + AC#2). Constroi o {@link CoaCodDemoRunner} diretamente com os beans JMS mockados e inspeciona
 * o veredito de {@code runDemo()}.
 *
 * <p><b>Por que construcao direta (e nao via {@code ApplicationContext})?</b> O runner e um
 * {@code ApplicationEventListener<StartupEvent>}: subir o contexto dispararia o listener no startup,
 * consumindo os stubs ANTES do corpo do teste. Para validar o contrato de {@code runDemo()} de forma
 * deterministica — sem colisao de stubs nem o spin ate o deadline — construimos o runner manualmente
 * e o invocamos UMA vez, exatamente como {@code LoggingFlowTest} faz com os beans de producao. O
 * gating via {@code @Requires} (AC#3) e coberto separadamente em {@link CoaCodDemoRunnerGatingTest} e
 * {@link CoaCodDemoRunnerEnabledGatingTest}.</p>
 */
@DisplayName("Demo COA/COD — orquestracao (produce -> consume -> COA/COD) e validacao 259/260")
class CoaCodDemoRunnerTest {

    private static final String MSG_ID = "ID:414d51204d513120202020202020202000000001";
    private static final String DEMO_PAYLOAD = "{\"demo\":\"coa-cod\",\"pedido\":42}";

    @Test
    @DisplayName("runDemo produz, consome e valida COA(259)+COD(260) com correlId == messageId (PASS)")
    void runDemoValidatesCoaAndCodWithCorrelationMatchingMessageId() {
        BusinessMessageProducer producer = mock(BusinessMessageProducer.class);
        BusinessMessageConsumer consumer = mock(BusinessMessageConsumer.class);
        ReportMessageConsumer reportConsumer = mock(ReportMessageConsumer.class);

        when(producer.send(anyString(), anyString())).thenReturn(MSG_ID);
        when(consumer.receiveOne(anyLong())).thenReturn(DEMO_PAYLOAD);
        // COA, depois COD, depois null: AMBOS com correlationId == MSG_ID (default
        // MQRO_COPY_MSG_ID_TO_CORREL_ID). Apos ver os dois, o loop de coleta sai antes do deadline.
        DeliveryEvent coa = new DeliveryEvent(
                ReportType.COA, MQConstants.MQFB_COA, MSG_ID, MSG_ID, Instant.now());
        DeliveryEvent cod = new DeliveryEvent(
                ReportType.COD, MQConstants.MQFB_COD, MSG_ID, MSG_ID, Instant.now());
        when(reportConsumer.receiveOneReport(anyLong())).thenReturn(coa, cod, (DeliveryEvent) null);

        CoaCodDemoRunner runner = new CoaCodDemoRunner(producer, consumer, reportConsumer);

        CoaCodDemoRunner.DemoResult result = runner.runDemo();

        // AC#1: um unico fluxo produziu, consumiu e colheu os relatorios reutilizando os beans.
        verify(producer).send(anyString(), anyString());
        verify(consumer).receiveOne(anyLong());

        // AC#2: ambos os feedbacks (259/260) vistos e correlId == messageId.
        assertThat(result.messageId()).as("messageId de producer.send").isEqualTo(MSG_ID);
        assertThat(result.coaSeen()).as("COA (feedback 259) visto").isTrue();
        assertThat(result.codSeen()).as("COD (feedback 260) visto").isTrue();
        assertThat(result.correlationOk())
                .as("todos os relatorios COA/COD com correlId == messageId").isTrue();
        assertThat(result.passed()).as("veredito agregado PASS").isTrue();
        assertThat(result.reports())
                .extracting(DeliveryEvent::feedbackCode)
                .contains(MQConstants.MQFB_COA, MQConstants.MQFB_COD);
    }

    @Test
    @DisplayName("Sem o COD (so COA chega), a validacao falha (FAIL) sem lancar excecao")
    void runDemoFailsWhenCodMissing() {
        BusinessMessageProducer producer = mock(BusinessMessageProducer.class);
        BusinessMessageConsumer consumer = mock(BusinessMessageConsumer.class);
        ReportMessageConsumer reportConsumer = mock(ReportMessageConsumer.class);

        when(producer.send(anyString(), anyString())).thenReturn(MSG_ID);
        when(consumer.receiveOne(anyLong())).thenReturn(DEMO_PAYLOAD);
        // Apenas um COA e depois null: o loop nunca ve o COD e roda ate o deadline. Para manter o
        // teste rapido, o runner usa um deadline curto neste cenario via construtor de teste.
        DeliveryEvent coa = new DeliveryEvent(
                ReportType.COA, MQConstants.MQFB_COA, MSG_ID, MSG_ID, Instant.now());
        when(reportConsumer.receiveOneReport(anyLong())).thenReturn(coa, (DeliveryEvent) null);

        // Construtor de teste: (consumeTimeout=200ms, reportPollTimeout=1ms, reportDeadline=50ms).
        // O deadline curto (50ms, o 6o arg) impede o surefire de prender quando o COD nunca chega.
        CoaCodDemoRunner runner = new CoaCodDemoRunner(
                producer, consumer, reportConsumer, 200L, 1L, 50L);

        CoaCodDemoRunner.DemoResult result = runner.runDemo();

        assertThat(result.coaSeen()).as("COA visto").isTrue();
        assertThat(result.codSeen()).as("COD ausente").isFalse();
        assertThat(result.passed()).as("veredito agregado FAIL (falta COD)").isFalse();
    }
}
