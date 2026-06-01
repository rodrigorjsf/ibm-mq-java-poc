package com.example.ibmmq.correlation;

import com.example.ibmmq.model.PendingMessage;

import java.util.Optional;

/**
 * Armazena as mensagens de negocio pendentes para correlacionar os relatorios (COA/COD) de volta
 * a mensagem original.
 *
 * <p><b>Modelo de correlacao:</b> ao enviar, registramos {@code messageId} (JMSMessageID). Como o
 * default IBM MQ e {@code MQRO_COPY_MSG_ID_TO_CORREL_ID}, o relatorio chega com
 * {@code JMSCorrelationID == messageId} original. Assim o consumidor de relatorios faz
 * {@code findByMessageId(report.getJMSCorrelationID())}.</p>
 */
public interface CorrelationStore {

    /** Registra uma nova mensagem de negocio aguardando confirmacoes. */
    void register(PendingMessage pending);

    /**
     * Busca a pendencia pelo MessageId original.
     *
     * @param messageId tipicamente o {@code JMSCorrelationID} do relatorio recebido.
     */
    Optional<PendingMessage> findByMessageId(String messageId);

    /** Marca o COA como recebido para o MessageId dado. No-op se desconhecido. */
    Optional<PendingMessage> markCoaReceived(String messageId);

    /** Marca o COD como recebido para o MessageId dado. No-op se desconhecido. */
    Optional<PendingMessage> markCodReceived(String messageId);

    /** Remove a pendencia (ex. apos COA+COD confirmados). */
    void remove(String messageId);

    /**
     * Remove a pendencia atomicamente SE (e somente se) COA e COD ja estiverem ambos confirmados.
     * Retorna {@code true} apenas se ESTA chamada realizou a remocao.
     *
     * <p>Independente de ordem: qualquer um dos relatorios (COA ou COD) que complete o par dispara a
     * remocao, e consumidores concorrentes (competing consumers em pods distintos) competem com
     * seguranca — exatamente uma chamada remove, as demais sao no-op. E isto que permite o
     * {@link #pendingCount()} drenar a zero cluster-wide SEM um sweep manual de operador, mesmo quando
     * COA e COD sao processados fora de ordem em pods diferentes.</p>
     */
    boolean removeIfFullyConfirmed(String messageId);

    /** Numero de mensagens ainda pendentes (sem confirmacao completa). */
    int pendingCount();
}
