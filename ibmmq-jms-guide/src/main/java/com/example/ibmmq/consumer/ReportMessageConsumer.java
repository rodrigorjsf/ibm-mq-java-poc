package com.example.ibmmq.consumer;

import com.example.ibmmq.config.MqProperties;
import com.example.ibmmq.correlation.CorrelationStore;
import com.example.ibmmq.model.DeliveryEvent;
import com.example.ibmmq.model.PendingMessage;
import com.example.ibmmq.model.ReportType;
import com.example.ibmmq.report.ReportFeedbackRouter;
import com.ibm.msg.client.wmq.WMQConstants;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.Optional;

import javax.jms.JMSConsumer;
import javax.jms.ConnectionFactory;
import javax.jms.JMSContext;
import javax.jms.Message;

/**
 * Le a fila de relatorios (JMSReplyTo) e processa os relatorios de entrega COA/COD/etc.
 *
 * <p><b>Como classificar:</b> lemos o codigo de feedback do MQMD via a propriedade JMS
 * {@code JMS_IBM_Feedback} ({@code WMQConstants.JMS_IBM_FEEDBACK}). Esta e a propriedade canonica e
 * <em>sempre populada</em> para relatorios — diferente de {@code JMS_IBM_MQMD_Feedback}, que so e
 * preenchida quando {@code WMQ_MQMD_READ_ENABLED=true} no destino. (Ambas existem em
 * {@code JmsConstants} 9.4.5.0; usamos a primeira propositalmente.)</p>
 *
 * <p><b>Como correlacionar:</b> com o default {@code MQRO_COPY_MSG_ID_TO_CORREL_ID}, o relatorio
 * chega com {@code JMSCorrelationID == MessageId} da mensagem original. Buscamos a pendencia por esse
 * id no {@link CorrelationStore}.</p>
 */
