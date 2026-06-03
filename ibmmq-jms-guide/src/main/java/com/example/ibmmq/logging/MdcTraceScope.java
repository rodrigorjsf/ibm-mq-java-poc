package com.example.ibmmq.logging;

import org.slf4j.MDC;

/**
 * The single contract for the "Log trace context (MDC)" concept (see {@code CONTEXT.md}): the pair of
 * ids ({@code messageId} + {@code correlationId}) bound into SLF4J's Mapped Diagnostic Context so every
 * log line of one business message's lifecycle (PRODUCE -&gt; CONSUME -&gt; COA/COD -&gt; reconcile) carries
 * them, making a single message greppable end-to-end.
 *
 * <p>This is the one source of truth for the code&lt;-&gt;logback contract: {@link #MESSAGE_ID} and
 * {@link #CORRELATION_ID} hold the exact key literals that {@code logback.xml} reads via
 * {@code %X{messageId}} / {@code %X{correlationId}}. Changing those literal values here silently drops
 * the ids from every log line, so they are fixed by contract and must not be edited.</p>
 *
 * <p><b>Why an {@link AutoCloseable} and not raw {@code MDC.put}/{@code MDC.remove}:</b> the bind/remove
 * pair must be structurally coupled. Under the ~10k-rpm target on Kubernetes (and with Virtual Threads,
 * where a carrier thread is reused across many tasks), a forgotten {@code MDC.remove} leaks one message's
 * ids onto the next task that reuses the thread. Wrapping the bind in try-with-resources
 * ({@code try (var scope = MdcTraceScope.bind(messageId, correlationId)) { ... }}) makes that leak
 * structurally impossible: {@link #close()} always runs, even on an exception.</p>
 *
 * <p>This is a logging/observability concern only — distinct from the Correlation store: it reconciles
 * nothing and holds no delivery state, it only decorates log output. The name deliberately avoids
 * "Correlation" so it does not overload the Correlation store concept (see {@code CONTEXT.md}).</p>
 */
public final class MdcTraceScope implements AutoCloseable {

    /**
     * MDC key for the original business MessageId. Its value is the literal {@code "messageId"} read by
     * {@code logback.xml}'s {@code %X{messageId}} — do not change it.
     */
    public static final String MESSAGE_ID = "messageId";

    /**
     * MDC key for the CorrelationId. Its value is the literal {@code "correlationId"} read by
     * {@code logback.xml}'s {@code %X{correlationId}} — do not change it.
     */
    public static final String CORRELATION_ID = "correlationId";

    private MdcTraceScope() {
    }

    /**
     * Binds {@code messageId} and {@code correlationId} into the MDC under {@link #MESSAGE_ID} and
     * {@link #CORRELATION_ID} respectively, and returns a scope whose {@link #close()} removes both.
     *
     * <p>Intended for try-with-resources at each lifecycle step:
     * {@code try (var scope = MdcTraceScope.bind(messageId, correlationId)) { LOG.info(...); }}.</p>
     *
     * @param messageId     value bound under {@link #MESSAGE_ID} (may differ from {@code correlationId}).
     * @param correlationId value bound under {@link #CORRELATION_ID}.
     * @return an open scope; close it (via try-with-resources) to clear both keys from the MDC.
     */
    public static MdcTraceScope bind(String messageId, String correlationId) {
        MDC.put(MESSAGE_ID, messageId);
        MDC.put(CORRELATION_ID, correlationId);
        return new MdcTraceScope();
    }

    /**
     * Removes both {@link #MESSAGE_ID} and {@link #CORRELATION_ID} from the MDC. Always invoked at the
     * end of the try-with-resources block, so the ids cannot leak onto a reused (carrier/virtual) thread.
     */
    @Override
    public void close() {
        MDC.remove(MESSAGE_ID);
        MDC.remove(CORRELATION_ID);
    }
}
