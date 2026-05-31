package com.example.ibmmq.model;

import java.time.Instant;

/**
 * Evento de entrega derivado de um relatorio (report) COA/COD/etc.
 *
 * @param reportType   tipo do relatorio (COA, COD, EXPIRATION, PAN, NAN, EXCEPTION...).
 * @param feedbackCode codigo de feedback bruto do MQMD (ex. 259=COA, 260=COD). Para relatorios
 *                     de excecao este e um reason code MQRC_* (nao um MQFB_* fixo).
 * @param correlationId JMSCorrelationID do relatorio — por padrao igual ao MessageId original.
 * @param originalMessageId MessageId da mensagem de negocio correlacionada (se conhecido no store).
 * @param occurredAt   instante em que o evento foi processado pelo consumidor de relatorios.
 */
public record DeliveryEvent(
        ReportType reportType,
        int feedbackCode,
        String correlationId,
        String originalMessageId,
        Instant occurredAt
) {
}
