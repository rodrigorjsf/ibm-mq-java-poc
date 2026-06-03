package com.example.ibmmq.correlation;

import com.example.ibmmq.config.MqProperties;
import com.example.ibmmq.consumer.ReportMessageConsumer;
import com.example.ibmmq.messaging.ReceivePort;
import com.example.ibmmq.messaging.ReportEnvelope;
import com.example.ibmmq.model.DeliveryEvent;
import com.example.ibmmq.model.PendingMessage;
import com.example.ibmmq.model.ReportType;
import com.example.ibmmq.report.ReportFeedbackRouter;
import com.ibm.mq.constants.MQConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Testes unitarios (sem broker) da logica de correlacao MessageId<->CorrelationId e do roteamento
 * de feedback no {@link ReportMessageConsumer}. Os relatorios sao fabricados como {@link ReportEnvelope}
 * sinteticos ({@link ReportEnvelope#synthetic}) — o seam (ADR-0008) ja entrega envelopes decodificados,
 * nunca um {@code javax.jms.Message}.
 */
class InMemoryCorrelationStoreTest {

    private InMemoryCorrelationStore store;
    private ReportMessageConsumer reportConsumer;

    private static final String ORIGINAL_MSG_ID = "ID:414d51204d513120202020202020202000000001";

    @BeforeEach
    void setUp() {
        store = new InMemoryCorrelationStore();

        // O consumer de relatorios so usa store + router em handleReport(); a ReceivePort/props nao sao
        // exercitadas nesse caminho, entao mockamos a porta e usamos props default.
        ReceivePort receivePort = mock(ReceivePort.class);
        MqProperties props = new MqProperties();
        // auditRepository=null + auditSchema=null: este teste unitario nao tem datasource, entao a
        // persistencia de auditoria (delivery_report) fica inerte — o consumer skipa o persist quando o repo
        // e nulo e nunca chama o guarda de schema (ADR-0010).
        reportConsumer = new ReportMessageConsumer(
                receivePort, props, store, new ReportFeedbackRouter(), null, null);
    }

    @Test
    @DisplayName("register + findByMessageId recupera a pendencia pela chave de MessageId")
    void registerAndFind() {
        store.register(PendingMessage.newlySent(ORIGINAL_MSG_ID, "pedido-1", "{}"));

        Optional<PendingMessage> found = store.findByMessageId(ORIGINAL_MSG_ID);
        assertTrue(found.isPresent());
        assertEquals("pedido-1", found.get().businessKey());
        assertEquals(1, store.pendingCount());
    }

    @Test
    @DisplayName("findByMessageId com id nulo ou desconhecido retorna vazio")
    void findUnknown() {
        assertTrue(store.findByMessageId(null).isEmpty());
        assertTrue(store.findByMessageId("ID:naoexiste").isEmpty());
    }

    @Test
    @DisplayName("markCoa/markCod sao atomicos e idempotentes")
    void markFlags() {
        store.register(PendingMessage.newlySent(ORIGINAL_MSG_ID, "pedido-2", "{}"));

        store.markCoaReceived(ORIGINAL_MSG_ID);
        assertTrue(store.findByMessageId(ORIGINAL_MSG_ID).orElseThrow().coaReceived());
        assertFalse(store.findByMessageId(ORIGINAL_MSG_ID).orElseThrow().isFullyConfirmed());

        // Marcar COA de novo (entrega at-least-once de relatorios) nao deve quebrar nada.
        store.markCoaReceived(ORIGINAL_MSG_ID);
        assertTrue(store.findByMessageId(ORIGINAL_MSG_ID).orElseThrow().coaReceived());

        store.markCodReceived(ORIGINAL_MSG_ID);
        assertTrue(store.findByMessageId(ORIGINAL_MSG_ID).orElseThrow().isFullyConfirmed());
    }

    @Test
    @DisplayName("Relatorio COA: correlaciona CorrelationId->MessageId e marca COA recebido")
    void handleCoaReport() {
        store.register(PendingMessage.newlySent(ORIGINAL_MSG_ID, "pedido-3", "{}"));

        // Default MQRO_COPY_MSG_ID_TO_CORREL_ID: o relatorio chega com CorrelationId == MessageId original.
        ReportEnvelope coaReport = ReportEnvelope.synthetic(
                MQConstants.MQFB_COA, ORIGINAL_MSG_ID, "", ReportType.COA);

        DeliveryEvent event = reportConsumer.handleReport(coaReport);

        assertEquals(ReportType.COA, event.reportType());
        assertEquals(259, event.feedbackCode());
        assertEquals(ORIGINAL_MSG_ID, event.correlationId());
        assertEquals(ORIGINAL_MSG_ID, event.originalMessageId());
        assertTrue(store.findByMessageId(ORIGINAL_MSG_ID).orElseThrow().coaReceived());
        assertFalse(store.findByMessageId(ORIGINAL_MSG_ID).orElseThrow().codReceived());
    }

    @Test
    @DisplayName("Relatorio COD apos COA: marca COD e remove a pendencia (entrega completa)")
    void handleCodReportRemovesWhenFullyConfirmed() {
        store.register(PendingMessage.newlySent(ORIGINAL_MSG_ID, "pedido-4", "{}"));
        store.markCoaReceived(ORIGINAL_MSG_ID); // COA ja recebido antes

        ReportEnvelope codReport = ReportEnvelope.synthetic(
                MQConstants.MQFB_COD, ORIGINAL_MSG_ID, "", ReportType.COD);

        DeliveryEvent event = reportConsumer.handleReport(codReport);

        assertEquals(ReportType.COD, event.reportType());
        assertEquals(260, event.feedbackCode());
        // COA+COD confirmados -> pendencia removida.
        assertTrue(store.findByMessageId(ORIGINAL_MSG_ID).isEmpty());
        assertEquals(0, store.pendingCount());
    }

    @Test
    @DisplayName("Relatorio de feedback desconhecido (CorrelationId orfao) ainda gera evento")
    void handleOrphanReport() {
        // feedback 2053 (MQRC_Q_FULL) classifica como EXCEPTION via o feedbackRouter — o arg de tipo do
        // synthetic() so define o char do descriptor, nunca a classificacao (que vem do feedback).
        ReportEnvelope report = ReportEnvelope.synthetic(2053, "ID:orfao", "", null);

        DeliveryEvent event = reportConsumer.handleReport(report);

        assertEquals(ReportType.EXCEPTION, event.reportType());
        assertEquals(2053, event.feedbackCode());
        // CorrelationId desconhecido -> originalMessageId cai de volta para o proprio correlationId.
        assertEquals("ID:orfao", event.originalMessageId());
    }
}
