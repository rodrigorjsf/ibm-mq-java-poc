package com.example.ibmmq.integration;

import com.ibm.msg.client.wmq.WMQConstants;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.jms.MQConnectionFactory;
import com.ibm.mq.testcontainers.MQContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;

import javax.jms.BytesMessage;
import javax.jms.DeliveryMode;
import javax.jms.JMSConsumer;
import javax.jms.JMSContext;
import javax.jms.JMSProducer;
import javax.jms.Message;
import javax.jms.Queue;
import javax.jms.TextMessage;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Group A integration scenario matrix (issue #20) against a real IBM MQ broker.
 *
 * <p><b>Why ONE shared container.</b> The {@link MQContainer} takes ~30-60 s to boot; spinning one up per
 * scenario would be prohibitively slow. The OUTER class owns a single {@code static} container in
 * {@code @BeforeAll}/{@code @AfterAll}; the {@code @Nested} scenario classes all share it.</p>
 *
 * <p><b>Why connect as {@code admin} (not {@code app}).</b> Mirrors {@link CoaCodEndToEndIT}: for the Queue
 * Manager to GENERATE and DELIVER a COA/COD it does a PUT-with-context onto the ReplyToQ, which requires
 * context authority the low-privilege {@code app} user of the developer image lacks (the report would fail
 * {@code MQRC_NOT_AUTHORIZED} = 2035 and dead-letter, leaving the report queue empty). The {@code admin}
 * user holds full context authority. This also lets scenario {@code WithDataPayload} request a
 * {@code WITH_FULL_DATA} report without the {@code +setid/+setall} escalation that #19 deliberately avoided
 * on the production producer (which stays on plain {@code MQRO_COD}).</p>
 *
 * <p><b>Scenario isolation.</b> JUnit 5 does NOT order nested classes or methods. A {@code @BeforeEach}
 * DRAINS the business queue and the report queue (destructive get-all with a short timeout) so each scenario
 * starts from a clean queue state — cross-scenario message bleed is a real failure mode.</p>
 *
 * <p>Named {@code *IT} so failsafe (not surefire) runs it under {@code mvn verify}.</p>
 */
@DisplayName("Matriz de cenarios de integracao Grupo A contra IBM MQ real")
class ScenarioMatrixIT {

    private static final String SECRET = "passw0rd";
    private static final String QUEUE_MANAGER = "QM1";
    // Connect via the ADMIN channel/user to hold context authority for report generation.
    private static final String ADMIN_CHANNEL = "DEV.ADMIN.SVRCONN";
    private static final String ADMIN_USER = "admin";
    // Low-privilege application channel/user — used ONLY by the ReportPutAuthToDlq scenario to reproduce the
    // report-PUT authority gotcha (app holds put+browse on DEV.QUEUE.2 but NOT passid, so its report
    // PUT-with-context fails MQRC_NOT_AUTHORIZED (2035) and the report dead-letters).
    private static final String APP_CHANNEL = "DEV.APP.SVRCONN";
    private static final String APP_USER = "app";

    private static final String BUSINESS_QUEUE = "queue:///DEV.QUEUE.1";
    private static final String REPORT_QUEUE = "queue:///DEV.QUEUE.2";
    // Reading MQMD-bearing report properties (e.g. JMS_IBM_FEEDBACK is always populated, but the
    // report-queue consumer is kept consistent with CoaCodEndToEndIT) requires the mdReadEnabled URI form.
    private static final String REPORT_QUEUE_MD_READ = REPORT_QUEUE + "?mdReadEnabled=true";

    // Plain queue NAMES (no URI scheme) for the runmqsc helpers (CURDEPTH / CLEAR).
    private static final String REPORT_QUEUE_NAME = "DEV.QUEUE.2";
    private static final String DEAD_LETTER_QUEUE_NAME = "DEV.DEAD.LETTER.QUEUE";

    // Scenario-owned queues, defined once in @BeforeAll via runmqsc (see defineScenarioQueues()).
    private static final String POISON_QUEUE_NAME = "SCENARIO.POISON.Q";
    private static final String BACKOUT_QUEUE_NAME = "SCENARIO.BACKOUT.Q";
    private static final String FULL_QUEUE_NAME = "SCENARIO.FULL.Q";
    // BOTHRESH for the poison queue: after this many rollbacks the QMgr requeues the message to BOQNAME.
    private static final int POISON_BOTHRESH = 3;
    // MAXDEPTH for the queue-full scenario: the 3rd PUT must fail MQRC_Q_FULL (2053).
    private static final int FULL_MAX_DEPTH = 2;

    // Extracts the integer N from a runmqsc "CURDEPTH(N)" attribute line.
    private static final Pattern CURDEPTH_PATTERN = Pattern.compile("CURDEPTH\\((\\d+)\\)");

    private static MQContainer mq;

    @BeforeAll
    static void startBroker() throws Exception {
        // The image publishes no bare "9.4.5.0" tag — use the fixpack release -r2.
        mq = new MQContainer("icr.io/ibm-messaging/mq:9.4.5.0-r2")
                .acceptLicense()
                .withQueueManager(QUEUE_MANAGER)
                .withAppPassword(SECRET)     // enables the 'app' user (general use)
                .withAdminPassword(SECRET);  // enables the 'admin' user (used by most scenarios here)
        mq.start();
        // Define the scenario-owned queues (poison/backout/full) once, after the container is up.
        defineScenarioQueues();
    }

    /**
     * Defines the queues that the poison-message and queue-full scenarios depend on, via {@code runmqsc}
     * inside the running container (the dev image only pre-creates DEV.QUEUE.*). Verified live: each DEFINE
     * returns {@code AMQ8006I}. {@code REPLACE} keeps this idempotent across reruns of the same container.
     *
     * <ul>
     *   <li>{@code SCENARIO.POISON.Q} with {@code BOTHRESH(3)} + {@code BOQNAME(SCENARIO.BACKOUT.Q)} — after
     *       3 rollbacks the QMgr requeues the poison message to the backout queue.</li>
     *   <li>{@code SCENARIO.BACKOUT.Q} — the backout target.</li>
     *   <li>{@code SCENARIO.FULL.Q} with {@code MAXDEPTH(2)} — the 3rd PUT must fail MQRC_Q_FULL (2053).</li>
     * </ul>
     */
    private static void defineScenarioQueues() throws Exception {
        runMqsc(
                "DEFINE QLOCAL(" + POISON_QUEUE_NAME + ") BOTHRESH(" + POISON_BOTHRESH
                        + ") BOQNAME(" + BACKOUT_QUEUE_NAME + ") REPLACE",
                "DEFINE QLOCAL(" + BACKOUT_QUEUE_NAME + ") REPLACE",
                "DEFINE QLOCAL(" + FULL_QUEUE_NAME + ") MAXDEPTH(" + FULL_MAX_DEPTH + ") REPLACE");
    }

    /**
     * Runs one or more MQSC verbs through {@code runmqsc QM1} inside the container and asserts a zero exit
     * code. The verbs are newline-joined and piped to {@code runmqsc} via {@code printf}; quoting is avoided
     * by keeping verbs free of shell metacharacters (queue names + numeric attributes only).
     */
    private static Container.ExecResult runMqsc(String... verbs) throws Exception {
        String script = String.join("\n", verbs) + "\n";
        Container.ExecResult result = mq.execInContainer(
                "bash", "-c", "printf '" + script + "' | runmqsc " + QUEUE_MANAGER);
        assertThat(result.getExitCode())
                .as("runmqsc should exit 0 for verbs: %s (stdout=%s, stderr=%s)",
                        String.join(" ; ", verbs), result.getStdout(), result.getStderr())
                .isZero();
        return result;
    }

    /**
     * Reads {@code CURDEPTH} for a local queue via {@code runmqsc DIS QLOCAL(name) CURDEPTH} and parses the
     * integer out of the {@code CURDEPTH(N)} attribute in stdout. Used by the scenarios that assert queue
     * state robustly at the broker level (no JMS MQDLH parsing).
     */
    private static int currentDepth(String queueName) throws Exception {
        Container.ExecResult result = mq.execInContainer(
                "bash", "-c",
                "printf 'DIS QLOCAL(" + queueName + ") CURDEPTH\\n' | runmqsc " + QUEUE_MANAGER);
        assertThat(result.getExitCode())
                .as("DIS QLOCAL(%s) CURDEPTH should exit 0 (stdout=%s, stderr=%s)",
                        queueName, result.getStdout(), result.getStderr())
                .isZero();
        Matcher matcher = CURDEPTH_PATTERN.matcher(result.getStdout());
        assertThat(matcher.find())
                .as("CURDEPTH(N) should appear in runmqsc output for %s: %s", queueName, result.getStdout())
                .isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    /**
     * Polls {@link #currentDepth(String)} until it satisfies {@code minInclusive <= depth} (when
     * {@code atLeast}) or {@code depth == 0} (when not {@code atLeast}), or the bounded budget expires.
     * Returns the last observed depth so the caller asserts on it (depth checks stay in the test, not here).
     */
    private static int awaitDepth(String queueName, int target, boolean atLeast, Duration budget)
            throws Exception {
        long deadline = System.currentTimeMillis() + budget.toMillis();
        int depth = currentDepth(queueName);
        while (System.currentTimeMillis() < deadline
                && !(atLeast ? depth >= target : depth == target)) {
            Thread.sleep(500L);
            depth = currentDepth(queueName);
        }
        return depth;
    }

    @AfterAll
    static void stopBroker() {
        if (mq != null) {
            mq.stop();
        }
    }

    /** Builds the admin-channel client connection factory (MQCSP auth), mirroring {@link CoaCodEndToEndIT}. */
    private static MQConnectionFactory buildConnectionFactory() throws Exception {
        return buildConnectionFactory(ADMIN_CHANNEL, ADMIN_USER);
    }

    /**
     * Builds a client connection factory (MQCSP auth) for the given channel/user. The admin variant holds
     * full context authority (report generation); the {@code app} variant is the low-privilege principal of
     * the developer image (used only by {@code ReportPutAuthToDlq} to reproduce the report-PUT 2035 gotcha).
     */
    private static MQConnectionFactory buildConnectionFactory(String channel, String user) throws Exception {
        MQConnectionFactory cf = new MQConnectionFactory();
        cf.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        cf.setStringProperty(WMQConstants.WMQ_HOST_NAME, mq.getHost());
        cf.setIntProperty(WMQConstants.WMQ_PORT, mq.getPort());
        cf.setStringProperty(WMQConstants.WMQ_CHANNEL, channel);
        cf.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, QUEUE_MANAGER);
        cf.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        cf.setStringProperty(WMQConstants.USERID, user);
        cf.setStringProperty(WMQConstants.PASSWORD, SECRET);
        return cf;
    }

    /**
     * Drains a queue destructively (get-all) until the receive times out. Short timeout keeps the drain
     * fast on an already-empty queue; the first non-null receive resets the idle budget so a populated queue
     * is fully cleared.
     */
    private static void drainQueue(JMSConsumer consumer) {
        while (consumer.receive(500L) != null) {
            // keep consuming until the queue is empty (a timed-out receive returns null)
        }
    }

    /**
     * Shared per-scenario cleanup: destructively drains the business queue and the report queue (via JMS) and
     * CLEARs the dead-letter and scenario queues (via runmqsc, since the QMgr — not the app — populates the
     * DLQ and the backout queue) so each nested scenario starts from a clean state regardless of
     * nested-class/method ordering. Clearing the DLQ is load-bearing for {@code ReportPutAuthToDlq}: a prior
     * scenario's dead-letter must not bleed into its CURDEPTH assertion.
     */
    @BeforeEach
    void drainQueues() throws Exception {
        MQConnectionFactory cf = buildConnectionFactory();
        try (JMSContext ctx = cf.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
            drainQueue(ctx.createConsumer(ctx.createQueue(BUSINESS_QUEUE)));
            // Read the report queue with mdReadEnabled so the drain matches how scenarios consume it.
            drainQueue(ctx.createConsumer(ctx.createQueue(REPORT_QUEUE_MD_READ)));
        }
        // Clear the broker-populated / scenario-owned queues that JMS draining cannot reliably reach.
        runMqsc(
                "CLEAR QLOCAL(" + DEAD_LETTER_QUEUE_NAME + ")",
                "CLEAR QLOCAL(" + POISON_QUEUE_NAME + ")",
                "CLEAR QLOCAL(" + BACKOUT_QUEUE_NAME + ")",
                "CLEAR QLOCAL(" + FULL_QUEUE_NAME + ")");
    }

    // ------------------------------------------------------------------------------------------------
    // Scenario 1 — report persistence INHERITANCE (validated fact; refutes "non-persistent by default").
    // ------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Heranca de persistencia: COA e COD herdam a persistencia do original")
    class PersistenceInheritance {

        @Test
        @DisplayName("Original PERSISTENT com COA+COD -> ambos os relatorios recebidos sao PERSISTENT")
        void persistentOriginalYieldsPersistentReports() throws Exception {
            MQConnectionFactory cf = buildConnectionFactory();

            // ---- 1) Produce a PERSISTENT original requesting COA + COD ----
            try (JMSContext ctx = cf.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
                Queue businessQueue = ctx.createQueue(BUSINESS_QUEUE);
                Queue reportQueue = ctx.createQueue(REPORT_QUEUE);

                TextMessage msg = ctx.createTextMessage("{\"order\":1}");
                msg.setJMSReplyTo(reportQueue);
                msg.setIntProperty(WMQConstants.JMS_IBM_REPORT_COA, MQConstants.MQRO_COA);
                msg.setIntProperty(WMQConstants.JMS_IBM_REPORT_COD, MQConstants.MQRO_COD);

                JMSProducer producer = ctx.createProducer();
                producer.setDeliveryMode(DeliveryMode.PERSISTENT);
                producer.send(businessQueue, msg);
            }

            // ---- 2) Consume + commit the original under a transacted session (releases the COD) ----
            try (JMSContext ctx = cf.createContext(JMSContext.SESSION_TRANSACTED)) {
                JMSConsumer consumer = ctx.createConsumer(ctx.createQueue(BUSINESS_QUEUE));
                Message consumed = consumer.receive(Duration.ofSeconds(15).toMillis());
                assertThat(consumed).as("the business message should have been consumed").isNotNull();
                ctx.commit();
            }

            // ---- 3) Collect both reports and assert each inherited PERSISTENT delivery mode ----
            boolean coaPersistent = false;
            boolean codPersistent = false;
            boolean coaSeen = false;
            boolean codSeen = false;

            try (JMSContext ctx = cf.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
                JMSConsumer reportConsumer = ctx.createConsumer(ctx.createQueue(REPORT_QUEUE_MD_READ));

                long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
                while (System.currentTimeMillis() < deadline && !(coaSeen && codSeen)) {
                    Message report = reportConsumer.receive(5_000L);
                    if (report == null) {
                        continue;
                    }
                    int feedback = report.getIntProperty(WMQConstants.JMS_IBM_FEEDBACK);
                    if (feedback == MQConstants.MQFB_COA) {
                        coaSeen = true;
                        coaPersistent = report.getJMSDeliveryMode() == DeliveryMode.PERSISTENT;
                    } else if (feedback == MQConstants.MQFB_COD) {
                        codSeen = true;
                        codPersistent = report.getJMSDeliveryMode() == DeliveryMode.PERSISTENT;
                    }
                }
            }

            assertThat(coaSeen).as("a COA report (feedback MQFB_COA=259) should have arrived").isTrue();
            assertThat(codSeen).as("a COD report (feedback MQFB_COD=260) should have arrived").isTrue();
            assertThat(coaPersistent)
                    .as("the COA report must inherit the original's PERSISTENT delivery mode")
                    .isTrue();
            assertThat(codPersistent)
                    .as("the COD report must inherit the original's PERSISTENT delivery mode")
                    .isTrue();
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Scenario 2 — syncpoint COA/COD timing: COA at PUT, COD only after the transacted commit.
    // ------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Timing COA/COD com syncpoint: COA no PUT, COD so apos o commit")
    class SyncpointCoaCodTiming {

        @Test
        @DisplayName("Antes de consumir: COA presente, COD ausente; apos consumir+commit: COD aparece")
        void codOnlyAppearsAfterTransactedCommit() throws Exception {
            MQConnectionFactory cf = buildConnectionFactory();

            // ---- 1) Produce a PERSISTENT original requesting COA + COD ----
            try (JMSContext ctx = cf.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
                Queue businessQueue = ctx.createQueue(BUSINESS_QUEUE);
                Queue reportQueue = ctx.createQueue(REPORT_QUEUE);

                TextMessage msg = ctx.createTextMessage("{\"order\":2}");
                msg.setJMSReplyTo(reportQueue);
                msg.setIntProperty(WMQConstants.JMS_IBM_REPORT_COA, MQConstants.MQRO_COA);
                msg.setIntProperty(WMQConstants.JMS_IBM_REPORT_COD, MQConstants.MQRO_COD);

                JMSProducer producer = ctx.createProducer();
                producer.setDeliveryMode(DeliveryMode.PERSISTENT);
                producer.send(businessQueue, msg);
            }

            // ---- 2) BEFORE consuming: poll the report queue and assert a COA is there but NO COD yet ----
            // The COA is generated at PUT time; the COD is only generated after the consumer commits. We poll
            // until the COA arrives (bounded), then a final bounded sweep proves no COD has been generated.
            boolean coaSeenBeforeConsume = false;
            boolean codSeenBeforeConsume = false;
            try (JMSContext ctx = cf.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
                JMSConsumer reportConsumer = ctx.createConsumer(ctx.createQueue(REPORT_QUEUE_MD_READ));

                long deadline = System.currentTimeMillis() + Duration.ofSeconds(20).toMillis();
                while (System.currentTimeMillis() < deadline && !coaSeenBeforeConsume) {
                    Message report = reportConsumer.receive(2_000L);
                    if (report == null) {
                        continue;
                    }
                    int feedback = report.getIntProperty(WMQConstants.JMS_IBM_FEEDBACK);
                    if (feedback == MQConstants.MQFB_COA) {
                        coaSeenBeforeConsume = true;
                    } else if (feedback == MQConstants.MQFB_COD) {
                        // A COD before the commit would refute the timing model — fail loudly.
                        codSeenBeforeConsume = true;
                    }
                }
                // Drain a short additional window to confirm no COD has appeared yet (the original is still
                // sitting on DEV.QUEUE.1, uncommitted-consumed = never consumed, so no COD can exist).
                Message extra = reportConsumer.receive(1_000L);
                while (extra != null) {
                    if (extra.getIntProperty(WMQConstants.JMS_IBM_FEEDBACK) == MQConstants.MQFB_COD) {
                        codSeenBeforeConsume = true;
                    }
                    extra = reportConsumer.receive(1_000L);
                }
            }

            assertThat(coaSeenBeforeConsume)
                    .as("the COA (feedback 259) must be on the report queue BEFORE the original is consumed")
                    .isTrue();
            assertThat(codSeenBeforeConsume)
                    .as("NO COD (feedback 260) may exist before the consumer's transacted commit")
                    .isFalse();

            // ---- 3) Consume the original under a SESSION_TRANSACTED session and commit (releases the COD) --
            try (JMSContext ctx = cf.createContext(JMSContext.SESSION_TRANSACTED)) {
                JMSConsumer consumer = ctx.createConsumer(ctx.createQueue(BUSINESS_QUEUE));
                Message consumed = consumer.receive(Duration.ofSeconds(15).toMillis());
                assertThat(consumed).as("the business message should have been consumed").isNotNull();
                ctx.commit();
            }

            // ---- 4) AFTER the commit: a COD (feedback 260) appears on the report queue ----
            boolean codSeenAfterConsume = false;
            try (JMSContext ctx = cf.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
                JMSConsumer reportConsumer = ctx.createConsumer(ctx.createQueue(REPORT_QUEUE_MD_READ));

                long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
                while (System.currentTimeMillis() < deadline && !codSeenAfterConsume) {
                    Message report = reportConsumer.receive(5_000L);
                    if (report == null) {
                        continue;
                    }
                    if (report.getIntProperty(WMQConstants.JMS_IBM_FEEDBACK) == MQConstants.MQFB_COD) {
                        codSeenAfterConsume = true;
                    }
                }
            }

            assertThat(codSeenAfterConsume)
                    .as("a COD (feedback 260) must appear AFTER the consumer's transacted commit")
                    .isTrue();
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Scenario 3 — COD WITH FULL DATA: the report carries the original message body.
    // ------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("COD com dados: o relatorio carrega o corpo da mensagem original")
    class WithDataPayload {

        @Test
        @DisplayName("MQRO_COD_WITH_FULL_DATA -> o corpo do COD recebido contem o payload original")
        void codWithFullDataCarriesOriginalPayload() throws Exception {
            MQConnectionFactory cf = buildConnectionFactory();
            String originalText = "{\"order\":3,\"payload\":\"with-full-data\"}";

            // ---- 1) Produce a PERSISTENT original requesting a COD that CARRIES the original data ----
            // The with-data flag is set on a message the TEST builds (the production BusinessMessageProducer
            // stays on plain MQRO_COD — #19 deliberately avoided the +setid/+setall escalation; the admin
            // connection here holds full context authority so it can request WITH_FULL_DATA safely).
            try (JMSContext ctx = cf.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
                Queue businessQueue = ctx.createQueue(BUSINESS_QUEUE);
                Queue reportQueue = ctx.createQueue(REPORT_QUEUE);

                TextMessage msg = ctx.createTextMessage(originalText);
                msg.setJMSReplyTo(reportQueue);
                msg.setIntProperty(WMQConstants.JMS_IBM_REPORT_COD,
                        MQConstants.MQRO_COD_WITH_FULL_DATA); // 14336 (verified bytecode value)

                JMSProducer producer = ctx.createProducer();
                producer.setDeliveryMode(DeliveryMode.PERSISTENT);
                producer.send(businessQueue, msg);
            }

            // ---- 2) Consume + commit the original (releases the COD-with-data) ----
            try (JMSContext ctx = cf.createContext(JMSContext.SESSION_TRANSACTED)) {
                JMSConsumer consumer = ctx.createConsumer(ctx.createQueue(BUSINESS_QUEUE));
                Message consumed = consumer.receive(Duration.ofSeconds(15).toMillis());
                assertThat(consumed).as("the business message should have been consumed").isNotNull();
                ctx.commit();
            }

            // ---- 3) Read the COD report and assert its body carries the original payload ----
            String recoveredBody = null;
            try (JMSContext ctx = cf.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
                JMSConsumer reportConsumer = ctx.createConsumer(ctx.createQueue(REPORT_QUEUE_MD_READ));

                long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
                while (System.currentTimeMillis() < deadline && recoveredBody == null) {
                    Message report = reportConsumer.receive(5_000L);
                    if (report == null) {
                        continue;
                    }
                    if (report.getIntProperty(WMQConstants.JMS_IBM_FEEDBACK) != MQConstants.MQFB_COD) {
                        continue;
                    }
                    recoveredBody = extractBody(report);
                }
            }

            assertThat(recoveredBody)
                    .as("the COD-with-full-data report body should be recoverable")
                    .isNotNull();
            // The report carries the FULL original body. A TextMessage round-trips as the same text; a
            // BytesMessage carries the original bytes (the JMS body without the RFH2 area is the payload),
            // so the original text must be present as a prefix of / equal to the recovered body.
            assertThat(recoveredBody)
                    .as("the COD-with-full-data report must carry the original message payload")
                    .contains(originalText);
        }

        /**
         * Recovers the report body as a String regardless of whether the QMgr delivered the with-data
         * report as a {@link TextMessage} or a {@link BytesMessage}. Returns {@code null} when the body is
         * empty / unrecoverable.
         */
        private String extractBody(Message report) throws Exception {
            if (report instanceof TextMessage textMessage) {
                return textMessage.getText();
            }
            if (report instanceof BytesMessage bytesMessage) {
                long length = bytesMessage.getBodyLength();
                if (length <= 0) {
                    return null;
                }
                byte[] bytes = new byte[(int) length];
                bytesMessage.readBytes(bytes);
                return new String(bytes, StandardCharsets.UTF_8);
            }
            // Fallback: some report messages expose their payload via getBody(String.class).
            try {
                return report.getBody(String.class);
            } catch (Exception e) {
                return null;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Scenario 4 — report-PUT authority gotcha: app lacks passid -> report dead-letters (CURDEPTH proof).
    // ------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Autoridade do PUT de relatorio: app sem passid -> relatorio vai para a DLQ")
    class ReportPutAuthToDlq {

        @Test
        @DisplayName("app produz com COA -> DEV.QUEUE.2 fica vazia e DEV.DEAD.LETTER.QUEUE recebe o relatorio")
        void reportFromLowPrivAppDeadLetters() throws Exception {
            // The low-privilege 'app' user holds put+browse on DEV.QUEUE.2 but NOT passid (live-verified on
            // icr.io/ibm-messaging/mq:9.4.5.0-r2). When app produces an original requesting a COA with
            // JMSReplyTo=DEV.QUEUE.2, the QMgr's report PUT-with-context onto the ReplyToQ fails
            // MQRC_NOT_AUTHORIZED (2035) and the report is routed to DEV.DEAD.LETTER.QUEUE. The COA is
            // generated at PUT time, so the failure occurs immediately — no consume step is needed.
            MQConnectionFactory appCf = buildConnectionFactory(APP_CHANNEL, APP_USER);

            try (JMSContext ctx = appCf.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
                Queue businessQueue = ctx.createQueue(BUSINESS_QUEUE);
                Queue reportQueue = ctx.createQueue(REPORT_QUEUE);

                TextMessage msg = ctx.createTextMessage("{\"order\":4,\"auth\":\"dlq\"}");
                msg.setJMSReplyTo(reportQueue);
                // COA only: it fires at PUT time, so the unauthorized report PUT (and dead-letter) happens
                // without consuming the original. PERSISTENT so the report inherits persistence and is
                // dead-lettered (not silently discarded) when its ReplyToQ PUT is refused.
                msg.setIntProperty(WMQConstants.JMS_IBM_REPORT_COA, MQConstants.MQRO_COA);

                JMSProducer producer = ctx.createProducer();
                producer.setDeliveryMode(DeliveryMode.PERSISTENT);
                producer.send(businessQueue, msg);
            }

            // The unauthorized report PUT + dead-lettering is async — poll the DLQ depth with a bounded budget.
            int dlqDepth = awaitDepth(DEAD_LETTER_QUEUE_NAME, 1, true, Duration.ofSeconds(20));

            // The report never reached the ReplyToQ: DEV.QUEUE.2 stays empty.
            assertThat(currentDepth(REPORT_QUEUE_NAME))
                    .as("no report should reach the ReplyToQ (DEV.QUEUE.2) when the PUT-with-context is refused")
                    .isZero();
            // The report dead-lettered: DEV.DEAD.LETTER.QUEUE holds at least one message.
            assertThat(dlqDepth)
                    .as("the refused report PUT should dead-letter to DEV.DEAD.LETTER.QUEUE (CURDEPTH >= 1)")
                    .isGreaterThanOrEqualTo(1);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Scenario 5 — poison message backout: after BOTHRESH rollbacks the QMgr requeues to BOQNAME.
    // ------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Mensagem envenenada: apos BOTHRESH rollbacks a mensagem vai para a fila de backout")
    class PoisonMessageBackout {

        @Test
        @DisplayName("3 rollbacks em SCENARIO.POISON.Q -> mensagem em SCENARIO.BACKOUT.Q; JMSXDeliveryCount sobe")
        void poisonMessageMovesToBackoutQueueAfterThreshold() throws Exception {
            MQConnectionFactory cf = buildConnectionFactory();

            // ---- 1) PUT one poison message onto SCENARIO.POISON.Q (admin holds full authority) ----
            try (JMSContext ctx = cf.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
                Queue poisonQueue = ctx.createQueue("queue:///" + POISON_QUEUE_NAME);
                TextMessage msg = ctx.createTextMessage("{\"order\":5,\"poison\":true}");
                JMSProducer producer = ctx.createProducer();
                producer.setDeliveryMode(DeliveryMode.PERSISTENT);
                producer.send(poisonQueue, msg);
            }

            // ---- 2) Receive + rollback in a bounded loop under a SESSION_TRANSACTED context ----
            // BackoutCount lives on the MQMD; each rollback redelivers the same message with an incremented
            // JMSXDeliveryCount. After BOTHRESH rollbacks the QMgr requeues the message to BOQNAME, so the
            // next receive() returns null. We commit (never rollback) at the end: the requeue PUT may ride
            // the current UoW, and commit is safe whether or not it does.
            int maxDeliveryCountSeen = 0;
            int previousDeliveryCount = 0;
            try (JMSContext ctx = cf.createContext(JMSContext.SESSION_TRANSACTED)) {
                JMSConsumer consumer = ctx.createConsumer(ctx.createQueue("queue:///" + POISON_QUEUE_NAME));
                // Bound the loop generously above BOTHRESH so it always terminates.
                for (int attempt = 0; attempt < POISON_BOTHRESH + 2; attempt++) {
                    Message redelivered = consumer.receive(Duration.ofSeconds(10).toMillis());
                    if (redelivered == null) {
                        // The QMgr has requeued the poison message to BOQNAME — it is gone from POISON.Q.
                        break;
                    }
                    int deliveryCount = redelivered.getIntProperty("JMSXDeliveryCount");
                    assertThat(deliveryCount)
                            .as("JMSXDeliveryCount must increase across redeliveries (was %s)", previousDeliveryCount)
                            .isGreaterThan(previousDeliveryCount);
                    previousDeliveryCount = deliveryCount;
                    maxDeliveryCountSeen = Math.max(maxDeliveryCountSeen, deliveryCount);
                    ctx.rollback(); // backs out the message; the QMgr increments BackoutCount and redelivers
                }
                ctx.commit(); // never rollback — commit is safe whether or not the requeue rides this UoW
            }

            // ---- 3) Assert: backout queue has the message, poison queue is empty, delivery count climbed ----
            int backoutDepth = awaitDepth(BACKOUT_QUEUE_NAME, 1, true, Duration.ofSeconds(15));
            assertThat(backoutDepth)
                    .as("the poison message should have been requeued to SCENARIO.BACKOUT.Q (CURDEPTH >= 1)")
                    .isGreaterThanOrEqualTo(1);
            assertThat(currentDepth(POISON_QUEUE_NAME))
                    .as("SCENARIO.POISON.Q should be empty after the message moved to the backout queue")
                    .isZero();
            assertThat(maxDeliveryCountSeen)
                    .as("JMSXDeliveryCount should have climbed across the redeliveries (>= 2 observed)")
                    .isGreaterThanOrEqualTo(2);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Scenario 6 — queue full: the PUT past MAXDEPTH fails MQRC_Q_FULL (2053).
    // ------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Fila cheia: o PUT alem de MAXDEPTH falha com MQRC_Q_FULL (2053)")
    class QueueFull {

        @Test
        @DisplayName("MAXDEPTH(2): 2 PUTs ok, o 3o PUT lanca com a razao 2053 (MQRC_Q_FULL)")
        void thirdPutOnMaxDepth2QueueFailsWithQueueFull() throws Exception {
            MQConnectionFactory cf = buildConnectionFactory();

            // A non-transacted AUTO_ACKNOWLEDGE context makes the 3rd send() throw SYNCHRONOUSLY (under a
            // transacted session the queue-full could defer to commit). The JMS 2.0 simplified API's
            // JMSProducer.send() throws javax.jms.JMSRuntimeException (NOT JMSException — it does not extend
            // it and has no getLinkedException). We detect MQRC_Q_FULL (2053) by walking the full cause chain
            // to the linked MQException via the stack trace, tolerant of how 2053 surfaces (error code text).
            try (JMSContext ctx = cf.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
                Queue fullQueue = ctx.createQueue("queue:///" + FULL_QUEUE_NAME);
                JMSProducer producer = ctx.createProducer();
                producer.setDeliveryMode(DeliveryMode.PERSISTENT);

                // Fill the queue to MAXDEPTH(2).
                producer.send(fullQueue, ctx.createTextMessage("{\"order\":6,\"fill\":1}"));
                producer.send(fullQueue, ctx.createTextMessage("{\"order\":6,\"fill\":2}"));

                // The 3rd PUT must fail with MQRC_Q_FULL (2053).
                TextMessage overflow = ctx.createTextMessage("{\"order\":6,\"fill\":3}");
                assertThatThrownBy(() -> producer.send(fullQueue, overflow))
                        .as("the 3rd PUT onto a MAXDEPTH(2) queue must fail with MQRC_Q_FULL (2053)")
                        .hasStackTraceContaining("2053");
            }

            // The queue is still at MAXDEPTH (the overflow PUT did not land).
            assertThat(currentDepth(FULL_QUEUE_NAME))
                    .as("SCENARIO.FULL.Q should hold exactly MAXDEPTH(2) messages (the 3rd PUT was refused)")
                    .isEqualTo(FULL_MAX_DEPTH);
        }
    }
}
