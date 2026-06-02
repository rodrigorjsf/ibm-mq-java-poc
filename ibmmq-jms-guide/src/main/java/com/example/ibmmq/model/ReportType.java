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
    UNKNOWN;

    /**
     * Sentinel single-char value for report types that have no COA/COD domain projection. Stored as the
     * {@code report_type_char} audit column for non-COA/COD reports (and never asserted as {@code 'A'}/
     * {@code 'D'}).
     */
    public static final char DOMAIN_CHAR_OTHER = '?';

    /**
     * Projects this report type to the single domain char persisted in {@code delivery_report}
     * ({@code report_type_char}). This is issue #19's field #6 — a pure derivation, since MQ exposes no
     * single-char report-type field; the value is derived purely from the classified type:
     * {@code COA -> 'A'}, {@code COD -> 'D'}, everything else -> {@link #DOMAIN_CHAR_OTHER}.
     *
     * @return {@code 'A'} for {@link #COA}, {@code 'D'} for {@link #COD}, {@link #DOMAIN_CHAR_OTHER} otherwise.
     */
    public char toDomainChar() {
        return switch (this) {
            case COA -> 'A';
            case COD -> 'D';
            default -> DOMAIN_CHAR_OTHER;
        };
    }
}
