package com.example.ibmmq.demo;

import com.example.ibmmq.consumer.BusinessMessageConsumer;
import com.example.ibmmq.consumer.ReportMessageConsumer;
import com.example.ibmmq.model.DeliveryEvent;
import com.example.ibmmq.producer.BusinessMessageProducer;
import com.ibm.mq.constants.MQConstants;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Gated runner (triggered by an EXPLICIT flag) that executes the end-to-end COA/COD flow against the
 * local broker, reusing the production beans (producer, consumer, report consumer) and the narrated
 * logger (#11), printing a banner per stage and a final PASS/FAIL summary.
 *
 * <p><b>Gating (AC#3):</b> the {@code @Requires(property = "demo.coa-cod.enabled", value = "true")}
 * annotation ensures this bean is <em>not instantiated</em> when the flag is absent — so the normal
 * application startup is NOT affected and the no-flag path is unit-testable (the bean simply does not
 * exist in the {@code ApplicationContext}). The flag is EXPLICIT: never enabled by default.</p>
 *
 * <p><b>Why {@link ApplicationEventListener}&lt;{@link StartupEvent}&gt;?</b> {@code StartupEvent} is
 * published by Micronaut as soon as the context starts; the listener runs <em>synchronously</em> inside
 * the bootstrap. Since the bean only exists when the flag is on, "booting the context with the flag" is
 * exactly "running the demo" — a single command. Neither {@code StartupEvent} nor the listener bring new
 * dependencies (they come from {@code micronaut-runtime}, already present).</p>
 *
 * <p><b>Reuse, not re-implementation:</b> this runner does NOT speak JMS directly. It orchestrates the
 * existing beans — {@link BusinessMessageProducer#send}, {@link BusinessMessageConsumer#receiveOne},
 * {@link ReportMessageConsumer#receiveOneReport} — which already emit the per-stage narration
 * ([stage=PRODUCE/CONSUME/COMMIT/CLASSIFY/CORRELATE/COA/COD/RECONCILE]). The runner only adds an
 * orchestration banner and the final validation summary; it does NOT re-log those stages.</p>
 *
 * <p><b>Report authority (gotcha 2035):</b> for the QMgr to GENERATE+DELIVER a COA/COD report it performs
 * a PUT-with-context onto the ReplyToQ, requiring context authority ({@code +setall}). The low-privilege
 * {@code app} user of the dev image does NOT have this -> the report PUT fails with
 * {@code MQRC_NOT_AUTHORIZED (2035)}, the report goes to the DLQ and the report queue stays empty
 * (the demo "hangs" never seeing the reports). Therefore the demo must connect as {@code admin} via
 * {@code DEV.ADMIN.SVRCONN} — see {@code application-demo.yml} and the documented command in the README.</p>
 *
 * <p><b>No {@code System.exit}:</b> the listener runs inside the application JVM (and, in tests, inside
 * the surefire JVM). Terminating the process would kill the test runner and is unnecessary — the
 * PASS/FAIL summary is logged and the method returns normally.</p>
 */
@Singleton
@Requires(property = "demo.coa-cod.enabled", value = "true")
public class CoaCodDemoRunner implements ApplicationEventListener<StartupEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(CoaCodDemoRunner.class);

    /** Fixed business key for the demo (log-readable traceability only). */
    private static final String DEMO_BUSINESS_KEY = "demo-coa-cod";
    /** Sample JSON payload sent by the demo. */
    private static final String DEMO_PAYLOAD = "{\"demo\":\"coa-cod\",\"pedido\":42}";

    /** Default timeout (ms) for the destructive GET of the business message. */
    private static final long DEFAULT_CONSUME_TIMEOUT_MILLIS = Duration.ofSeconds(15).toMillis();
    /** Default timeout (ms) per read attempt on the report queue. */
    private static final long DEFAULT_REPORT_POLL_TIMEOUT_MILLIS = Duration.ofSeconds(5).toMillis();
    /** Default total deadline (ms) to collect BOTH reports (COA + COD). */
    private static final long DEFAULT_REPORT_DEADLINE_MILLIS = Duration.ofSeconds(30).toMillis();

    private final BusinessMessageProducer producer;
    private final BusinessMessageConsumer consumer;
    private final ReportMessageConsumer reportConsumer;

    private final long consumeTimeoutMillis;
    private final long reportPollTimeoutMillis;
    private final long reportDeadlineMillis;

    /** Production constructor (injected by Micronaut): uses the default timeouts/deadline. */
    @Inject
    public CoaCodDemoRunner(BusinessMessageProducer producer,
                            BusinessMessageConsumer consumer,
                            ReportMessageConsumer reportConsumer) {
        this(producer, consumer, reportConsumer,
                DEFAULT_CONSUME_TIMEOUT_MILLIS,
                DEFAULT_REPORT_POLL_TIMEOUT_MILLIS,
                DEFAULT_REPORT_DEADLINE_MILLIS);
    }

    /**
     * Parameterised constructor (package-private) used by tests to shorten the deadline and keep
     * surefire fast when a report never arrives (FAIL scenario). NOT a Micronaut injection point —
     * the {@code @Singleton} resolves via the three-argument public constructor.
     */
    CoaCodDemoRunner(BusinessMessageProducer producer,
                     BusinessMessageConsumer consumer,
                     ReportMessageConsumer reportConsumer,
                     long consumeTimeoutMillis,
                     long reportPollTimeoutMillis,
                     long reportDeadlineMillis) {
        this.producer = producer;
        this.consumer = consumer;
        this.reportConsumer = reportConsumer;
        this.consumeTimeoutMillis = consumeTimeoutMillis;
        this.reportPollTimeoutMillis = reportPollTimeoutMillis;
        this.reportDeadlineMillis = reportDeadlineMillis;
    }

    /**
     * Triggered by the {@code StartupEvent}: runs the demo exactly once. Catches exceptions so that a
     * demo failure (e.g. broker down) is reported as FAIL in the summary — without abruptly tearing
     * down the context or killing the JVM.
     */
    @Override
    public void onApplicationEvent(StartupEvent event) {
        try {
            DemoResult result = runDemo();
            logSummary(result);
        } catch (RuntimeException e) {
            LOG.error("[demo=COA/COD] [result=FAIL] The demo failed with an exception — "
                    + "check that the broker is up (docker compose up -d) and that the admin config "
                    + "(DEV.ADMIN.SVRCONN/admin) is active (-Dmicronaut.environments=demo).", e);
        }
    }

    /**
     * Orchestrates the end-to-end COA/COD flow and returns a {@link DemoResult} with the collected
     * reports and the validation verdict. Exposed (package-private) for unit testing with mocked
     * JMS beans.
     *
     * <p>Order: (a) {@code messageId = producer.send(...)}; (b) {@code consumer.receiveOne(timeout)}
     * (the commit triggers the COD); (c) loop {@code reportConsumer.receiveOneReport(timeout)} under a
     * generous deadline, collecting {@link DeliveryEvent}s until BOTH COA(259) and COD(260) are seen;
     * (d) validate: both feedbacks present and each {@code correlationId == messageId}.</p>
     */
    DemoResult runDemo() {
        LOG.info("[demo=COA/COD] [stage=BANNER] Starting the end-to-end COA/COD demo "
                + "(produce -> consume -> COA/COD) against the local broker.");

        // (a) PRODUCE — the bean already narrates [stage=PRODUCE]; here we only store the messageId.
        String messageId = producer.send(DEMO_BUSINESS_KEY, DEMO_PAYLOAD);
        LOG.info("[demo=COA/COD] [stage=BANNER] Message sent (messageId={}). Consuming to "
                + "trigger the COD...", messageId);

        // (b) CONSUME destructively — the commit (narrated as [stage=COMMIT]) releases the COD.
        String body = consumer.receiveOne(consumeTimeoutMillis);
        if (body == null) {
            LOG.warn("[demo=COA/COD] [stage=BANNER] No business message consumed within the "
                    + "timeout ({} ms) — the COD will not be triggered.", consumeTimeoutMillis);
        }

        // (c) COLLECT reports under a generous deadline, in any order (COA/COD), tolerating
        // null returns (timeout) until BOTH feedbacks are seen.
        CollectedReports collected = collectReports();

        // (d) VALIDATE — reuse the flags already computed by collectReports (m3).
        return validate(messageId, collected);
    }

    /**
     * Deadline-bounded loop that reads the report queue reusing the {@link ReportMessageConsumer}
     * (which already narrates [stage=CLASSIFY/CORRELATE/COA/COD/RECONCILE]). Stops as soon as BOTH
     * COA(259) and COD(260) are seen, or when the deadline expires.
     *
     * <p>Uses {@link System#nanoTime()} for a monotonic deadline (m4); the subtraction-based
     * comparison ({@code nanoTime() - deadlineNanos < 0}) is overflow-safe per the JDK contract.</p>
     */
    private CollectedReports collectReports() {
        List<DeliveryEvent> reports = new ArrayList<>();
        boolean coaSeen = false;
        boolean codSeen = false;

        // m4: nanoTime() is monotonic — immune to wall-clock adjustments (NTP, DST, leap seconds).
        long deadlineNanos = System.nanoTime() + reportDeadlineMillis * 1_000_000L;
        while (System.nanoTime() - deadlineNanos < 0 && !(coaSeen && codSeen)) {
            DeliveryEvent event = reportConsumer.receiveOneReport(reportPollTimeoutMillis);
            if (event == null) {
                // Timeout with no report: keep looping until the deadline.
                continue;
            }
            reports.add(event);
            if (event.feedbackCode() == MQConstants.MQFB_COA) {
                coaSeen = true;
            } else if (event.feedbackCode() == MQConstants.MQFB_COD) {
                codSeen = true;
            }
        }
        // Return the list together with the flags already computed during collection (m3:
        // avoids re-scanning the list in validate() via anyMatch).
        return new CollectedReports(List.copyOf(reports), coaSeen, codSeen);
    }

    /**
     * Carries the result of one {@link #collectReports()} pass: the immutable report list
     * plus the COA/COD seen-flags already computed during the collection loop (m3: no need
     * for validate() to re-derive them via anyMatch).
     */
    private record CollectedReports(
            List<DeliveryEvent> reports,
            boolean coaSeen,
            boolean codSeen
    ) {}

    /**
     * Validates (AC#2) that BOTH reports arrived — COA (feedback {@code MQFB_COA}=259) and COD
     * ({@code MQFB_COD}=260) — and that each correlates to the original {@code messageId}
     * ({@code correlationId == messageId}, default {@code MQRO_COPY_MSG_ID_TO_CORREL_ID}). Uses the
     * {@code MQConstants.MQFB_*} constants, never literals; the feedback was read by the consumer via
     * {@code WMQConstants.JMS_IBM_FEEDBACK}.
     */
    private DemoResult validate(String messageId, CollectedReports collected) {
        // Reuse the flags computed by collectReports — no redundant anyMatch re-scan (m3).
        boolean coaSeen = collected.coaSeen();
        boolean codSeen = collected.codSeen();
        List<DeliveryEvent> reports = collected.reports();

        // Each COA/COD report must correlate to the original messageId (correlId == messageId).
        boolean correlationOk = reports.stream()
                .filter(e -> e.feedbackCode() == MQConstants.MQFB_COA
                        || e.feedbackCode() == MQConstants.MQFB_COD)
                .allMatch(e -> messageId != null && messageId.equals(e.correlationId()));

        boolean passed = coaSeen && codSeen && correlationOk;
        return new DemoResult(passed, messageId, coaSeen, codSeen, correlationOk, List.copyOf(reports));
    }

    /** Prints the final PASS/FAIL summary of the demo (the only output the runner adds to the narration). */
    private void logSummary(DemoResult result) {
        if (result.passed()) {
            LOG.info("[demo=COA/COD] [result=PASS] Flow validated: COA(feedback={})={}, "
                            + "COD(feedback={})={}, correlId==messageId={} (messageId={}). "
                            + "Reports collected={}.",
                    MQConstants.MQFB_COA, result.coaSeen(),
                    MQConstants.MQFB_COD, result.codSeen(),
                    result.correlationOk(), result.messageId(), result.reports().size());
        } else {
            LOG.warn("[demo=COA/COD] [result=FAIL] Validation failed: COA(feedback={})={}, "
                            + "COD(feedback={})={}, correlId==messageId={} (messageId={}). "
                            + "Reports collected={}. Hint: confirm the broker is up + admin config "
                            + "(DEV.ADMIN.SVRCONN) for the context authority that generates the reports.",
                    MQConstants.MQFB_COA, result.coaSeen(),
                    MQConstants.MQFB_COD, result.codSeen(),
                    result.correlationOk(), result.messageId(), result.reports().size());
        }
    }

    /**
     * Demo result: aggregated verdict + individual signals that back the summary and the tests.
     *
     * @param passed        true when COA+COD were seen and both correlate to the messageId.
     * @param messageId     JMSMessageID returned by {@code producer.send(...)} (correlation key).
     * @param coaSeen       whether a COA report arrived (feedback {@code MQFB_COA}=259).
     * @param codSeen       whether a COD report arrived (feedback {@code MQFB_COD}=260).
     * @param correlationOk whether all COA/COD reports have {@code correlationId == messageId}.
     * @param reports       the list of collected reports (immutable copy).
     */
    record DemoResult(
            boolean passed,
            String messageId,
            boolean coaSeen,
            boolean codSeen,
            boolean correlationOk,
            List<DeliveryEvent> reports
    ) {
    }
}
