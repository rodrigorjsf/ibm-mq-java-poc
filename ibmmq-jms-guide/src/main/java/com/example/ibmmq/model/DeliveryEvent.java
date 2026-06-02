package com.example.ibmmq.model;

import com.example.ibmmq.report.HexBytes;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Evento de entrega derivado de um relatorio (report) COA/COD/etc.
 *
 * <p><b>Issue #19 — recuperacao de campos MQMD do relatorio:</b> alem dos cinco campos canonicos, o
 * evento carrega agora os seis valores recuperados do PROPRIO descriptor MQMD do relatorio (verdict
 * {@code (R)}-all — ver {@code research-output/phase-f-mqmd-field-recovery.md}): identidade da aplicacao,
 * accounting token, correlation id (byte[]), message id (byte[]), put-timestamp (UTC) e o char de tipo
 * derivado. Os campos {@code byte[]} sao expostos via acessores hex ({@link #accountingTokenHex()} etc.)
 * para seguranca de log e de igualdade de valor (um {@code byte[]} cru quebra {@code equals} de record e
 * loga como lixo).</p>
 *
 * <p><b>Compatibilidade retroativa:</b> o construtor canonico de 5 argumentos
 * {@code (reportType, feedbackCode, correlationId, originalMessageId, occurredAt)} continua disponivel via
 * um construtor secundario que zera (null/sentinel) os seis campos novos — todos os call sites existentes
 * (demo runner, testes de correlacao/log) compilam sem alteracao. Apenas
 * {@code ReportMessageConsumer.handleReport} constroi o evento completo de 11 campos.</p>
 *
 * @param reportType   tipo do relatorio (COA, COD, EXPIRATION, PAN, NAN, EXCEPTION...).
 * @param feedbackCode codigo de feedback bruto do MQMD (ex. 259=COA, 260=COD). Para relatorios
 *                     de excecao este e um reason code MQRC_* (nao um MQFB_* fixo).
 * @param correlationId JMSCorrelationID do relatorio — por padrao igual ao MessageId original.
 * @param originalMessageId MessageId da mensagem de negocio correlacionada (se conhecido no store).
 * @param occurredAt   instante em que o evento foi processado pelo consumidor de relatorios.
 * @param applIdentityData   ApplIdentityData do PROPRIO relatorio (definido pelo QMgr; pode ser nulo/branco).
 * @param accountingToken    AccountingToken do PROPRIO relatorio (32 bytes; pode ser nulo).
 * @param correlationIdBytes CorrelId em bytes == MsgId original sob a propagacao default (pode ser nulo).
 * @param messageIdBytes     MsgId do PROPRIO relatorio em bytes (pode ser nulo se o read nao estiver habilitado).
 * @param putTimestampUtc    put-time do relatorio como relogio-de-parede UTC (pode ser nulo).
 * @param reportTypeChar     char de tipo derivado ({@code 'A'} para COA, {@code 'D'} para COD; sentinela caso contrario).
 */
public record DeliveryEvent(
        ReportType reportType,
        int feedbackCode,
        String correlationId,
        String originalMessageId,
        Instant occurredAt,
        String applIdentityData,
        byte[] accountingToken,
        byte[] correlationIdBytes,
        byte[] messageIdBytes,
        LocalDateTime putTimestampUtc,
        char reportTypeChar
) {

    /**
     * Construtor secundario de 5 argumentos (compatibilidade retroativa): os seis campos MQMD recuperados
     * (issue #19) ficam {@code null} / sentinela. Mantem todos os call sites pre-#19 compilando sem mudanca.
     */
    public DeliveryEvent(ReportType reportType,
                         int feedbackCode,
                         String correlationId,
                         String originalMessageId,
                         Instant occurredAt) {
        this(reportType, feedbackCode, correlationId, originalMessageId, occurredAt,
                null, null, null, null, null, ReportType.DOMAIN_CHAR_OTHER);
    }

    /** O AccountingToken do relatorio em hex (minusculo), ou {@code null} quando ausente. */
    public String accountingTokenHex() {
        return HexBytes.toHex(accountingToken);
    }

    /** Os bytes de CorrelId (== MsgId original sob propagacao default) em hex, ou {@code null}. */
    public String correlationIdBytesHex() {
        return HexBytes.toHex(correlationIdBytes);
    }

    /** Os bytes do proprio MsgId do relatorio em hex, ou {@code null}. */
    public String messageIdBytesHex() {
        return HexBytes.toHex(messageIdBytes);
    }
}
