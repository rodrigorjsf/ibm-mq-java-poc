package com.example.ibmmq.correlation;

import com.example.ibmmq.config.MqProperties;
import com.example.ibmmq.consumer.ReportMessageConsumer;
import com.example.ibmmq.model.DeliveryEvent;
import com.example.ibmmq.model.PendingMessage;
import com.example.ibmmq.model.ReportType;
import com.example.ibmmq.report.ReportFeedbackRouter;
import com.ibm.msg.client.wmq.WMQConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.jms.ConnectionFactory;
import javax.jms.Message;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Testes unitarios (sem broker) da logica de correlacao MessageId<->CorrelationId e do roteamento
 * de feedback no {@link ReportMessageConsumer}. Mockito e usado para fabricar relatorios JMS
 * ({@link Message}) sinteticos — onde realmente agrega valor.
 */
class InMemoryCorrelationStoreTest {

    private InMemoryCorrelationStore store;
    private ReportMessageConsumer reportConsumer;

    private static final String ORIGINAL_MSG_ID = "ID:414d51204d513120202020202020202000000001";

    @BeforeEach
    void setUp() {
        store = new InMemoryCorrelationStore();

        // O consumer de relatorios so usa store + router em handleReport(); CF/props nao sao
        // exercitados nesse caminho, entao mockamos o CF e usamos props default.
        ConnectionFactory cf = mock(ConnectionFactory.class);
        MqProperties props = new MqProperties();
        reportConsumer = new ReportMessageConsumer(cf, props, store, new ReportFeedbackRouter());
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
    void handleCoaReport() throws Exception {
        store.register(PendingMessage.newlySent(ORIGINAL_MSG_ID, "pedido-3", "{}"));

        Message coaReport = mock(Message.class);
        // Default MQRO_COPY_MSG_ID_TO_CORREL_ID: o relatorio chega com CorrelationId == MessageId original.
        when(coaReport.getJMSCorrelationID()).thenReturn(ORIGINAL_MSG_ID);
        when(coaReport.getIntProperty(WMQConstants.JMS_IBM_FEEDBACK)).thenReturn(259); // MQFB_COA

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
    void handleCodReportRemovesWhenFullyConfirmed() throws Exception {
        store.register(PendingMessage.newlySent(ORIGINAL_MSG_ID, "pedido-4", "{}"));
        store.markCoaReceived(ORIGINAL_MSG_ID); // COA ja recebido antes

        Message codReport = mock(Message.class);
        when(codReport.getJMSCorrelationID()).thenReturn(ORIGINAL_MSG_ID);
        when(codReport.getIntProperty(WMQConstants.JMS_IBM_FEEDBACK)).thenReturn(260); // MQFB_COD

        DeliveryEvent event = reportConsumer.handleReport(codReport);

        assertEquals(ReportType.COD, event.reportType());
        assertEquals(260, event.feedbackCode());
        // COA+COD confirmados -> pendencia removida.
        assertTrue(store.findByMessageId(ORIGINAL_MSG_ID).isEmpty());
        assertEquals(0, store.pendingCount());
    }

    @Test
    @DisplayName("Relatorio de feedback desconhecido (CorrelationId orfao) ainda gera evento")
    void handleOrphanReport() throws Exception {
        Message report = mock(Message.class);
        when(report.getJMSCorrelationID()).thenReturn("ID:orfao");
        when(report.getIntProperty(WMQConstants.JMS_IBM_FEEDBACK)).thenReturn(2053); // MQRC_Q_FULL

        DeliveryEvent event = reportConsumer.handleReport(report);

        assertEquals(ReportType.EXCEPTION, event.reportType());
        assertEquals(2053, event.feedbackCode());
        // CorrelationId desconhecido -> originalMessageId cai de volta para o proprio correlationId.
        assertEquals("ID:orfao", event.originalMessageId());
    }
}