@Singleton
public class ReportMessageConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(ReportMessageConsumer.class);

    private final ConnectionFactory connectionFactory;
    private final MqProperties props;
    private final CorrelationStore correlationStore;
    private final ReportFeedbackRouter feedbackRouter;

    public ReportMessageConsumer(ConnectionFactory connectionFactory,
                                 MqProperties props,
                                 CorrelationStore correlationStore,
                                 ReportFeedbackRouter feedbackRouter) {
        this.connectionFactory = connectionFactory;
        this.props = props;
        this.correlationStore = correlationStore;
        this.feedbackRouter = feedbackRouter;
    }

    /**
     * Recebe um relatorio da fila de relatorios (com timeout), classifica e registra o evento.
     *
     * @param timeoutMillis tempo maximo de espera (ms).
     * @return o {@link DeliveryEvent} derivado, ou {@code null} se o timeout expirar sem relatorio.
     */
    public DeliveryEvent receiveOneReport(long timeoutMillis) {
        try (JMSContext context = connectionFactory.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {

            JMSConsumer consumer = context.createConsumer(
                    context.createQueue("queue:///" + props.getReportQueue()));

            Message report = consumer.receive(timeoutMillis);
            if (report == null) {
                LOG.debug("Nenhum relatorio dentro do timeout ({} ms)", timeoutMillis);
                return null;
            }

            return handleReport(report);
        }
    }

    /**
     * Processa um unico relatorio JMS. Exposto separadamente para testabilidade (pode ser chamado
     * com um {@code Message} mockado).
     */
    public DeliveryEvent handleReport(Message report) {
        try {
            // Le o codigo de feedback do MQMD via a propriedade canonica JMS_IBM_Feedback.
            int feedback = report.getIntProperty(WMQConstants.JMS_IBM_FEEDBACK);
            String correlationId = report.getJMSCorrelationID();

            ReportType type = feedbackRouter.classify(feedback);

            // Correlaciona de volta a mensagem original (CorrelationId == MessageId original).
            Optional<PendingMessage> pending = correlationStore.findByMessageId(correlationId);
            String originalMessageId = pending.map(PendingMessage::messageId).orElse(correlationId);

            // MDC: correlationId = JMSCorrelationID do relatorio; messageId = MessageId original
            // derivado pela correlacao. Vinculamos ANTES das etapas para que toda linha (classify,
            // correlate, COA/COD, reconcile) carregue os mesmos ids — fechando a rastreabilidade
            // ponta-a-ponta: o mesmo id do PRODUCE aparece aqui no relatorio.
            MDC.put("messageId", originalMessageId);
            MDC.put("correlationId", correlationId);
            try {
                LOG.info("[stage=CLASSIFY] Relatorio classificado: tipo={}, feedback={}, correlId={}",
                        type, feedback, correlationId);
                LOG.info("[stage=CORRELATE] Correlacionado a mensagem original: originalMsgId={}, conhecido={}",
                        originalMessageId, pending.isPresent());

                // Atualiza o estado da pendencia conforme o tipo de relatorio.
                switch (type) {
                    case COA -> {
                        // COA = Confirmation On Arrival: a mensagem CHEGOU na fila de destino.
                        correlationStore.markCoaReceived(correlationId);
                        LOG.info("[stage=COA] Confirmacao de chegada (arrival) registrada: correlId={}, originalMsgId={}",
                                correlationId, originalMessageId);
                        // Reconcilia tambem aqui: sob competing consumers, o COD pode ter sido processado
                        // ANTES do COA em outro pod — entao e o COA que completa o par. Independente de ordem.
                        reconcileIfComplete(correlationId, originalMessageId);
                    }
                    case COD -> {
                        // COD = Confirmation On Delivery: a mensagem foi CONSUMIDA destrutivamente.
                        correlationStore.markCodReceived(correlationId);
                        LOG.info("[stage=COD] Confirmacao de entrega (delivery) registrada: correlId={}, originalMsgId={}",
                                correlationId, originalMessageId);
                        reconcileIfComplete(correlationId, originalMessageId);
                    }
                    case EXPIRATION, NAN, EXCEPTION ->
                            LOG.warn("[stage=PROBLEM] Relatorio de problema: tipo={}, feedback={}, correlId={}",
                                    type, feedback, correlationId);
                    default -> { /* PAN/UNKNOWN: apenas registra no resumo abaixo. */ }
                }

                DeliveryEvent event = new DeliveryEvent(
                        type, feedback, correlationId, originalMessageId, Instant.now());

                LOG.info("[stage=REPORT-DONE] Relatorio processado: tipo={}, feedback={}, correlId={}, originalMsgId={}, conhecido={}",
                        type, feedback, correlationId, originalMessageId, pending.isPresent());

                return event;
            } finally {
                // Limpa o MDC antes de devolver a thread ao pool (ver nota do produtor): sob ~10k rpm
                // uma thread reutilizada nao pode vazar os ids deste relatorio para o proximo.
                MDC.remove("messageId");
                MDC.remove("correlationId");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao processar relatorio de entrega", e);
        }
    }

    /**
     * Reconciliacao independente de ordem: qualquer relatorio (COA ou COD) que complete o par remove a
     * pendencia atomicamente. Seguro sob competing report-consumers em pods distintos — exatamente uma
     * chamada remove (ver {@link CorrelationStore#removeIfFullyConfirmed}). Isto faz o
     * {@code pendingCount()} drenar a zero mesmo quando COA e COD chegam fora de ordem em pods
     * diferentes, sem depender de um sweep manual de operador.
     */
    private void reconcileIfComplete(String correlationId, String originalMessageId) {
        if (correlationStore.removeIfFullyConfirmed(correlationId)) {
            LOG.info("[stage=RECONCILE] Entrega completa (COA+COD): pendencia reconciliada e removida, "
                            + "originalMsgId={}, pendentesRestantes={}",
                    originalMessageId, correlationStore.pendingCount());
        }
    }
}
