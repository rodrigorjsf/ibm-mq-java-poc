package com.example.ibmmq.model;

/**
 * Classificacao de um relatorio (report) de entrega IBM MQ a partir do codigo de feedback (MQMD.Feedback).
 *
 * <ul>
 *   <li>{@link #COA} — Confirmation On Arrival: a mensagem chegou na fila de destino.</li>
 *   <li>{@link #COD} — Confirmation On Delivery: a mensagem foi recuperada (get destrutivo) pela app.</li>
 *   <li>{@link #EXPIRATION} — a mensagem expirou antes de ser consumida.</li>
 *   <li>{@link #PAN} — Positive Action Notification: a app consumidora processou com sucesso.</li>
 *   <li>{@link #NAN} — Negative Action Notification: a app consumidora falhou ao processar.</li>
 *   <li>{@link #EXCEPTION} — relatorio de excecao; o Feedback carrega um reason code MQRC_* (nao um MQFB_*).</li>
 *   <li>{@link #UNKNOWN} — feedback nao reconhecido.</li>
 * </ul>
 */
public enum ReportType {
    COA,
    COD,
    EXPIRATION,
    PAN,
    NAN,
    EXCEPTION,
    UNKNOWN
}
