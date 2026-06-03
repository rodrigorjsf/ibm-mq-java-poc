package com.example.ibmmq.messaging;

import com.example.ibmmq.config.MqConnectionFactoryFactory;
import com.example.ibmmq.model.ReportType;
import com.example.ibmmq.report.ReportDescriptor;
import com.example.ibmmq.report.ReportFeedbackRouter;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import io.micronaut.context.annotation.Requires;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.jms.JMSConsumer;
import jakarta.jms.JMSContext;
import jakarta.jms.Message;
import jakarta.jms.Queue;
import jakarta.jms.TextMessage;

/**
 * Production {@link ReceivePort} adapter over the <b>dedicated long-lived consumer factory</b> (ADR-0006).
 * This is the only place on the receive side that touches {@code jakarta.jms}.
 *
 * <h2>Long-lived held contexts (ADR-0006 consumer lifecycle)</h2>
 * The adapter holds TWO long-lived {@link JMSContext}s drawn from the dedicated, non-pooled
 * consumer {@link MQConnectionFactory} (ADR-0006 {@code @Named(CONSUMER)} bean), each for the pod's life:
 * <ul>
 *   <li>a {@code SESSION_TRANSACTED} context for {@link #receiveWithinUnitOfWork} (the business consume whose
 *       commit releases the COD);</li>
 *   <li>an {@code AUTO_ACKNOWLEDGE} context for {@link #receiveReport} (behavior-preserving — the report path
 *       stays AUTO_ACKNOWLEDGE / best-effort idempotent audit; the ADR-0005 commit-after-process upgrade is
 *       out of scope).</li>
 * </ul>
 * Two contexts are needed because the two paths require different acknowledge modes, which are fixed at
 * {@code createContext} time. Both are created <b>lazily</b> on first use and reused thereafter; a held
 * context is closed and re-created on failure (reconnect), and both are closed at {@code @PreDestroy}.
 *
 * <h2>Concurrency</h2>
 * Under the project topology there is exactly one single-threaded consumer loop per pod for each role, so a
 * given context is touched by one thread. The lazy-init / reconnect / close paths are still guarded by the
 * context's own monitor to stay correct if a second caller ever appears.
 *
 * <p><b>Default bean.</b> Gated on {@code messaging.adapter != fake}; absent when the in-memory fake is
 * selected.</p>
 */
@Singleton
@Requires(property = "messaging.adapter", notEquals = "fake")
public class PooledJmsReceiveAdapter implements ReceivePort {

    private static final Logger LOG = LoggerFactory.getLogger(PooledJmsReceiveAdapter.class);

    private final MQConnectionFactory connectionFactory;
    private final ReportFeedbackRouter feedbackRouter;

    /** Long-lived held contexts (lazy). Guarded by their respective lock objects. */
    private volatile JMSContext businessContext;
    private volatile JMSContext reportContext;
    private final Object businessLock = new Object();
    private final Object reportLock = new Object();

    public PooledJmsReceiveAdapter(
            @Named(MqConnectionFactoryFactory.CONSUMER) MQConnectionFactory connectionFactory,
            ReportFeedbackRouter feedbackRouter) {
        this.connectionFactory = connectionFactory;
        this.feedbackRouter = feedbackRouter;
    }

    @Override
    public String receiveWithinUnitOfWork(String queueName, long timeoutMillis, UnitOfWorkHandler handler) {
        synchronized (businessLock) {
            JMSContext context = businessContext();
            try {
                Queue businessQueue = context.createQueue("queue:///" + queueName);
                JMSConsumer consumer = context.createConsumer(businessQueue);

                // Destructive GET — removes the message and (given MQRO_COD on the original) schedules the COD.
                Message message = consumer.receive(timeoutMillis);
                if (message == null) {
                    return null; // timeout — nothing to commit; the held context stays open for the next poll.
                }

                String body = (message instanceof TextMessage textMessage)
                        ? textMessage.getText()
                        : "(payload nao-texto)";

                String result;
                try {
                    result = handler.handle(body);
                } catch (Exception handlerFailure) {
                    // Rollback: the message returns to the queue and the COD is NOT generated.
                    context.rollback();
                    throw new IllegalStateException("Unit-of-work handler failed — rolled back", handlerFailure);
                }

                // Commit: confirms the consume and releases the COD to the report queue.
                context.commit();
                return result;
            } catch (IllegalStateException alreadyWrapped) {
                throw alreadyWrapped;
            } catch (Exception jmsFailure) {
                // A JMS failure invalidates the held context: drop it so the next call reconnects.
                closeQuietly(businessContext);
                businessContext = null;
                throw new IllegalStateException("Falha ao consumir mensagem de negocio", jmsFailure);
            }
        }
    }

