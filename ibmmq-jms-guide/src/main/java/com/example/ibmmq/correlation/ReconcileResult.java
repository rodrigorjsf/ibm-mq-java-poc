package com.example.ibmmq.correlation;

import com.example.ibmmq.model.PendingMessage;

/**
 * Result of a single higher-level reconciliation step produced by
 * {@link CorrelationStore#recordReport(String, com.example.ibmmq.model.ReportType)}.
 *
 * <p>It bundles the {@link Outcome} of recording one COA/COD report with the {@link PendingMessage}
 * that was known <em>before</em> the report was marked. Callers derive the original {@code messageId}
 * and {@code sentAt} (for the {@code DeliveryEvent}) from {@link #pending()}, and switch on
 * {@link #outcome()} for logging and the orphan-rate metric.</p>
 *
 * @param outcome how recording this report resolved (see {@link Outcome}).
 * @param pending the pending row known BEFORE the mark, or {@code null} when the report was an
 *                {@link Outcome#ORPHAN} (no prior registration existed for this correlation id).
 */
public record ReconcileResult(Outcome outcome, PendingMessage pending) {

    /**
     * Classification of a single recorded COA/COD report.
     *
     * <ul>
     *   <li>{@link #RECORDED} — the report was applied to a KNOWN pending message but the COA/COD pair
     *       is not yet complete (the row stays pending).</li>
     *   <li>{@link #COMPLETED} — applying this report completed the COA+COD pair, and THIS call removed
     *       the row (the fully-confirmed delivery is reconciled). Exactly one competing consumer sees
     *       {@code COMPLETED}; concurrent callers see {@code RECORDED}.</li>
     *   <li>{@link #ORPHAN} — the report arrived for a correlation id that has no prior registration
     *       (e.g. an at-least-once redelivery after the pair already completed and the row was removed,
     *       or a report for a message this process never registered). Surfaced as a WARN + orphan-rate
     *       counter; never swept.</li>
     * </ul>
     */
    public enum Outcome {
        RECORDED,
        COMPLETED,
        ORPHAN
    }
}
