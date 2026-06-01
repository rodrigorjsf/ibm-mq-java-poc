package com.example.ibmmq.producer;

import com.example.ibmmq.config.MqProperties;
import com.example.ibmmq.correlation.CorrelationStore;
import com.example.ibmmq.model.PendingMessage;
import com.ibm.msg.client.wmq.WMQConstants;
import com.ibm.mq.constants.MQConstants;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import javax.jms.ConnectionFactory;
import javax.jms.DeliveryMode;
import javax.jms.JMSContext;
import javax.jms.JMSProducer;
import javax.jms.Queue;
import javax.jms.TextMessage;

/**
 * Produz mensagens de negocio (JSON) na fila de negocio, solicitando relatorios COA e COD.
 *
 * <p><b>Fluxo de relatorios:</b> ao habilitar COA e COD via as propriedades JMS {@code JMS_IBM_Report_*},
 * o gerenciador de filas gerara:
 * <ul>
 *   <li><b>COA</b> quando a mensagem for COLOCADA na fila de destino (timing de chegada);</li>
 *   <li><b>COD</b> quando a app consumidora fizer um GET destrutivo (timing de entrega).</li>
 * </ul>
 * Ambos os relatorios sao enviados para a fila indicada em {@code JMSReplyTo}.</p>
 *
 * <p><b>Correlacao:</b> nao definimos opcoes de propagacao de id, entao vale o default
 * {@code MQRO_COPY_MSG_ID_TO_CORREL_ID}: o MessageId desta mensagem vira o CorrelationId do relatorio.
 * Registramos o MessageId no {@link CorrelationStore} para fechar o ciclo quando o relatorio chegar.</p>
 */
@Singleton
public class BusinessMessageProducer {

    private static final Logger LOG = LoggerFactory.getLogger(BusinessMessageProducer.class);

    private final ConnectionFactory connectionFactory;
    private final MqProperties props;
    private final CorrelationStore correlationStore;

    public BusinessMessageProducer(ConnectionFactory connectionFactory,
                                   MqProperties props,
                                   CorrelationStore correlationStore) {
        this.connectionFactory = connectionFactory;
        this.props = props;
        this.correlationStore = correlationStore;
    }

    /**
     * Envia uma mensagem de negocio persistente com COA+COD habilitados.
     *
     * @param businessKey identificador de dominio (ex. id do pedido) — para log/auditoria.
     * @param jsonPayload corpo JSON ja serializado.
     * @return o JMSMessageID atribuido (chave de correlacao com os relatorios).
     */
    public String send(String businessKey, String jsonPayload) {
        // try-with-resources: o JMSContext (e a conexao/sessao subjacentes) e fechado ao final.
        // AUTO_ACKNOWLEDGE: o send e confirmado imediatamente (COA so e recuperavel apos o commit;
        // em modo auto-ack o produtor "commita" cada envio).
        try (JMSContext context = connectionFactory.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {

            Queue businessQueue = context.createQueue("queue:///" + props.getBusinessQueue());
            // Fila de relatorios — destino do JMSReplyTo (para onde o QMgr enviara COA/COD).
            Queue reportQueue = context.createQueue("queue:///" + props.getReportQueue());

            TextMessage message = context.createTextMessage(jsonPayload);

            // ---- JMSReplyTo: para onde vao os relatorios ----
            message.setJMSReplyTo(reportQueue);

            // ---- Habilita os relatorios via propriedades JMS de report ----
            // O campo Java e UPPER_SNAKE (JMS_IBM_REPORT_COA) e o valor passado e o inteiro MQRO_*.
            // MQRO_COA = solicita Confirmation On Arrival.
            message.setIntProperty(WMQConstants.JMS_IBM_REPORT_COA, MQConstants.MQRO_COA);
            // MQRO_COD = solicita Confirmation On Delivery.
            message.setIntProperty(WMQConstants.JMS_IBM_REPORT_COD, MQConstants.MQRO_COD);
            // (Opcional) Exception + Expiration: descomentar para tambem receber esses relatorios.
            // message.setIntProperty(WMQConstants.JMS_IBM_REPORT_EXCEPTION, MQConstants.MQRO_EXCEPTION);
            // message.setIntProperty(WMQConstants.JMS_IBM_REPORT_EXPIRATION, MQConstants.MQRO_EXPIRATION);

            JMSProducer producer = context.createProducer();
            // PERSISTENT explicito: a mensagem (e, por heranca, os relatorios) sobrevive a restart do QMgr.
            producer.setDeliveryMode(DeliveryMode.PERSISTENT);

            producer.send(businessQueue, message);

            // O JMSMessageID so e atribuido apos o send. Default MQRO_COPY_MSG_ID_TO_CORREL_ID faz
            // este id virar o CorrelationId dos relatorios.
            String messageId = message.getJMSMessageID();

            // MDC: vincula messageId e correlationId para que esta primeira etapa do ciclo de vida
            // ja carregue os mesmos ids que aparecerao nas etapas COA/COD. Como o default IBM MQ e
            // MQRO_COPY_MSG_ID_TO_CORREL_ID, o CorrelationId do relatorio futuro sera ESTE messageId —
            // por isso vinculamos correlationId = messageId aqui (a chave que fecha o ciclo).
            MDC.put("messageId", messageId);
            MDC.put("correlationId", messageId);
            try {
                correlationStore.register(PendingMessage.newlySent(messageId, businessKey, jsonPayload));

                LOG.info("[stage=PRODUCE] Mensagem de negocio enviada: businessKey={}, messageId={}, replyTo={}",
                        businessKey, messageId, props.getReportQueue());

                return messageId;
            } finally {
                // Limpa o MDC antes de devolver a thread (virtual/carrier) ao pool: sob ~10k rpm uma
                // thread reutilizada nao pode vazar os ids desta mensagem para a proxima.
                MDC.remove("messageId");
                MDC.remove("correlationId");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao enviar mensagem de negocio: " + businessKey, e);
        }
    }
}