    @Override
    public ReportEnvelope receiveReport(String queueName, long timeoutMillis) {
        synchronized (reportLock) {
            JMSContext context = reportContext();
            try {
                // Issue #19: enable MQMD read on the consume destination via the URI form so the
                // JMS_IBM_MQMD_* properties are populated. The canonical JMS_IBM_Feedback needs no read-enable.
                JMSConsumer consumer = context.createConsumer(
                        context.createQueue("queue:///" + queueName + "?mdReadEnabled=true"));

                Message report = consumer.receive(timeoutMillis);
                if (report == null) {
                    return null; // timeout
                }
                return decode(report);
            } catch (Exception jmsFailure) {
                closeQuietly(reportContext);
                reportContext = null;
                throw new IllegalStateException("Falha ao receber relatorio de entrega", jmsFailure);
            }
        }
    }

    /**
     * Extracts the feedback code, correlation id, body, and the six MQMD values from a received report into a
     * {@link ReportEnvelope}. ALL JMS extraction happens here (ADR-0008) so no {@code jakarta.jms.Message}
     * crosses the seam.
     */
    private ReportEnvelope decode(Message report) throws Exception {
        int feedback = report.getIntProperty(WMQConstants.JMS_IBM_FEEDBACK);
        String correlationId = report.getJMSCorrelationID();
        ReportType type = feedbackRouter.classify(feedback);
        // Null-safe, non-throwing (degrades to nulls when read is not enabled or fields absent).
        ReportDescriptor descriptor = ReportDescriptor.from(report, type);
        String body = (report instanceof TextMessage textMessage) ? textMessage.getText() : null;
        return new ReportEnvelope(feedback, correlationId, body, descriptor);
    }

    /** Returns the held transacted business context, (re)creating it lazily. Caller holds {@link #businessLock}. */
    private JMSContext businessContext() {
        JMSContext context = businessContext;
        if (context == null) {
            context = connectionFactory.createContext(JMSContext.SESSION_TRANSACTED);
            businessContext = context;
            LOG.info("[ADR-0006] Held long-lived SESSION_TRANSACTED consumer context opened (business consume)");
        }
        return context;
    }

    /** Returns the held auto-ack report context, (re)creating it lazily. Caller holds {@link #reportLock}. */
    private JMSContext reportContext() {
        JMSContext context = reportContext;
        if (context == null) {
            context = connectionFactory.createContext(JMSContext.AUTO_ACKNOWLEDGE);
            reportContext = context;
            LOG.info("[ADR-0006] Held long-lived AUTO_ACKNOWLEDGE consumer context opened (report receive)");
        }
        return context;
    }

    private static void closeQuietly(JMSContext context) {
        if (context != null) {
            try {
                context.close();
            } catch (RuntimeException ignored) {
                // Best-effort close on a failing context; the caller already nulls the field to force reconnect.
            }
        }
    }

    /** Closes both long-lived held contexts at pod shutdown (SIGTERM / context destroy). */
    @PreDestroy
    void close() {
        synchronized (businessLock) {
            closeQuietly(businessContext);
            businessContext = null;
        }
        synchronized (reportLock) {
            closeQuietly(reportContext);
            reportContext = null;
        }
        LOG.info("[ADR-0006] Held long-lived consumer contexts closed (@PreDestroy)");
    }
}
