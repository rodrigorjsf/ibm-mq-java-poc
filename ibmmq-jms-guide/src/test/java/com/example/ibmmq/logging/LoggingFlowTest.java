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
 * Unit tests (no broker) of narrated logging and MDC across the COA/COD lifecycle, exercising the
 * three PRODUCTION entry points wired to the seam (ADR-0008) — {@link SendPort}/{@link ReceivePort}
 * via the in-memory fakes (or a mock where that simplifies the assertion).
 *
 * <p>Validates the slice's pillars:
 * <ol>
 *   <li>each lifecycle stage (PRODUCE, CONSUME+COMMIT, COA, COD, CLASSIFY/CORRELATE/RECONCILE) emits
 *       a readable INFO line with a visible {@code [stage=...]} tag;</li>
 *   <li>{@code messageId} and {@code correlationId} are bound via MDC and appear on EACH line
 *       <b>on the PRODUCE side and the reports side</b> — so a single id traces the entire flow;</li>
 *   <li>the MDC is cleared after each method (verified by reading the per-event snapshot, never the
 *       thread-local after the return).</li>
 * </ol>
 *
 * <p><b>Observability note (ADR-0008, intentional):</b> the receive seam exposes only the BODY of the
 * consumed message (signature {@code handle(String body)}), NOT the consumed messageId. Therefore the
 * {@code [stage=CONSUME]}/{@code [stage=COMMIT]} lines can no longer bind messageId/correlationId in
 * the MDC — an accepted consequence of the locked seam. The MDC assertions for those two lines were
 * removed; the stage tags and body remain asserted.</p>
 *
 * <p><b>Log capture:</b> we use a {@link ListAppender} that FORCES eager MDC capture inside
 * {@code append()} (calling {@code getMDCPropertyMap()} while the MDC is still bound to the thread,
 * before the production code's {@code finally} clears it). Without this, a late MDC read in the test
 * would return {@code {}} because the logback {@code LoggingEvent} materialises the MDC lazily.</p>
 */
class LoggingFlowTest {

    private static final String MSG_ID = "ID:414d51204d513120202020202020202000000001";

    private final List<Logger> attached = new ArrayList<>();

    /**
     * Attaches a {@link ListAppender} (started, with forced MDC capture) to the logger of the given
     * class and registers it for detach in {@link #detachAll()}.
     */
    private ListAppender<ILoggingEvent> attachCapturingAppender(Class<?> loggingClass) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>() {
            @Override
            protected void append(ILoggingEvent eventObject) {
                // Force eager MDC capture while it is still bound to the thread
                // (this append() runs synchronously inside LOG.info, BEFORE the finally that clears it).
                eventObject.getMDCPropertyMap();
                super.append(eventObject);
            }
        };
        // Required: AppenderBase.doAppend() silently ignores events if !started.
        appender.start();

        Logger logger = (Logger) LoggerFactory.getLogger(loggingClass);
        // Pin the level to INFO on the logger itself: makes the test self-contained and immune to any
        // logback-test.xml (which logback resolves before the production logback.xml) that might
        // leave these loggers at the root WARN level and filter INFO lines before the appender.
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        attached.add(logger);
        return appender;
    }

    @AfterEach
    void detachAll() {
        // Remove appenders to avoid leaking capture to other tests in the same surefire JVM.
        for (Logger logger : attached) {
            logger.detachAndStopAllAppenders();
        }
        attached.clear();
    }

    /** Finds the first event whose formatted message contains the given stage tag. */
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
            // The messageId still flows back from the seam to the producer (SendPort.send returns the id),
            // so the PRODUCE MDC binding remains valid. A SendPort mock with a fixed id keeps the assertions verbatim.
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
                    .contains("Business message sent")
                    .contains(MSG_ID);
            // MDC: the future report correlation id (correlationId) is the messageId itself.
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
            // Pre-seeds a business message into the in-memory broker via the paired send port, then
            // consumes it via the real receive port. Observability (ADR-0008): the seam exposes only the
            // body, not the consumed messageId — so CONSUME/COMMIT do NOT bind id in the MDC.
            InMemoryBroker broker = new InMemoryBroker();
            SendPort sendPort = new InMemorySendPort(broker);
            ReceivePort receivePort = new InMemoryReceivePort(broker);

            MqProperties props = new MqProperties();
            sendPort.send(OutboundMessage.persistentWithCoaCod(
                    "pedido-1", "{\"k\":\"v\"}", props.getBusinessQueue(), props.getReportQueue()));

            BusinessMessageConsumer beanConsumer = new BusinessMessageConsumer(receivePort, props);

            ListAppender<ILoggingEvent> appender = attachCapturingAppender(BusinessMessageConsumer.class);

            String body = beanConsumer.receiveOne(1_000L);

            // The non-null return + the [stage=COMMIT] line are evidence that the commit (in the seam's
            // UoW) occurred — the COD is only released after that commit.
            assertThat(body).isEqualTo("{\"k\":\"v\"}");

            ILoggingEvent consumeEvent = eventWithStage(appender, "[stage=CONSUME]");
            assertThat(consumeEvent).as("linha [stage=CONSUME] emitida").isNotNull();
            assertThat(consumeEvent.getFormattedMessage())
                    .contains("Business message consumed")
                    .contains("{\"k\":\"v\"}");

            ILoggingEvent commitEvent = eventWithStage(appender, "[stage=COMMIT]");
            assertThat(commitEvent).as("linha [stage=COMMIT] emitida").isNotNull();
            assertThat(commitEvent.getFormattedMessage()).contains("Consumption committed");
        }
    }

    @Nested
    @DisplayName("RELATORIOS: COA e COD narram chegada/entrega + classify/correlate/reconcile no MDC")
    class ReportStages {

        private ReportMessageConsumer reportConsumer(InMemoryCorrelationStore store) {
            // auditRepository=null + auditSchema=null: no datasource in this log test; audit persistence
            // stays inert and the schema guard (ADR-0010) never fires. The ReceivePort is not exercised by
            // handleReport(envelope), so a mock suffices.
            return new ReportMessageConsumer(
                    mock(ReceivePort.class), new MqProperties(), store, new ReportFeedbackRouter(), null, null);
        }

        @Test
        @DisplayName("Relatorio COA narra [stage=CLASSIFY], [stage=CORRELATE] e [stage=COA] (arrival)")
        void coaReportLogsArrivalStages() {
            InMemoryCorrelationStore store = new InMemoryCorrelationStore();
            store.register(PendingMessage.newlySent(MSG_ID, "pedido-2", "{}"));

            // Default MQRO_COPY_MSG_ID_TO_CORREL_ID: the report arrives with correlationId == original MessageId.
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

            // Every related line carries the same ids: correlId == original messageId (default
            // MQRO_COPY_MSG_ID_TO_CORREL_ID), so a single id greps the entire flow.
            for (ILoggingEvent e : List.of(classify, correlate, coa)) {
                assertThat(e.getMDCPropertyMap())
                        .as("MDC em %s", e.getFormattedMessage())
                        .containsEntry("messageId", MSG_ID)
                        .containsEntry("correlationId", MSG_ID);
            }
            assertThat(coa.getFormattedMessage()).contains("Arrival confirmation");
        }

        @Test
        @DisplayName("Relatorio COD apos COA narra [stage=COD] (delivery) e [stage=RECONCILE]")
        void codReportLogsDeliveryAndReconcileStages() {
            InMemoryCorrelationStore store = new InMemoryCorrelationStore();
            store.register(PendingMessage.newlySent(MSG_ID, "pedido-3", "{}"));
            store.markCoaReceived(MSG_ID); // COA already received — COD completes the delivery.

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
            assertThat(cod.getFormattedMessage()).contains("Delivery confirmation");
            // COA+COD confirmed -> pending entry reconciled and removed.
            assertThat(store.pendingCount()).isZero();
        }

        @Test
        @DisplayName("Relatorio COA orfao (sem registro previo) narra [stage=ORPHAN] WARN, incrementa o contador e ainda gera evento")
        void orphanCoaReportLogsOrphanStageAndIncrementsCounter() {
            // No register: the COA arrives for a CorrelationId this consumer never registered
            // (orphan-on-redelivery, or a report this process never registered).
            InMemoryCorrelationStore store = new InMemoryCorrelationStore();

            ReportEnvelope coaReport = ReportEnvelope.synthetic(
                    MQConstants.MQFB_COA, MSG_ID, "", ReportType.COA);

            ListAppender<ILoggingEvent> appender = attachCapturingAppender(ReportMessageConsumer.class);
            ReportMessageConsumer consumer = reportConsumer(store);

            // Issue #26: an orphan report still generates a DeliveryEvent (behaviour preserved for
            // both known and orphan reports).
            var event = consumer.handleReport(coaReport);
            assertThat(event).as("relatorio orfao ainda gera DeliveryEvent").isNotNull();
            assertThat(event.reportType()).isEqualTo(ReportType.COA);

            // The ORPHAN outcome is surfaced: WARN [stage=ORPHAN] + orphan-rate counter.
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

            // Read AFTER return: the production code's finally already removed the keys from the thread-local.
            assertThat(org.slf4j.MDC.get("messageId")).isNull();
            assertThat(org.slf4j.MDC.get("correlationId")).isNull();
        }
    }
}
