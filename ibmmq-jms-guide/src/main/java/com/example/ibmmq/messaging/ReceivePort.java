package com.example.ibmmq.messaging;

/**
 * The RECEIVE side of the messaging seam (ADR-0008): the role-based port over the <b>dedicated long-lived
 * consumer factory</b> (ADR-0006). Both the {@link com.example.ibmmq.consumer.BusinessMessageConsumer} and
 * the {@link com.example.ibmmq.consumer.ReportMessageConsumer} call through this single port, which holds
 * one long-lived consumer connection for the pod's life.
 *
 * <p>Only decoded domain types cross the seam — never a {@code javax.jms.Message}. The implementation owns
 * the {@code JMSContext} lifecycle, the {@code queue:///} resolution, the {@code ?mdReadEnabled=true} URI
 * form for the report destination, and all MQMD extraction into a {@link ReportEnvelope}.</p>
 *
 * <h2>Two receive modes — by design (behavior-preserving)</h2>
 * <ul>
 *   <li>{@link #receiveWithinUnitOfWork} — the <b>business</b> consume, {@code SESSION_TRANSACTED}: the
 *       handler runs inside the unit of work; a normal return commits (releasing the COD), a throw rolls
 *       back (no COD).</li>
 *   <li>{@link #receiveReport} — the <b>report</b> receive, {@code AUTO_ACKNOWLEDGE} / best-effort idempotent
 *       audit (NOT a unit of work). The ADR-0005 commit-after-process upgrade is intentionally OUT OF SCOPE.</li>
 * </ul>
 *
 * <h2>Adapters</h2>
 * <ul>
 *   <li>{@code PooledJmsReceiveAdapter} — production: a dedicated, non-pooled {@code MQConnectionFactory}
 *       holding one long-lived {@code JMSContext}, with {@code @PreDestroy} close and reconnect.</li>
 *   <li>{@code InMemoryReceivePort} — broker-free fake: serves messages put by the paired
 *       {@code InMemorySendPort}, models COD-on-commit, and delivers synthetic COA/COD reports.</li>
 * </ul>
 */
public interface ReceivePort {

    /**
     * Business consume within a transacted unit of work. Receives one message from {@code queueName} (with a
     * timeout); on a message, invokes {@code handler} with the decoded body and <b>commits</b> on a normal
     * return (releasing the COD report) or <b>rolls back</b> on a thrown exception (the message returns; no
     * COD). On timeout (no message in the window) returns {@code null} without invoking the handler.
     *
     * @param queueName     the plain business queue name (no {@code queue:///} prefix).
     * @param timeoutMillis the maximum wait for a message (ms).
     * @param handler       the unit-of-work handler (commit on return, rollback on throw).
     * @return the value returned by the handler, or {@code null} when the receive timed out.
     */
    String receiveWithinUnitOfWork(String queueName, long timeoutMillis, UnitOfWorkHandler handler);

    /**
     * Report receive under {@code AUTO_ACKNOWLEDGE} (behavior-preserving — best-effort idempotent audit
     * downstream, NOT a unit of work). Receives one report from {@code queueName} (with a timeout), extracts
     * the feedback code, correlation id, body, and the six MQMD values into a {@link ReportEnvelope}.
     *
     * @param queueName     the plain report queue name (no {@code queue:///} prefix; the adapter applies the
     *                      {@code ?mdReadEnabled=true} URI form so the MQMD values are populated).
     * @param timeoutMillis the maximum wait for a report (ms).
     * @return the decoded {@link ReportEnvelope}, or {@code null} when the receive timed out.
     */
    ReportEnvelope receiveReport(String queueName, long timeoutMillis);
}
