package com.example.ibmmq.model;

import java.time.Instant;

/**
 * Mensagem de negocio pendente — aguardando os relatorios COA/COD que confirmam sua entrega.
 *
 * <p>Indexada pelo {@code messageId} retornado no envio (JMSMessageID). Como o default IBM MQ e
 * {@code MQRO_COPY_MSG_ID_TO_CORREL_ID}, o relatorio chega com {@code JMSCorrelationID == messageId},
 * permitindo a correlacao reversa.</p>
 *
 * @param messageId   JMSMessageID da mensagem de negocio (chave de correlacao).
 * @param businessKey identificador de dominio (ex. id do pedido) para rastreabilidade legivel.
 * @param payload     conteudo (JSON) enviado — util para reprocessamento/auditoria.
 * @param sentAt      instante do envio.
 * @param coaReceived se ja recebemos o relatorio COA desta mensagem.
 * @param codReceived se ja recebemos o relatorio COD desta mensagem.
 */
public record PendingMessage(
        String messageId,
        String businessKey,
        String payload,
        Instant sentAt,
        boolean coaReceived,
        boolean codReceived
) {
    /** Cria uma nova pendencia recem-enviada (sem relatorios ainda). */
    public static PendingMessage newlySent(String messageId, String businessKey, String payload) {
        return new PendingMessage(messageId, businessKey, payload, Instant.now(), false, false);
    }

    /** Entrega totalmente confirmada quando COA e COD foram recebidos. */
    public boolean isFullyConfirmed() {
        return coaReceived && codReceived;
    }
}
