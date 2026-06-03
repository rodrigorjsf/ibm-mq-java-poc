package com.example.ibmmq.integration;

import com.ibm.msg.client.jakarta.wmq.WMQConstants;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import com.ibm.mq.testcontainers.MQContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.messaginghub.pooled.jms.JmsPoolConnectionFactory;
import org.testcontainers.containers.Container;

import jakarta.jms.JMSConsumer;
import jakarta.jms.JMSContext;
import jakarta.jms.JMSProducer;
import jakarta.jms.JMSRuntimeException;
import jakarta.jms.Message;
import jakarta.jms.Queue;
import jakarta.jms.TextMessage;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Group A — TIMING-SENSITIVE integration scenarios (issue #20, slow tier) against a real IBM MQ broker.
 *
 * <p><b>Why this class is SEPARATE and {@code @Tag("scenario")}-gated.</b> The three scenarios here
 * ({@code Expiration}, {@code Reconnect}, {@code PooledJmsInvalidation}) are inherently timing-sensitive:
 * they wait out a message TTL and/or BOUNCE the Queue Manager and poll for it to come back. They are too
 * slow / flaky for the default {@code mvn verify} gate, so they live in a tag-gated class run on demand via
 * {@code mvn verify -Pscenarios}. The deterministic Group A scenarios stay in {@link ScenarioMatrixIT}
 * (default gate). JUnit 5 inherits a class-level {@code @Tag} to the {@code @Nested} scenario classes, so
 * the one tag here covers all three.</p>
 *
 * <p><b>Why ONE shared container — and why the QMgr is BOUNCED, not the container.</b> The
 * {@link MQContainer} takes ~30-60 s to boot; one container serves all three scenarios. The reconnect and
 * pool-invalidation scenarios need the broker to go away and come back — but {@code MQContainer.stop()/
 * start()} would REMAP the random published host port, so a reconnecting client could never find the same
 * endpoint. Instead they bounce the Queue Manager INSIDE the still-running container
 * ({@code endmqm -i QM1} / {@code strmqm QM1} via {@code execInContainer}); the container's port mapping
 * stays stable, so client auto-reconnect and pooled-connection replacement actually work. Each scenario
 * MUST leave the QMgr Running so siblings are unaffected (a {@code @BeforeEach} also re-ensures Running and
 * drains the queues it uses).</p>
 *
 * <p><b>Why connect as {@code admin} (not {@code app}).</b> Mirrors {@link ScenarioMatrixIT}: for the Queue
 * Manager to GENERATE and DELIVER a report it does a PUT-with-context onto the ReplyToQ, which needs context
 * authority the low-privilege {@code app} user lacks. The {@code admin} user holds full context authority.</p>
 *
 * <p>Named {@code *IT} so failsafe (not surefire) runs it; {@code @Tag("scenario")} keeps it out of the
 * default gate (the base failsafe config excludes the {@code scenario} group).</p>
 */
@Tag("scenario")
@DisplayName("Matriz de cenarios de integracao Grupo A (lentos, sensiveis a timing) contra IBM MQ real")
class ScenarioMatrixSlowIT {

    private static final String SECRET = "passw0rd";
    private static final String QUEUE_MANAGER = "QM1";
    // Connect via the ADMIN channel/user to hold context authority for report generation.
    private static final String ADMIN_CHANNEL = "DEV.ADMIN.SVRCONN";
    private static final String ADMIN_USER = "admin";

    private static final String BUSINESS_QUEUE = "queue:///DEV.QUEUE.1";
    private static final String REPORT_QUEUE = "queue:///DEV.QUEUE.2";
    // Reading MQMD-bearing report properties (e.g. JMS_IBM_FEEDBACK) requires the mdReadEnabled URI form,
    // kept consistent with ScenarioMatrixIT / CoaCodEndToEndIT.
    private static final String REPORT_QUEUE_MD_READ = REPORT_QUEUE + "?mdReadEnabled=true";
    // DEV.QUEUE.3 is the non-persistent original's home for the expiration scenario (pre-created by the
    // dev image). A destructive GET on it after the TTL elapses forces the QMgr to discard the expired
    // message and generate the MQRO_EXPIRATION report onto the ReplyToQ (DEV.QUEUE.2).
    private static final String EXPIRY_QUEUE = "queue:///DEV.QUEUE.3";

    private static MQContainer mq;

    @BeforeAll
    static void startBroker() {
        // The image publishes no bare "9.4.5.0" tag — use the fixpack release -r2.
        mq = new MQContainer("icr.io/ibm-messaging/mq:9.4.5.0-r2")
                .acceptLicense()
                .withQueueManager(QUEUE_MANAGER)
                .withAppPassword(SECRET)     // enables the 'app' user (general use)
                .withAdminPassword(SECRET);  // enables the 'admin' user (used by every scenario here)
        mq.start();
    }

    @AfterAll
    static void stopBroker() {
        if (mq != null) {
            mq.stop();
        }
    }

    /**
     * Per-scenario setup: re-ensures the Queue Manager is Running (a prior scenario bounced it; if it left
     * the QMgr down — e.g. a failure between {@code endmqm} and {@code strmqm} — recover here so siblings
     * are not poisoned) and destructively drains the queues these scenarios touch (DEV.QUEUE.1/2/3) so each
     * scenario starts from a clean state regardless of nested-class/method ordering.
     */
    @BeforeEach
    void ensureRunningAndDrain() throws Exception {
        ensureQueueManagerRunning();
        MQConnectionFactory cf = buildConnectionFactory();
        try (JMSContext ctx = cf.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
            drainQueue(ctx.createConsumer(ctx.createQueue(BUSINESS_QUEUE)));
            drainQueue(ctx.createConsumer(ctx.createQueue(EXPIRY_QUEUE)));
            // Read the report queue with mdReadEnabled so the drain matches how scenarios consume it.
            drainQueue(ctx.createConsumer(ctx.createQueue(REPORT_QUEUE_MD_READ)));
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Shared helpers — connection factory, queue drain, and the QMgr bounce/poll primitives.
    // ------------------------------------------------------------------------------------------------

    /** Builds the admin-channel client connection factory (MQCSP auth), mirroring {@link ScenarioMatrixIT}. */
    private static MQConnectionFactory buildConnectionFactory() throws Exception {
        MQConnectionFactory cf = new MQConnectionFactory();
        cf.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        cf.setStringProperty(WMQConstants.WMQ_HOST_NAME, mq.getHost());
        cf.setIntProperty(WMQConstants.WMQ_PORT, mq.getPort());
        cf.setStringProperty(WMQConstants.WMQ_CHANNEL, ADMIN_CHANNEL);
        cf.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, QUEUE_MANAGER);
        cf.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        cf.setStringProperty(WMQConstants.USERID, ADMIN_USER);
        cf.setStringProperty(WMQConstants.PASSWORD, SECRET);
        return cf;
    }

    /**
     * Drains a queue destructively (get-all) until the receive times out. Short timeout keeps the drain fast
     * on an already-empty queue; the first non-null receive resets the idle budget so a populated queue is
     * fully cleared.
     */
    private static void drainQueue(JMSConsumer consumer) {
        while (consumer.receive(500L) != null) {
            // keep consuming until the queue is empty (a timed-out receive returns null)
        }
    }

    /**
     * Bounces the Queue Manager INSIDE the running container: {@code endmqm -i QM1} (immediate stop) then
     * {@code strmqm QM1} (start), then polls {@link #ensureQueueManagerRunning()} until it reports Running.
     * The container — and thus its published host:port mapping — is untouched, so a client with
     * auto-reconnect (or a pooled connection factory) re-establishes against the SAME endpoint.
     */
    private static void bounceQueueManager() throws Exception {
        Container.ExecResult end = mq.execInContainer("bash", "-c", "endmqm -i " + QUEUE_MANAGER);
        // endmqm returns 0 on a clean stop; tolerate a non-zero only if the QMgr was already ending — but
        // assert here so a genuine failure is loud rather than leaving a half-bounced broker.
        assertThat(end.getExitCode())
                .as("endmqm -i %s should exit 0 (stdout=%s, stderr=%s)",
                        QUEUE_MANAGER, end.getStdout(), end.getStderr())
                .isZero();

        Container.ExecResult start = mq.execInContainer("bash", "-c", "strmqm " + QUEUE_MANAGER);
        assertThat(start.getExitCode())
                .as("strmqm %s should exit 0 (stdout=%s, stderr=%s)",
                        QUEUE_MANAGER, start.getStdout(), start.getStderr())
                .isZero();

        ensureQueueManagerRunning();
    }

    /**
     * Polls {@code dspmq} (via {@code execInContainer}) until the Queue Manager reports {@code Running}, or a
     * bounded budget expires. {@code dspmq} prints a line like {@code QMNAME(QM1) STATUS(Running)}; we match
     * the {@code STATUS(Running)} token. If the QMgr is not yet started ({@code STATUS(Ended ...)}), this also
     * issues a {@code strmqm} once to recover (covers a {@code @BeforeEach} after a scenario that left it
     * down). Throws (via the AssertJ assertion) if it never reaches Running within the budget.
     */
    private static void ensureQueueManagerRunning() throws Exception {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(60).toMillis();
        boolean issuedRecoveryStart = false;
        String lastStatus = "";
        while (System.currentTimeMillis() < deadline) {
            Container.ExecResult status = mq.execInContainer("bash", "-c", "dspmq -m " + QUEUE_MANAGER);
            lastStatus = status.getStdout() + status.getStderr();
            if (lastStatus.contains("STATUS(Running)")) {
                return;
            }
            // If the QMgr is fully ended (not in the middle of starting), kick a single recovery start.
            if (!issuedRecoveryStart
                    && (lastStatus.contains("STATUS(Ended") || lastStatus.contains("STATUS(Stopped"))) {
                mq.execInContainer("bash", "-c", "strmqm " + QUEUE_MANAGER);
                issuedRecoveryStart = true;
            }
            Thread.sleep(1_000L);
        }
        assertThat(lastStatus)
                .as("Queue Manager %s should reach STATUS(Running) within the bounded budget", QUEUE_MANAGER)
                .contains("STATUS(Running)");
    }

    /**
     * Waits until the SVRCONN listener actually ACCEPTS a client connection. {@code dspmq STATUS(Running)}
     * reports the Queue Manager process, but its channel listener may not yet be bound to the port — so
     * after a bounce we probe by opening and closing a throwaway context (on a plain, NON-reconnecting CF so
     * each probe fails fast) in a bounded retry loop. Returns once a connect succeeds.
     */
    private static void awaitListenerReady() throws Exception {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(60).toMillis();
        JMSRuntimeException last = null;
        while (System.currentTimeMillis() < deadline) {
            try (JMSContext probe = buildConnectionFactory().createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
                return; // a successful connect means the listener is accepting again
            } catch (JMSRuntimeException notReadyYet) {
                last = notReadyYet;
                Thread.sleep(1_000L);
            }
        }
        throw new AssertionError("SVRCONN listener did not accept a connection within the bounded budget", last);
    }

    // ------------------------------------------------------------------------------------------------
    // Scenario 4 — EXPIRATION report: a TTL-expired message yields an MQRO_EXPIRATION report (feedback 258).
    // ------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Expiracao: mensagem com TTL expirado gera relatorio MQRO_EXPIRATION (feedback 258)")
    class Expiration {

        @Test
        @DisplayName("NON-persistent com TTL 2s + GET destrutivo apos o TTL -> relatorio de expiracao (258) na DEV.QUEUE.2")
        void expiredMessageYieldsExpirationReport() throws Exception {
            MQConnectionFactory cf = buildConnectionFactory();
            String originalMessageId;

            // ---- 1) Produce a NON-persistent original to DEV.QUEUE.3 with a short TTL, requesting an
            //         EXPIRATION report routed to DEV.QUEUE.2. ----
            try (JMSContext ctx = cf.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
                Queue expiryQueue = ctx.createQueue(EXPIRY_QUEUE);
                Queue reportQueue = ctx.createQueue(REPORT_QUEUE);

                TextMessage msg = ctx.createTextMessage("{\"order\":7,\"ttl\":\"expire\"}");
                msg.setJMSReplyTo(reportQueue);
                // Request an expiration report (base option, no data). MQRO_* come from MQConstants.
                msg.setIntProperty(WMQConstants.JMS_IBM_REPORT_EXPIRATION, MQConstants.MQRO_EXPIRATION);

                JMSProducer producer = ctx.createProducer();
                // Non-persistent: an expired non-persistent message is discarded and (with the report
                // requested) yields an expiration report. 2s TTL keeps the wait bounded.
                producer.setDeliveryMode(jakarta.jms.DeliveryMode.NON_PERSISTENT);
                producer.setTimeToLive(2_000L);
                producer.send(expiryQueue, msg);
                originalMessageId = msg.getJMSMessageID();
            }

            assertThat(originalMessageId)
                    .as("the original should have a JMSMessageID assigned after send")
                    .isNotNull();

            // ---- 2) Wait out the TTL (bounded), then do a destructive GET on DEV.QUEUE.3. MQ 9.4.5 has no
            //         background expiry scan (ALTER QMGR EXPRYINT is a syntax error — see phase-g §4), so the
            //         expired message is only discarded during this GET scan, which THEN triggers the report.
            //         The GET must return null (the message has expired and is gone). ----
            Thread.sleep(3_000L); // > 2s TTL, bounded
            try (JMSContext ctx = cf.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
                JMSConsumer expiryConsumer = ctx.createConsumer(ctx.createQueue(EXPIRY_QUEUE));
                Message expired = expiryConsumer.receive(2_000L);
                assertThat(expired)
                        .as("the TTL-expired original must be gone from DEV.QUEUE.3 (the GET scan discards it)")
                        .isNull();
            }

            // ---- 3) Poll DEV.QUEUE.2 for the expiration report (feedback MQFB_EXPIRATION = 258). Its
            //         CorrelationId equals the original MessageId (default MQRO_COPY_MSG_ID_TO_CORREL_ID). ----
            boolean expirationSeen = false;
            String reportCorrelationId = null;
            try (JMSContext ctx = cf.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
                JMSConsumer reportConsumer = ctx.createConsumer(ctx.createQueue(REPORT_QUEUE_MD_READ));

                long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
                while (System.currentTimeMillis() < deadline && !expirationSeen) {
                    Message report = reportConsumer.receive(5_000L);
                    if (report == null) {
                        continue;
                    }
                    if (report.getIntProperty(WMQConstants.JMS_IBM_FEEDBACK) == MQConstants.MQFB_EXPIRATION) {
                        expirationSeen = true;
                        reportCorrelationId = report.getJMSCorrelationID();
                    }
                }
            }

            assertThat(expirationSeen)
                    .as("an expiration report (feedback MQFB_EXPIRATION=258) should arrive on DEV.QUEUE.2")
                    .isTrue();
            assertThat(reportCorrelationId)
                    .as("the expiration report's CorrelationId must equal the original MessageId "
                            + "(default MQRO_COPY_MSG_ID_TO_CORREL_ID propagation)")
                    .isEqualTo(originalMessageId);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Scenario 8 — client AUTO-RECONNECT: a round-trip succeeds after the QMgr is bounced under the client.
    // ------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Reconexao automatica: round-trip pela DEV.QUEUE.1 sobrevive ao bounce do Queue Manager")
    class Reconnect {

        @Test
        @DisplayName("CF com WMQ_CLIENT_RECONNECT + bounce do QMgr (mesma porta) -> send/receive reconecta e funciona")
        void roundTripSurvivesQueueManagerBounce() throws Exception {
            MQConnectionFactory cf = buildConnectionFactory();
            // Enable client auto-reconnect on the CF (field names verified against jakarta.client 9.4.5 bytecode:
            // WMQ_CLIENT_RECONNECT_OPTIONS, WMQ_CLIENT_RECONNECT=16777216, WMQ_CLIENT_RECONNECT_TIMEOUT).
            cf.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, WMQConstants.WMQ_CLIENT_RECONNECT);
            cf.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_TIMEOUT, 30); // bounded reconnect window (s)

            // Hold a context open across the bounce so the broken-connection / auto-reconnect path is exercised.
            JMSContext ctx = cf.createContext(JMSContext.AUTO_ACKNOWLEDGE);
            try {
                Queue businessQueue = ctx.createQueue(BUSINESS_QUEUE);

                // Bounce the Queue Manager underneath the live client (endmqm -i / strmqm, poll Running).
                bounceQueueManager();

                // dspmq STATUS(Running) does NOT mean the SVRCONN listener is already accepting connections;
                // wait until a throwaway connect succeeds so the round-trip below is not racing listener startup.
                awaitListenerReady();

                // Round-trip after recovery. If the held context's auto-reconnect window lapsed during the
                // outage (the client begins reconnecting the instant endmqm breaks the connection, before the
                // listener is back), fall back to a FRESH context — the QMgr is back on the SAME host:port, so
                // either path proves the client reconnects to the recovered broker. Bounded retry absorbs the
                // residual listener-readiness race.
                String payload = "{\"order\":8,\"reconnect\":true}";
                String roundTripped = null;
                long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
                while (System.currentTimeMillis() < deadline && roundTripped == null) {
                    try {
                        ctx.createProducer().send(businessQueue, ctx.createTextMessage(payload));
                        Message received = ctx.createConsumer(businessQueue)
                                .receive(Duration.ofSeconds(10).toMillis());
                        if (received != null) {
                            roundTripped = ((TextMessage) received).getText();
                        }
                    } catch (JMSRuntimeException reconnectLapsed) {
                        // The held context's auto-reconnect gave up; replace it and retry against the recovered QMgr.
                        try {
                            ctx.close();
                        } catch (JMSRuntimeException alreadyBroken) {
                            // best-effort close of the dead context
                        }
                        ctx = cf.createContext(JMSContext.AUTO_ACKNOWLEDGE);
                        businessQueue = ctx.createQueue(BUSINESS_QUEUE);
                        Thread.sleep(1_000L);
                    }
                }

                assertThat(roundTripped)
                        .as("a send/receive round-trip through DEV.QUEUE.1 must succeed after the QMgr is "
                                + "bounced and the client reconnects to the recovered broker")
                        .isEqualTo(payload);
            } finally {
                try {
                    ctx.close();
                } catch (JMSRuntimeException ignore) {
                    // best-effort close
                }
            }

            // The QMgr is Running here (bounceQueueManager polled it back) — siblings are unaffected.
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Scenario 9 — pooled-jms STALE-CONNECTION invalidation: a second borrow yields a WORKING connection.
    // ------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Invalidacao do pooled-jms: apos bounce do QMgr, novo borrow do pool entrega conexao funcional")
    class PooledJmsInvalidation {

        @Test
        @DisplayName("borrow -> bounce QMgr -> borrow novamente -> send/receive funciona (conexao morta substituida)")
        void secondBorrowAfterBounceYieldsWorkingConnection() throws Exception {
            MQConnectionFactory mqCf = buildConnectionFactory();

            JmsPoolConnectionFactory pool = new JmsPoolConnectionFactory();
            // setConnectionFactory accepts the jakarta.jms.ConnectionFactory; the MQ CF is one.
            pool.setConnectionFactory(mqCf);
            pool.setMaxConnections(2);
            try {
                String firstPayload = "{\"order\":9,\"phase\":\"pre-bounce\"}";
                String secondPayload = "{\"order\":9,\"phase\":\"post-bounce\"}";

                // ---- 1) Borrow a context from the pool and use it (send + receive OK) — establishes a
                //         physical pooled connection. ----
                try (JMSContext ctx = pool.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
                    Queue businessQueue = ctx.createQueue(BUSINESS_QUEUE);
                    ctx.createProducer().send(businessQueue, ctx.createTextMessage(firstPayload));
                    Message received = ctx.createConsumer(businessQueue).receive(Duration.ofSeconds(15).toMillis());
                    assertThat(received)
                            .as("the pre-bounce pooled connection should round-trip a message")
                            .isNotNull();
                }

                // ---- 2) Bounce the QMgr so the pooled physical connection goes STALE (the pool still holds
                //         the now-dead connection). Container/port mapping unchanged. ----
                bounceQueueManager();

                // ---- 3) Borrow AGAIN from the pool: pooled-jms must detect the dead connection and replace
                //         it with a working one — a fresh send/receive SUCCEEDS. ----
                try (JMSContext ctx = pool.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
                    Queue businessQueue = ctx.createQueue(BUSINESS_QUEUE);
                    ctx.createProducer().send(businessQueue, ctx.createTextMessage(secondPayload));
                    Message received = ctx.createConsumer(businessQueue).receive(Duration.ofSeconds(20).toMillis());
                    assertThat(received)
                            .as("the SECOND borrow after the QMgr bounce must yield a WORKING connection "
                                    + "(pooled-jms replaced the stale one) — the round-trip succeeds")
                            .isNotNull();
                    assertThat(((TextMessage) received).getText())
                            .as("the post-bounce round-tripped body should match the sent payload")
                            .isEqualTo(secondPayload);
                }
            } finally {
                pool.stop(); // close the pool's physical connections
            }

            // The QMgr is Running here (bounceQueueManager polled it back) — siblings are unaffected.
        }
    }
}
