package com.example.ibmmq.logging;

import com.example.ibmmq.config.MqProperties;
import com.example.ibmmq.consumer.BusinessMessageConsumer;
import com.example.ibmmq.consumer.ReportMessageConsumer;
import com.example.ibmmq.correlation.InMemoryCorrelationStore;
import com.example.ibmmq.messaging.InMemoryBroker;
import com.example.ibmmq.messaging.InMemoryReceivePort;
import com.example.ibmmq.messaging.InMemorySendPort;
import com.example.ibmmq.messaging.OutboundMessage;
import com.example.ibmmq.messaging.ReceivePort;
import com.example.ibmmq.messaging.ReportEnvelope;
import com.example.ibmmq.messaging.SendPort;
import com.example.ibmmq.model.PendingMessage;
import com.example.ibmmq.model.ReportType;
import com.example.ibmmq.producer.BusinessMessageProducer;
import com.example.ibmmq.report.ReportFeedbackRouter;
import com.ibm.mq.constants.MQConstants;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Testes unitarios (sem broker) do logging narrado e do MDC ao longo do ciclo de vida COA/COD,
 * exercitando os tres entry points de PRODUCAO ligados ao seam (ADR-0008) — {@link SendPort}/
 * {@link ReceivePort} via os fakes in-memory (ou um mock onde isso simplifica a assercao).
 *
 * <p>Valida os pilares do slice:
 * <ol>
 *   <li>cada etapa do ciclo (PRODUCE, CONSUME+COMMIT, COA, COD, CLASSIFY/CORRELATE/RECONCILE) emite
 *       uma linha INFO legivel com uma tag {@code [stage=...]} visivel;</li>
 *   <li>{@code messageId} e {@code correlationId} estao vinculados via MDC e aparecem em CADA linha
 *       <b>do lado de PRODUCE e dos relatorios</b> — de forma que um unico id rastreia o fluxo;</li>
 *   <li>o MDC e limpo apos cada metodo (verificado lendo o snapshot por-evento, nunca o thread-local
 *       apos o retorno).</li>
 * </ol>
 *
 * <p><b>Nota de observabilidade (ADR-0008, intencional):</b> o seam de recebimento expoe apenas o CORPO
 * da mensagem consumida (assinatura {@code handle(String body)}), NAO o messageId consumido. Por isso
 * as linhas {@code [stage=CONSUME]}/{@code [stage=COMMIT]} nao podem mais vincular messageId/correlationId
 * no MDC — uma consequencia aceita do seam travado. As assercoes de MDC para essas duas linhas foram
 * removidas; as tags de etapa e o corpo permanecem assertados.</p>
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
        @DisplayName("send vincula messageId=correlationId=messageId-do-seam e narra a etapa de producao")
        void produceLogsStageAndBindsMdc() {
            // O messageId ainda volta do seam para o produtor (SendPort.send retorna o id), entao o MDC
            // de PRODUCE permanece valido. Um mock de SendPort com o id fixo mantem as assercoes verbatim.
            SendPort sendPort = mock(SendPort.class);
            when(sendPort.send(any(OutboundMessage.class))).thenReturn(MSG_ID);

            InMemoryCorrelationStore store = new InMemoryCorrelationStore();
            BusinessMessageProducer beanProducer =
                    new BusinessMessageProducer(sendPort, new MqProperties(), store);

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
        @DisplayName("receiveOne narra consumo e commit (seam) e retorna o corpo; sem MDC de id nessas linhas")
        void consumeLogsConsumeAndCommitStages() {
            // Pre-semeia uma mensagem de negocio no broker in-memory via o send port pareado, depois
            // consome via o receive port real. Observabilidade (ADR-0008): o seam expoe apenas o corpo,
            // nao o messageId consumido — por isso CONSUME/COMMIT NAO vinculam id no MDC.
            InMemoryBroker broker = new InMemoryBroker();
            SendPort sendPort = new InMemorySendPort(broker);
            ReceivePort receivePort = new InMemoryReceivePort(broker);

            MqProperties props = new MqProperties();
            sendPort.send(OutboundMessage.persistentWithCoaCod(
                    "pedido-1", "{\"k\":\"v\"}", props.getBusinessQueue(), props.getReportQueue()));

            BusinessMessageConsumer beanConsumer = new BusinessMessageConsumer(receivePort, props);

            ListAppender<ILoggingEvent> appender = attachCapturingAppender(BusinessMessageConsumer.class);

            String body = beanConsumer.receiveOne(1_000L);

            // O retorno nao-nulo + a linha [stage=COMMIT] sao a evidencia de que o commit (na UoW do
            // seam) ocorreu — o COD so e liberado apos esse commit.
            assertThat(body).isEqualTo("{\"k\":\"v\"}");

            ILoggingEvent consumeEvent = eventWithStage(appender, "[stage=CONSUME]");
            assertThat(consumeEvent).as("linha [stage=CONSUME] emitida").isNotNull();
            assertThat(consumeEvent.getFormattedMessage())
                    .contains("Mensagem de negocio consumida")
                    .contains("{\"k\":\"v\"}");

            ILoggingEvent commitEvent = eventWithStage(appender, "[stage=COMMIT]");
            assertThat(commitEvent).as("linha [stage=COMMIT] emitida").isNotNull();
            assertThat(commitEvent.getFormattedMessage()).contains("Consumo confirmado");
        }
    }

    @Nested
    @DisplayName("RELATORIOS: COA e COD narram chegada/entrega + classify/correlate/reconcile no MDC")
    class ReportStages {

        private ReportMessageConsumer reportConsumer(InMemoryCorrelationStore store) {
            // auditRepository=null: sem datasource neste teste de log; a persistencia de auditoria fica inerte.
            // A ReceivePort nao e exercitada por handleReport(envelope), entao um mock basta.
            return new ReportMessageConsumer(
                    mock(ReceivePort.class), new MqProperties(), store, new ReportFeedbackRouter(), null);
        }

        @Test
        @DisplayName("Relatorio COA narra [stage=CLASSIFY], [stage=CORRELATE] e [stage=COA] (arrival)")
        void coaReportLogsArrivalStages() {
            InMemoryCorrelationStore store = new InMemoryCorrelationStore();
            store.register(PendingMessage.newlySent(MSG_ID, "pedido-2", "{}"));

            // Default MQRO_COPY_MSG_ID_TO_CORREL_ID: o relatorio chega com correlationId == MessageId original.
            ReportEnvelope coaReport = ReportEnvelope.synthetic(
                    MQConstants.MQFB_COA, MSG_ID, "", ReportType.COA);

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
        void codReportLogsDeliveryAndReconcileStages() {
            InMemoryCorrelationStore store = new InMemoryCorrelationStore();
            store.register(PendingMessage.newlySent(MSG_ID, "pedido-3", "{}"));
            store.markCoaReceived(MSG_ID); // COA ja recebido — COD completa a entrega.

            ReportEnvelope codReport = ReportEnvelope.synthetic(
                    MQConstants.MQFB_COD, MSG_ID, "", ReportType.COD);

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
        void orphanCoaReportLogsOrphanStageAndIncrementsCounter() {
            // Sem register: o COA chega para um CorrelationId que este consumer nunca registrou
            // (orphan-on-redelivery, ou um relatorio que este processo nunca registrou).
            InMemoryCorrelationStore store = new InMemoryCorrelationStore();

            ReportEnvelope coaReport = ReportEnvelope.synthetic(
                    MQConstants.MQFB_COA, MSG_ID, "", ReportType.COA);

            ListAppender<ILoggingEvent> appender = attachCapturingAppender(ReportMessageConsumer.class);
            ReportMessageConsumer consumer = reportConsumer(store);

            // Issue #26: um relatorio orfao ainda gera um DeliveryEvent (comportamento preservado para
            // conhecidos E orfaos).
            var event = consumer.handleReport(coaReport);
            assertThat(event).as("relatorio orfao ainda gera DeliveryEvent").isNotNull();
            assertThat(event.reportType()).isEqualTo(ReportType.COA);

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
        void mdcClearedAfterHandleReport() {
            InMemoryCorrelationStore store = new InMemoryCorrelationStore();
            store.register(PendingMessage.newlySent(MSG_ID, "pedido-4", "{}"));

            ReportEnvelope coaReport = ReportEnvelope.synthetic(
                    MQConstants.MQFB_COA, MSG_ID, "", ReportType.COA);

            reportConsumer(store).handleReport(coaReport);

            // Lido APOS o retorno: o finally do codigo de producao removeu as chaves do thread-local.
            assertThat(org.slf4j.MDC.get("messageId")).isNull();
            assertThat(org.slf4j.MDC.get("correlationId")).isNull();
        }
    }
}
