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

            // Atualiza o estado da pendencia conforme o tipo de relatorio.
            switch (type) {
                case COA -> correlationStore.markCoaReceived(correlationId);
                case COD -> {
                    correlationStore.markCodReceived(correlationId);
                    // Com COA+COD confirmados, a entrega esta completa: pode-se remover a pendencia.
                    correlationStore.findByMessageId(correlationId)
                            .filter(PendingMessage::isFullyConfirmed)
                            .ifPresent(p -> correlationStore.remove(correlationId));
                }
                case EXPIRATION, NAN, EXCEPTION ->
                        LOG.warn("Relatorio de problema: tipo={}, feedback={}, correlId={}",
                                type, feedback, correlationId);
                default -> { /* PAN/UNKNOWN: apenas registra abaixo. */ }
            }

            DeliveryEvent event = new DeliveryEvent(
                    type, feedback, correlationId, originalMessageId, Instant.now());

            LOG.info("Relatorio processado: tipo={}, feedback={}, correlId={}, originalMsgId={}, conhecido={}",
                    type, feedback, correlationId, originalMessageId, pending.isPresent());

            return event;
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao processar relatorio de entrega", e);
        }
    }
}
