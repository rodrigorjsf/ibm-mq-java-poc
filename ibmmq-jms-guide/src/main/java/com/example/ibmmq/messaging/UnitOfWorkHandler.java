package com.example.ibmmq.messaging;

/**
 * The business handler invoked inside {@link ReceivePort#receiveWithinUnitOfWork} for a single consumed
 * message body (ADR-0008 transaction semantics as a callback unit-of-work).
 *
 * <p><b>Contract:</b> the handler receives the decoded body of the consumed message. A <em>normal return</em>
 * tells the port to <b>commit</b> the transacted consume — which, for the business queue, releases the COD
 * report. A <em>thrown exception</em> tells the port to <b>roll back</b> — the message returns to the queue
 * and no COD is generated. The handler's returned value is propagated back to the caller of
 * {@code receiveWithinUnitOfWork} (typically the message body itself).</p>
 *
 * <p>No {@code jakarta.jms} type is exposed: the handler sees only the decoded {@code String} body.</p>
 */
@FunctionalInterface
public interface UnitOfWorkHandler {

    /**
     * Processes one consumed message body within the unit of work.
     *
     * @param body the decoded body of the consumed message (never {@code null} when invoked).
     * @return the value to propagate back to the {@code receiveWithinUnitOfWork} caller.
     * @throws Exception to trigger a rollback of the consume (the COD is NOT released).
     */
    String handle(String body) throws Exception;
}
