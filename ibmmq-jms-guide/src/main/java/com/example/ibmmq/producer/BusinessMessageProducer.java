package com.example.ibmmq.producer;

import com.example.ibmmq.config.MqProperties;
import com.example.ibmmq.correlation.CorrelationStore;
import com.example.ibmmq.messaging.OutboundMessage;
import com.example.ibmmq.messaging.SendPort;
import com.example.ibmmq.model.PendingMessage;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

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
 *
 * <p><b>Seam (ADR-0008):</b> este entry point nao abre mais um {@code JMSContext} proprio — delega ao
 * {@link SendPort} (a porta de envio sobre a factory de produtor pooled, ADR-0006). Toda a construcao
 * {@code javax.jms} (TextMessage, JMSReplyTo, opcoes de report, resolucao {@code queue:///}) vive no
 * adapter pooled-JMS; o produtor apenas monta o {@link OutboundMessage} decodificado e nunca ve um
 * {@code javax.jms.Message}.</p>
 */
@Singleton
public class BusinessMessageProducer {

    private static final Logger LOG = LoggerFactory.getLogger(BusinessMessageProducer.class);

    private final SendPort sendPort;
    private final MqProperties props;
    private final CorrelationStore correlationStore;

    public BusinessMessageProducer(SendPort sendPort,
                                   MqProperties props,
                                   CorrelationStore correlationStore) {
        this.sendPort = sendPort;
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
        // Decoded outbound envelope: a persistent business message with both COA and COD requested,
        // bound for the report (reply-to) queue. The SendPort adapter owns all JMS construction.
        OutboundMessage outbound = OutboundMessage.persistentWithCoaCod(
                businessKey, jsonPayload, props.getBusinessQueue(), props.getReportQueue());

        // The SendPort returns the assigned messageId only after the send. Default
        // MQRO_COPY_MSG_ID_TO_CORREL_ID makes this id the report's CorrelationId.
        String messageId = sendPort.send(outbound);

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
    }
}
