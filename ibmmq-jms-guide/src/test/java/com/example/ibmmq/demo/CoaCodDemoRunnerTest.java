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
 * Unit test (surefire, NO broker, NO Micronaut, NO Docker) of the ORCHESTRATION of the COA/COD demo
 * (AC#1 + AC#2). Builds the {@link CoaCodDemoRunner} directly with mocked JMS beans and inspects
 * the verdict from {@code runDemo()}.
 *
 * <p><b>Why direct construction (and not via {@code ApplicationContext})?</b> The runner is an
 * {@code ApplicationEventListener<StartupEvent>}: booting the context would trigger the listener on
 * startup, consuming the stubs BEFORE the test body. To validate the {@code runDemo()} contract
 * deterministically — without stub collision or spinning to the deadline — we build the runner manually
 * and invoke it ONCE, exactly as {@code LoggingFlowTest} does with the production beans. The gating
 * via {@code @Requires} (AC#3) is covered separately in {@link CoaCodDemoRunnerGatingTest} and
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
        // COA, then COD, then null: BOTH with correlationId == MSG_ID (default
        // MQRO_COPY_MSG_ID_TO_CORREL_ID). After seeing both, the collection loop exits before the deadline.
        DeliveryEvent coa = new DeliveryEvent(
                ReportType.COA, MQConstants.MQFB_COA, MSG_ID, MSG_ID, Instant.now());
        DeliveryEvent cod = new DeliveryEvent(
                ReportType.COD, MQConstants.MQFB_COD, MSG_ID, MSG_ID, Instant.now());
        when(reportConsumer.receiveOneReport(anyLong())).thenReturn(coa, cod, (DeliveryEvent) null);

        CoaCodDemoRunner runner = new CoaCodDemoRunner(producer, consumer, reportConsumer);

        CoaCodDemoRunner.DemoResult result = runner.runDemo();

        // AC#1: a single flow produced, consumed, and collected the reports reusing the beans.
        verify(producer).send(anyString(), anyString());
        verify(consumer).receiveOne(anyLong());

        // AC#2: both feedbacks (259/260) seen and correlId == messageId.
        assertThat(result.messageId()).as("messageId from producer.send").isEqualTo(MSG_ID);
        assertThat(result.coaSeen()).as("COA (feedback 259) seen").isTrue();
        assertThat(result.codSeen()).as("COD (feedback 260) seen").isTrue();
        assertThat(result.correlationOk())
                .as("all COA/COD reports with correlId == messageId").isTrue();
        assertThat(result.passed()).as("aggregated verdict PASS").isTrue();
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
        // Only one COA and then null: the loop never sees the COD and runs until the deadline. To keep
        // the test fast, the runner uses a short deadline in this scenario via the test constructor.
        DeliveryEvent coa = new DeliveryEvent(
                ReportType.COA, MQConstants.MQFB_COA, MSG_ID, MSG_ID, Instant.now());
        when(reportConsumer.receiveOneReport(anyLong())).thenReturn(coa, (DeliveryEvent) null);

        // Test constructor: (consumeTimeout=200ms, reportPollTimeout=1ms, reportDeadline=50ms).
        // The short deadline (50ms, the 6th arg) prevents surefire from hanging when COD never arrives.
        CoaCodDemoRunner runner = new CoaCodDemoRunner(
                producer, consumer, reportConsumer, 200L, 1L, 50L);

        CoaCodDemoRunner.DemoResult result = runner.runDemo();

        assertThat(result.coaSeen()).as("COA seen").isTrue();
        assertThat(result.codSeen()).as("COD absent").isFalse();
        assertThat(result.passed()).as("aggregated verdict FAIL (missing COD)").isFalse();
    }
}
