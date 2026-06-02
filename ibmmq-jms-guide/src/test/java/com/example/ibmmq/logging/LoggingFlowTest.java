package com.example.ibmmq.logging;

import com.example.ibmmq.config.MqProperties;
import com.example.ibmmq.consumer.BusinessMessageConsumer;
import com.example.ibmmq.consumer.ReportMessageConsumer;
import com.example.ibmmq.correlation.InMemoryCorrelationStore;
import com.example.ibmmq.model.PendingMessage;
import com.example.ibmmq.producer.BusinessMessageProducer;
import com.example.ibmmq.report.ReportFeedbackRouter;
import com.ibm.msg.client.wmq.WMQConstants;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.jms.ConnectionFactory;
import javax.jms.JMSConsumer;
import javax.jms.JMSContext;
import javax.jms.JMSProducer;
import javax.jms.Message;
import javax.jms.Queue;
import javax.jms.TextMessage;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes unitarios (sem broker) do logging narrado e do MDC ao longo do ciclo de vida COA/COD.
 *
 * <p>Valida os tres pilares do slice:
 * <ol>
 *   <li>cada etapa do ciclo (PRODUCE, CONSUME+COMMIT, COA, COD, CLASSIFY/CORRELATE/RECONCILE) emite
 *       uma linha INFO legivel com uma tag {@code [stage=...]} visivel;</li>
 *   <li>{@code messageId} e {@code correlationId} estao vinculados via MDC e aparecem em CADA linha
 *       relacionada — de forma que um unico id rastreia o fluxo inteiro;</li>
 *   <li>o MDC e limpo apos cada metodo (verificado lendo o snapshot por-evento, nunca o thread-local
 *       apos o retorno).</li>
 * </ol>
 *
 * <p><b>Captura de log:</b> usamos um {@link ListAppender} que FORCA a captura preguicosa do MDC
 * dentro de {@code append()} (chamando {@code getMDCPropertyMap()} enquanto o MDC ainda esta
 * vinculado, antes do {@code finally} do codigo de producao limpa-lo). Sem isso, a leitura tardia do
 * mapa MDC no teste devolveria {@code {}} porque o {@code LoggingEvent} do logback so materializa o
 * MDC sob demanda.</p>
 */
class LoggingFlowTest {

    private static final String MSG_ID = "ID:414d51204d513120202020202020202000000001";

    private final List<Logger> attached = new ArrayList<>();

