package com.example.ibmmq.correlation;

import com.example.ibmmq.correlation.ReconcileResult.Outcome;
import com.example.ibmmq.model.PendingMessage;
import com.example.ibmmq.model.ReportType;

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

    /**
     * Records a single COA or COD report and reconciles the pending pair in ONE higher-level step.
     *
     * <p>This is a {@code default} method that <b>composes the existing primitives</b>
     * ({@link #findByMessageId}, {@link #markCoaReceived}/{@link #markCodReceived},
     * {@link #removeIfFullyConfirmed}); it deliberately does NOT introduce a new persistence path, so
     * the {@link JdbcCorrelationStore} adapter is not rewritten (ADR-0005-safe — every adapter inherits
     * this identical reconciliation logic on top of its own primitives). It collapses the consumer's
     * former per-branch two-step (mark, then reconcile-if-complete) into a single call.</p>
     *
     * <p><b>Why the prior lookup is needed (orphan detection):</b> {@code markCoaReceived}/
     * {@code markCodReceived} ALWAYS upsert — they create a stub row when none exists (a report can
     * arrive before {@code register}). So the post-mark row alone cannot distinguish a known message
     * from an orphan. We therefore read {@link #findByMessageId} FIRST: a present row means the message
     * was known, an absent row means this report is an orphan (an at-least-once redelivery after the
     * pair already completed and the row was removed, or a report this process never registered).</p>
     *
     * <p><b>Outcome derivation:</b></p>
     * <ul>
     *   <li>prior absent &rarr; {@link Outcome#ORPHAN} (with {@code pending == null});</li>
     *   <li>prior present and THIS call removed the fully-confirmed row &rarr; {@link Outcome#COMPLETED};</li>
     *   <li>prior present and the pair is not yet complete &rarr; {@link Outcome#RECORDED}.</li>
     * </ul>
     *
     * <p>Note that {@code ORPHAN} takes precedence over {@code COMPLETED}: a redelivered single report
     * after completion re-creates a one-flag stub that {@code removeIfFullyConfirmed} will NOT remove,
     * so {@code removed} is false there anyway — the absent prior is what flags it as an orphan.</p>
     *
     * @param correlationId the report's {@code JMSCorrelationID} (== original MessageId under the
     *                      default {@code MQRO_COPY_MSG_ID_TO_CORREL_ID} propagation).
     * @param type          the classified report type; MUST be {@link ReportType#COA} or
     *                      {@link ReportType#COD} (this op is only ever called for those two).
     * @return a {@link ReconcileResult} carrying the {@link Outcome} and the prior pending row (or
     *         {@code null} on {@code ORPHAN}).
     * @throws IllegalArgumentException if {@code type} is not COA or COD (loud-fail on misuse).
     */
    default ReconcileResult recordReport(String correlationId, ReportType type) {
        // (a) Look up the prior row BEFORE marking — the only way to tell a known message from an
        // orphan, since the mark* primitives always upsert a stub.
        Optional<PendingMessage> prior = findByMessageId(correlationId);

        // (b) Apply the flag for this report type. recordReport is only ever called for COA/COD.
        switch (type) {
            case COA -> markCoaReceived(correlationId);
            case COD -> markCodReceived(correlationId);
            default -> throw new IllegalArgumentException(
                    "recordReport supports only COA/COD reports, got: " + type);
        }

        // (c) Order-independent reconciliation: whichever report completes the pair removes the row.
        // Exactly one competing consumer observes removed == true (the rest are no-ops).
        boolean removed = removeIfFullyConfirmed(correlationId);

        // (d) Derive the outcome. ORPHAN (no prior registration) takes precedence; otherwise COMPLETED
        // iff THIS call removed the fully-confirmed row, else RECORDED.
        Outcome outcome = prior.isEmpty()
                ? Outcome.ORPHAN
                : (removed ? Outcome.COMPLETED : Outcome.RECORDED);

        // (e) pending is the prior row (null on ORPHAN) — the caller derives originalMessageId + sentAt.
        return new ReconcileResult(outcome, prior.orElse(null));
    }
}