    /**
     * Attacha um {@link ListAppender} (iniciado, com captura de MDC forcada) ao logger da classe dada
     * e o registra para detach em {@link #detachAll()}.
     */
    private ListAppender<ILoggingEvent> attachCapturingAppender(Class<?> loggingClass) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent eventObject) {
                // Forca a captura preguicosa do MDC enquanto ele ainda esta vinculado a thread
                // (este append() roda sincronamente dentro de LOG.info, ANTES do finally que limpa).
                eventObject.getMDCPropertyMap();
                super.append(eventObject);
            }
        };
        // Obrigatorio: AppenderBase.doAppend() ignora silenciosamente eventos se !started.
        appender.start();

        Logger logger = (Logger) LoggerFactory.getLogger(loggingClass);
        // Fixa o nivel em INFO no proprio logger: torna o teste auto-contido e imune a qualquer
        // logback-test.xml (que o logback resolve antes do logback.xml de producao) que pudesse
        // deixar estes loggers no WARN da raiz e filtrar as linhas INFO antes do appender.
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        attached.add(logger);
        return appender;
    }

    @AfterEach
    void detachAll() {
        // Remove os appenders para nao vazar captura para outros testes no mesmo JVM do surefire.
        for (Logger logger : attached) {
            logger.detachAndStopAllAppenders();
        }
        attached.clear();
    }

    /** Localiza o primeiro evento cuja mensagem formatada contem a tag de etapa dada. */
    private static ILoggingEvent eventWithStage(ListAppender<ILoggingEvent> appender, String stageTag) {
        return appender.list.stream()
                .filter(e -> e.getFormattedMessage().contains(stageTag))
                .findFirst()
                .orElse(null);
    }

    @Nested
    @DisplayName("PRODUCE: o envio emite [stage=PRODUCE] com messageId e correlationId no MDC")
    class ProduceStage {

        @Test
        @DisplayName("send vincula messageId=correlationId=JMSMessageID e narra a etapa de producao")
        void produceLogsStageAndBindsMdc() throws Exception {
            ConnectionFactory cf = mock(ConnectionFactory.class);
            JMSContext ctx = mock(JMSContext.class);
            TextMessage message = mock(TextMessage.class);
            JMSProducer producer = mock(JMSProducer.class);
            Queue queue = mock(Queue.class);

            when(cf.createContext(JMSContext.AUTO_ACKNOWLEDGE)).thenReturn(ctx);
            when(ctx.createQueue(anyString())).thenReturn(queue);
            when(ctx.createTextMessage(anyString())).thenReturn(message);
            when(ctx.createProducer()).thenReturn(producer);
            when(message.getJMSMessageID()).thenReturn(MSG_ID);

            InMemoryCorrelationStore store = new InMemoryCorrelationStore();
            BusinessMessageProducer beanProducer =
                    new BusinessMessageProducer(cf, new MqProperties(), store);

            ListAppender<ILoggingEvent> appender = attachCapturingAppender(BusinessMessageProducer.class);

            String returnedId = beanProducer.send("pedido-1", "{\"k\":\"v\"}");

            assertThat(returnedId).isEqualTo(MSG_ID);

            ILoggingEvent produceEvent = eventWithStage(appender, "[stage=PRODUCE]");
            assertThat(produceEvent).as("linha [stage=PRODUCE] emitida").isNotNull();
            assertThat(produceEvent.getFormattedMessage())
                    .contains("Mensagem de negocio enviada")
                    .contains(MSG_ID);
            // MDC: o id de negocio futuro do relatorio (correlationId) e o proprio messageId.
            assertThat(produceEvent.getMDCPropertyMap()).containsEntry("messageId", MSG_ID);
            assertThat(produceEvent.getMDCPropertyMap()).containsEntry("correlationId", MSG_ID);
        }
    }

    @Nested
    @DisplayName("CONSUME+COMMIT: o consumo destrutivo narra [stage=CONSUME] e [stage=COMMIT]")
    class ConsumeStage {

        @Test
        @DisplayName("receiveOne narra consumo e commit com messageId/correlationId no MDC e comita")
        void consumeLogsConsumeAndCommitStages() throws Exception {
            ConnectionFactory cf = mock(ConnectionFactory.class);
            JMSContext ctx = mock(JMSContext.class);
            JMSConsumer consumer = mock(JMSConsumer.class);
            TextMessage message = mock(TextMessage.class);
            Queue queue = mock(Queue.class);

            when(cf.createContext(JMSContext.SESSION_TRANSACTED)).thenReturn(ctx);
            when(ctx.createQueue(anyString())).thenReturn(queue);
            when(ctx.createConsumer(queue)).thenReturn(consumer);
            when(consumer.receive(anyLong())).thenReturn(message);
            when(message.getJMSMessageID()).thenReturn(MSG_ID);
            when(message.getText()).thenReturn("{\"k\":\"v\"}");

            BusinessMessageConsumer beanConsumer =
                    new BusinessMessageConsumer(cf, new MqProperties());

            ListAppender<ILoggingEvent> appender = attachCapturingAppender(BusinessMessageConsumer.class);

            String body = beanConsumer.receiveOne(1_000L);

            assertThat(body).isEqualTo("{\"k\":\"v\"}");
            // O COD so e liberado apos o commit — verificamos que o consumo foi de fato confirmado.
            verify(ctx).commit();

            ILoggingEvent consumeEvent = eventWithStage(appender, "[stage=CONSUME]");
            assertThat(consumeEvent).as("linha [stage=CONSUME] emitida").isNotNull();
            assertThat(consumeEvent.getFormattedMessage())
                    .contains("Mensagem de negocio consumida")
                    .contains(MSG_ID);
            assertThat(consumeEvent.getMDCPropertyMap()).containsEntry("messageId", MSG_ID);
            assertThat(consumeEvent.getMDCPropertyMap()).containsEntry("correlationId", MSG_ID);

            ILoggingEvent commitEvent = eventWithStage(appender, "[stage=COMMIT]");
            assertThat(commitEvent).as("linha [stage=COMMIT] emitida").isNotNull();
            assertThat(commitEvent.getFormattedMessage()).contains("Consumo confirmado");
            assertThat(commitEvent.getMDCPropertyMap()).containsEntry("messageId", MSG_ID);
            assertThat(commitEvent.getMDCPropertyMap()).containsEntry("correlationId", MSG_ID);
        }
    }

    @Nested
    @DisplayName("RELATORIOS: COA e COD narram chegada/entrega + classify/correlate/reconcile no MDC")
    class ReportStages {

        private ReportMessageConsumer reportConsumer(InMemoryCorrelationStore store) {
            // auditRepository=null: sem datasource neste teste de log; a persistencia de auditoria fica inerte.
            return new ReportMessageConsumer(
                    mock(ConnectionFactory.class), new MqProperties(), store, new ReportFeedbackRouter(), null);
        }

        @Test
        @DisplayName("Relatorio COA narra [stage=CLASSIFY], [stage=CORRELATE] e [stage=COA] (arrival)")
        void coaReportLogsArrivalStages() throws Exception {
            InMemoryCorrelationStore store = new InMemoryCorrelationStore();
            store.register(PendingMessage.newlySent(MSG_ID, "pedido-2", "{}"));

            Message coaReport = mock(Message.class);
            when(coaReport.getJMSCorrelationID()).thenReturn(MSG_ID);
            when(coaReport.getIntProperty(WMQConstants.JMS_IBM_FEEDBACK)).thenReturn(259); // MQFB_COA

            ListAppender<ILoggingEvent> appender = attachCapturingAppender(ReportMessageConsumer.class);

            reportConsumer(store).handleReport(coaReport);

            ILoggingEvent classify = eventWithStage(appender, "[stage=CLASSIFY]");
            ILoggingEvent correlate = eventWithStage(appender, "[stage=CORRELATE]");
            ILoggingEvent coa = eventWithStage(appender, "[stage=COA]");

            assertThat(classify).as("linha [stage=CLASSIFY] emitida").isNotNull();
            assertThat(correlate).as("linha [stage=CORRELATE] emitida").isNotNull();
            assertThat(coa).as("linha [stage=COA] emitida").isNotNull();

            // Toda linha relacionada carrega os mesmos ids: correlId == messageId original (default
            // MQRO_COPY_MSG_ID_TO_CORREL_ID), entao um unico id grepa o fluxo inteiro.
            for (ILoggingEvent e : List.of(classify, correlate, coa)) {
                assertThat(e.getMDCPropertyMap())
                        .as("MDC em %s", e.getFormattedMessage())
                        .containsEntry("messageId", MSG_ID)
                        .containsEntry("correlationId", MSG_ID);
            }
            assertThat(coa.getFormattedMessage()).contains("chegada");
        }

        @Test
        @DisplayName("Relatorio COD apos COA narra [stage=COD] (delivery) e [stage=RECONCILE]")
        void codReportLogsDeliveryAndReconcileStages() throws Exception {
            InMemoryCorrelationStore store = new InMemoryCorrelationStore();
            store.register(PendingMessage.newlySent(MSG_ID, "pedido-3", "{}"));
            store.markCoaReceived(MSG_ID); // COA ja recebido — COD completa a entrega.

            Message codReport = mock(Message.class);
            when(codReport.getJMSCorrelationID()).thenReturn(MSG_ID);
            when(codReport.getIntProperty(WMQConstants.JMS_IBM_FEEDBACK)).thenReturn(260); // MQFB_COD

            ListAppender<ILoggingEvent> appender = attachCapturingAppender(ReportMessageConsumer.class);

            reportConsumer(store).handleReport(codReport);

            ILoggingEvent cod = eventWithStage(appender, "[stage=COD]");
            ILoggingEvent reconcile = eventWithStage(appender, "[stage=RECONCILE]");

            assertThat(cod).as("linha [stage=COD] emitida").isNotNull();
            assertThat(reconcile).as("linha [stage=RECONCILE] (entrega completa) emitida").isNotNull();

            for (ILoggingEvent e : List.of(cod, reconcile)) {
                assertThat(e.getMDCPropertyMap())
                        .as("MDC em %s", e.getFormattedMessage())
                        .containsEntry("messageId", MSG_ID)
                        .containsEntry("correlationId", MSG_ID);
            }
            assertThat(cod.getFormattedMessage()).contains("entrega");
            // COA+COD confirmados -> pendencia reconciliada e removida.
            assertThat(store.pendingCount()).isZero();
        }

        @Test
        @DisplayName("Relatorio COA orfao (sem registro previo) narra [stage=ORPHAN] WARN, incrementa o contador e ainda gera evento")
        void orphanCoaReportLogsOrphanStageAndIncrementsCounter() throws Exception {
            // Sem register: o COA chega para um CorrelationId que este consumer nunca registrou
            // (orphan-on-redelivery, ou um relatorio que este processo nunca registrou).
            InMemoryCorrelationStore store = new InMemoryCorrelationStore();

            Message coaReport = mock(Message.class);
            when(coaReport.getJMSCorrelationID()).thenReturn(MSG_ID);
            when(coaReport.getIntProperty(WMQConstants.JMS_IBM_FEEDBACK)).thenReturn(259); // MQFB_COA

            ListAppender<ILoggingEvent> appender = attachCapturingAppender(ReportMessageConsumer.class);
            ReportMessageConsumer consumer = reportConsumer(store);

            // Issue #26: um relatorio orfao ainda gera um DeliveryEvent (comportamento preservado para
            // conhecidos E orfaos).
            var event = consumer.handleReport(coaReport);
            assertThat(event).as("relatorio orfao ainda gera DeliveryEvent").isNotNull();
            assertThat(event.reportType()).isEqualTo(com.example.ibmmq.model.ReportType.COA);

            // O outcome ORPHAN e superficializado: WARN [stage=ORPHAN] + contador de taxa de orfaos.
            ILoggingEvent orphan = eventWithStage(appender, "[stage=ORPHAN]");
            assertThat(orphan).as("linha [stage=ORPHAN] (WARN) emitida para um COA sem registro previo").isNotNull();
            assertThat(orphan.getLevel()).isEqualTo(Level.WARN);
            assertThat(orphan.getMDCPropertyMap())
                    .containsEntry("messageId", MSG_ID)
                    .containsEntry("correlationId", MSG_ID);

            assertThat(consumer.getOrphanReportCount())
                    .as("o contador de relatorios orfaos e incrementado exatamente uma vez")
                    .isEqualTo(1L);
        }

        @Test
        @DisplayName("MDC e limpo apos handleReport (sem vazamento entre relatorios)")
        void mdcClearedAfterHandleReport() throws Exception {
            InMemoryCorrelationStore store = new InMemoryCorrelationStore();
            store.register(PendingMessage.newlySent(MSG_ID, "pedido-4", "{}"));

            Message coaReport = mock(Message.class);
            when(coaReport.getJMSCorrelationID()).thenReturn(MSG_ID);
            when(coaReport.getIntProperty(WMQConstants.JMS_IBM_FEEDBACK)).thenReturn(259);

            reportConsumer(store).handleReport(coaReport);

            // Lido APOS o retorno: o finally do codigo de producao removeu as chaves do thread-local.
            assertThat(org.slf4j.MDC.get("messageId")).isNull();
            assertThat(org.slf4j.MDC.get("correlationId")).isNull();
        }
    }
}
