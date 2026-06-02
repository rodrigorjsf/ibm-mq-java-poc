package com.example.ibmmq.integration;

import com.ibm.msg.client.wmq.WMQConstants;
import com.ibm.mq.jms.MQConnectionFactory;
import com.ibm.mq.testcontainers.MQContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.messaginghub.pooled.jms.JmsPoolConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container;

import javax.jms.JMSConsumer;
import javax.jms.JMSContext;
import javax.jms.JMSProducer;
import javax.jms.JMSRuntimeException;
import javax.jms.Queue;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Group B — VIRTUAL-THREAD evidence (issue #20 AC: "Virtual-Thread throughput/latency measured and the
 * pinning boundary demonstrated"). This is PURELY test-source evidence: production uses NO Virtual Threads
 * (a single platform thread per pod + blocking JMS {@code receive()}, parallelism by Kubernetes replicas).
 * Nothing here changes production behaviour — it documents how a Virtual-Thread fan-out SHOULD and should NOT
 * be wired against a JMS 2.0 client, and demonstrates the Java 25 (JEP-491) pinning boundary deterministically.
 *
 * <p><b>Why {@code @Tag("vt")} and class-gated.</b> These evidence tests start a real broker, run bounded
 * concurrency micro-measurements, and drive programmatic JFR — useful but not part of the deterministic
 * default gate. The base failsafe config excludes the {@code vt} group; run them on demand with
 * {@code mvn verify -Pvt}. JUnit 5 inherits a class-level {@code @Tag} to the {@code @Nested} groups, so the
 * one tag here covers both evidence groups.</p>
 *
 * <p><b>Why ONE shared container.</b> The {@link MQContainer} takes ~30-60 s to boot; the OUTER class owns a
 * single {@code static} container in {@code @BeforeAll}/{@code @AfterAll} and the {@code @Nested} evidence
 * classes share it — mirrors {@link ScenarioMatrixIT} / {@link ScenarioMatrixSlowIT}.</p>
 *
 * <p><b>Why connect as {@code admin}.</b> Mirrors the sibling ITs: the admin user holds full authority on the
 * developer image; these scenarios only PUT/GET on the business queue (no COA/COD report PUT-with-context is
 * exercised), but staying on the same channel/user keeps the harness uniform.</p>
 *
 * <p><b>Bounded, NOT sustained.</b> Every workload here is a short micro-measurement ({@value #MESSAGE_COUNT}
 * messages). The sustained ~167 msg/s cluster load test is a separate, deferred concern (slice #21) and is
 * deliberately NOT built here.</p>
 *
 * <p>Named {@code *IT} so failsafe (not surefire) runs it; {@code @Tag("vt")} keeps it out of the default
 * gate (the base failsafe config excludes the {@code vt} group).</p>
 */
@Tag("vt")
@DisplayName("Evidencia de Virtual Threads Grupo B contra IBM MQ real (JEP-491, padrao certo vs errado)")
class VirtualThreadsEvidenceIT {

    private static final Logger log = LoggerFactory.getLogger(VirtualThreadsEvidenceIT.class);

    private static final String SECRET = "passw0rd";
    private static final String QUEUE_MANAGER = "QM1";
    // Connect via the ADMIN channel/user (full authority on the dev image) — same as the sibling matrix ITs.
    private static final String ADMIN_CHANNEL = "DEV.ADMIN.SVRCONN";
    private static final String ADMIN_USER = "admin";

    // Dedicated business queues for the two concurrency-pattern evidence tests so they never bleed into each
    // other's CURDEPTH proof (and never collide with DEV.QUEUE.1 used by the sibling ITs in other runs).
    private static final String WRONG_PATTERN_QUEUE_NAME = "VT.WRONG.Q";
    private static final String RIGHT_PATTERN_QUEUE_NAME = "VT.RIGHT.Q";
    private static final String WRONG_PATTERN_QUEUE = "queue:///" + WRONG_PATTERN_QUEUE_NAME;
    private static final String RIGHT_PATTERN_QUEUE = "queue:///" + RIGHT_PATTERN_QUEUE_NAME;

    // Bounded message count for the concurrency micro-measurement (200..500 total — a short measurement, NOT a
    // sustained load run; sustained ~167 msg/s on a cluster is deferred slice #21).
    private static final int MESSAGE_COUNT = 300;
    // Pool sizing for the RIGHT pattern: a handful of physical connections fronting the VT fan-out.
    private static final int POOL_MAX_CONNECTIONS = 8;
    // Virtual-thread task count for the JEP-491 pinning workload (each enters a synchronized block and blocks).
    private static final int PINNING_TASK_COUNT = 64;

    // Extracts the integer N from a runmqsc "CURDEPTH(N)" attribute line (same helper shape as ScenarioMatrixIT).
    private static final Pattern CURDEPTH_PATTERN = Pattern.compile("CURDEPTH\\((\\d+)\\)");
    // The JFR event the JDK emits when a virtual thread pins its carrier (pre-JEP-491 trigger: blocking while
    // holding a monitor). On Java 25 a synchronized-blocking VT does NOT pin, so this event must NOT appear.
    private static final String VT_PINNED_EVENT = "jdk.VirtualThreadPinned";
    // Positive-control JFR event that MUST appear (each virtual thread emits one on start). Asserting it was
    // captured proves the recording machinery actually observed virtual-thread events — so a ZERO pinned count
    // means "no pin", not "instrumentation silently broken / event name renamed / recording never started".
    private static final String VT_START_EVENT = "jdk.VirtualThreadStart";

    private static MQContainer mq;

    @BeforeAll
    static void startBroker() throws Exception {
        // The image publishes no bare "9.4.5.0" tag — use the fixpack release -r2.
        mq = new MQContainer("icr.io/ibm-messaging/mq:9.4.5.0-r2")
                .acceptLicense()
                .withQueueManager(QUEUE_MANAGER)
                .withAppPassword(SECRET)     // enables the 'app' user (general use)
                .withAdminPassword(SECRET);  // enables the 'admin' user (used by the evidence tests here)
        mq.start();
        // Define the dedicated VT evidence queues once, after the container is up (the dev image only
        // pre-creates DEV.QUEUE.*). REPLACE keeps this idempotent across reruns of the same container.
        runMqsc(
                "DEFINE QLOCAL(" + WRONG_PATTERN_QUEUE_NAME + ") REPLACE",
                "DEFINE QLOCAL(" + RIGHT_PATTERN_QUEUE_NAME + ") REPLACE");
    }

    @AfterAll
    static void stopBroker() {
        if (mq != null) {
            mq.stop();
        }
    }

    /** Clears both evidence queues before each test so each starts from a clean CURDEPTH (order-independent). */
    @BeforeEach
    void clearQueues() throws Exception {
        runMqsc(
                "CLEAR QLOCAL(" + WRONG_PATTERN_QUEUE_NAME + ")",
                "CLEAR QLOCAL(" + RIGHT_PATTERN_QUEUE_NAME + ")");
    }

    // ------------------------------------------------------------------------------------------------
    // Shared helpers — connection factory + runmqsc CURDEPTH/CLEAR (mirrors ScenarioMatrixIT).
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
     * Runs one or more MQSC verbs through {@code runmqsc QM1} inside the container and asserts a zero exit
     * code. Verbs are newline-joined and piped via {@code printf}; quoting is avoided by keeping verbs free of
     * shell metacharacters (queue names + numeric attributes only). Same shape as {@link ScenarioMatrixIT}.
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
     * integer out of the {@code CURDEPTH(N)} attribute in stdout — the robust, header-free state proof used by
     * {@link ScenarioMatrixIT}.
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
     * Polls {@link #currentDepth(String)} until it reaches {@code target} (at-least) or the bounded budget
     * expires; returns the last observed depth so the caller asserts on it.
     */
    private static int awaitDepthAtLeast(String queueName, int target, Duration budget) throws Exception {
        long deadline = System.currentTimeMillis() + budget.toMillis();
        int depth = currentDepth(queueName);
        while (System.currentTimeMillis() < deadline && depth < target) {
            Thread.sleep(250L);
            depth = currentDepth(queueName);
        }
        return depth;
    }

    /** Computes the value at the given percentile (0..100) from a list of nanosecond latencies (copy sorted). */
    private static long percentileNanos(List<Long> latenciesNanos, double percentile) {
        List<Long> sorted = new ArrayList<>(latenciesNanos);
        sorted.sort(Long::compareTo);
        // Nearest-rank: index = ceil(p/100 * N) - 1, clamped into [0, N-1].
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }

    // ------------------------------------------------------------------------------------------------
    // (B1) RightVsWrongConcurrencyPattern — JMSContext is NOT thread-safe; right vs wrong VT usage.
    // ------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Padrao certo vs errado de concorrencia: JMSContext nao e thread-safe sob Virtual Threads")
    class RightVsWrongConcurrencyPattern {

        @Test
        @DisplayName("CERTO: JMSContext por tarefa via pool e correto e roda em virtual threads; ERRADO: JMSContext compartilhado e anti-padrao")
        void perTaskContextIsCorrectWhileSharedContextIsAnUnsafeAntiPattern() throws Exception {
            // JMSContext/Session are NOT thread-safe (JMS 2.0 spec). This contrasts the WRONG pattern (one
            // JMSContext shared by N concurrent virtual threads) against the RIGHT pattern (one JMSContext PER
            // task, drawn from a JmsPoolConnectionFactory, with virtual threads only for fan-out) and MEASURES
            // both. NOTE on the IBM MQ client specifically: it SERIALIZES internal session access, so a shared
            // context usually neither throws nor loses sends — instead its cost is LOST CONCURRENCY (every
            // virtual thread contends on the one session's monitor). We therefore do NOT assert a throughput
            // WINNER: empirically the ordering is non-deterministic (the lean shared session often out-runs the
            // per-task pattern, which pays pool borrow/create/close churn) — see research-output/phase-g §4b.
            // The asserted evidence is the per-task pattern's CORRECTNESS + virtual-thread execution, plus that
            // the shared (anti-pattern) run still delivered all its messages; both throughputs are logged.

            // ---- WRONG pattern: one shared JMSContext hammered by N virtual threads (serialized by the client) ----
            MQConnectionFactory wrongCf = buildConnectionFactory();
            ConcurrentLinkedQueue<Throwable> wrongFailures = new ConcurrentLinkedQueue<>();
            long wrongWallStart;
            long wrongWallEnd;
            // Manage the shared context MANUALLY (not try-with-resources): a concurrently-abused Session can be
            // left inconsistent and its close() may itself throw — swallow that so only the measurements decide.
            JMSContext sharedCtx = wrongCf.createContext(JMSContext.AUTO_ACKNOWLEDGE);
            try {
                Queue queue = sharedCtx.createQueue(WRONG_PATTERN_QUEUE);
                CountDownLatch ready = new CountDownLatch(MESSAGE_COUNT);
                CountDownLatch go = new CountDownLatch(1);
                List<Future<?>> futures = new ArrayList<>(MESSAGE_COUNT);
                wrongWallStart = System.nanoTime();
                try (ExecutorService vexec = Executors.newVirtualThreadPerTaskExecutor()) {
                    for (int i = 0; i < MESSAGE_COUNT; i++) {
                        final int seq = i;
                        futures.add(vexec.submit(() -> {
                            ready.countDown();
                            go.await(); // align all tasks to hammer the one shared context at the same instant
                            sharedCtx.createProducer().send(queue, "{\"wrong\":" + seq + "}");
                            return null;
                        }));
                    }
                    ready.await();
                    go.countDown();
                    for (Future<?> future : futures) {
                        try {
                            future.get();
                        } catch (Exception taskFailed) {
                            wrongFailures.add(taskFailed.getCause() != null ? taskFailed.getCause() : taskFailed);
                        }
                    }
                }
                wrongWallEnd = System.nanoTime();
            } finally {
                try {
                    sharedCtx.close();
                } catch (JMSRuntimeException closeOfAbusedContextThrew) {
                    wrongFailures.add(closeOfAbusedContextThrew);
                }
            }
            int wrongDelivered = awaitDepthAtLeast(WRONG_PATTERN_QUEUE_NAME, MESSAGE_COUNT, Duration.ofSeconds(10));
            double wrongWallSeconds = (wrongWallEnd - wrongWallStart) / 1_000_000_000.0;
            double wrongThroughput = wrongWallSeconds > 0 ? wrongDelivered / wrongWallSeconds : 0.0;
            drainViaJms(wrongCf, WRONG_PATTERN_QUEUE);

            // ---- RIGHT pattern: one pooled JMSContext per task + virtual-thread fan-out (genuinely parallel) ----
            MQConnectionFactory rightCf = buildConnectionFactory();
            JmsPoolConnectionFactory pool = new JmsPoolConnectionFactory();
            pool.setConnectionFactory(rightCf);
            pool.setMaxConnections(POOL_MAX_CONNECTIONS);

            ConcurrentLinkedQueue<Long> latenciesNanos = new ConcurrentLinkedQueue<>();
            ConcurrentLinkedQueue<Throwable> rightFailures = new ConcurrentLinkedQueue<>();
            AtomicInteger nonVirtualObserved = new AtomicInteger(0);
            long rightWallStart;
            long rightWallEnd;
            try {
                CountDownLatch ready = new CountDownLatch(MESSAGE_COUNT);
                CountDownLatch go = new CountDownLatch(1);
                List<Future<?>> futures = new ArrayList<>(MESSAGE_COUNT);
                rightWallStart = System.nanoTime();
                try (ExecutorService vexec = Executors.newVirtualThreadPerTaskExecutor()) {
                    for (int i = 0; i < MESSAGE_COUNT; i++) {
                        final int seq = i;
                        futures.add(vexec.submit(() -> {
                            // Evidence assertion: the fan-out genuinely runs on virtual threads.
                            if (!Thread.currentThread().isVirtual()) {
                                nonVirtualObserved.incrementAndGet();
                            }
                            ready.countDown();
                            go.await();
                            // One JMSContext PER TASK, drawn from the pool, closed when the task ends.
                            try (JMSContext ctx = pool.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
                                Queue queue = ctx.createQueue(RIGHT_PATTERN_QUEUE);
                                JMSProducer producer = ctx.createProducer();
                                long sendStart = System.nanoTime();
                                producer.send(queue, "{\"right\":" + seq + "}");
                                latenciesNanos.add(System.nanoTime() - sendStart);
                            }
                            return null;
                        }));
                    }
                    ready.await();
                    go.countDown();
                    for (Future<?> future : futures) {
                        try {
                            future.get();
                        } catch (Exception taskFailed) {
                            rightFailures.add(taskFailed.getCause() != null ? taskFailed.getCause() : taskFailed);
                        }
                    }
                }
                rightWallEnd = System.nanoTime();
            } finally {
                pool.stop(); // close the pool's physical connections
            }
            int rightDelivered = awaitDepthAtLeast(RIGHT_PATTERN_QUEUE_NAME, MESSAGE_COUNT, Duration.ofSeconds(20));
            double rightWallSeconds = (rightWallEnd - rightWallStart) / 1_000_000_000.0;
            double rightThroughput = rightWallSeconds > 0 ? MESSAGE_COUNT / rightWallSeconds : 0.0;
            List<Long> latencies = new ArrayList<>(latenciesNanos);
            double p50Ms = percentileNanos(latencies, 50) / 1_000_000.0;
            double p95Ms = percentileNanos(latencies, 95) / 1_000_000.0;
            double p99Ms = percentileNanos(latencies, 99) / 1_000_000.0;
            drainViaJms(rightCf, RIGHT_PATTERN_QUEUE);

            log.info("WRONG pattern (shared JMSContext, {} VT tasks): delivered={}/{}; wall={}s; "
                            + "throughput={} msgs/sec; threw={} (the IBM MQ client serializes shared-session "
                            + "access, so the cost is lost concurrency, not corruption)",
                    MESSAGE_COUNT, wrongDelivered, MESSAGE_COUNT, String.format("%.3f", wrongWallSeconds),
                    String.format("%.1f", wrongThroughput), !wrongFailures.isEmpty());
            log.info("RIGHT pattern (one JMSContext per task from a pool of {}, {} VT tasks): delivered={}/{}; "
                            + "wall={}s; throughput={} msgs/sec; send latency p50={}ms p95={}ms p99={}ms; "
                            + "task failures={}",
                    POOL_MAX_CONNECTIONS, MESSAGE_COUNT, rightDelivered, MESSAGE_COUNT,
                    String.format("%.3f", rightWallSeconds), String.format("%.1f", rightThroughput),
                    String.format("%.3f", p50Ms), String.format("%.3f", p95Ms), String.format("%.3f", p99Ms),
                    rightFailures.size());

            // ---- Evidence assertions ----
            assertThat(rightFailures)
                    .as("the RIGHT pattern (one JMSContext per task) must not race: no task should throw")
                    .isEmpty();
            assertThat(nonVirtualObserved.get())
                    .as("every fan-out task must run on a virtual thread "
                            + "(Thread.currentThread().isVirtual() == true)")
                    .isZero();
            assertThat(rightDelivered)
                    .as("exactly %d messages must be delivered when each task uses its own pooled JMSContext",
                            MESSAGE_COUNT)
                    .isEqualTo(MESSAGE_COUNT);
            assertThat(latencies)
                    .as("a per-send latency sample must have been collected for every delivered message")
                    .hasSize(MESSAGE_COUNT);
            assertThat(rightThroughput)
                    .as("throughput must be a positive, measured rate (msgs/sec)")
                    .isGreaterThan(0.0);
            // RIGHT-vs-WRONG evidence is OBSERVATIONAL, not a throughput race. Empirically (logged above) the
            // shared-context anti-pattern does NOT reliably lose to the per-task pattern on THIS micro-workload:
            // the IBM MQ client serializes internal session access (so the shared run neither corrupts nor
            // loses sends), and its one lean long-lived session can even out-run the per-task pattern, which
            // pays pool borrow/create/close churn per task. So we deliberately do NOT assert a throughput
            // winner (the ordering is non-deterministic across runs — see research-output/phase-g §4b). The
            // shared context is WRONG regardless: it relies on undefined, provider- and version-specific
            // behaviour (JMSContext/Session are not thread-safe per the JMS 2.0 spec) and cannot scale beyond a
            // single session. Falsifiable anchor for the WRONG branch (matches the documented empirical
            // finding): with the IBM MQ client the serialized shared-context run still DELIVERS every message.
            assertThat(wrongDelivered)
                    .as("the shared-context anti-pattern run, serialized by the IBM MQ client, must still "
                            + "deliver all %d messages (CURDEPTH(VT.WRONG.Q)); right=%.1f vs wrong=%.1f msgs/sec "
                            + "are logged as measured evidence (no throughput winner is asserted)",
                            MESSAGE_COUNT, rightThroughput, wrongThroughput)
                    .isEqualTo(MESSAGE_COUNT);
        }

        /** Destructively drains a queue via a single-threaded JMS consumer (cleanup between/after tests). */
        private void drainViaJms(MQConnectionFactory cf, String queue) throws Exception {
            try (JMSContext ctx = cf.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
                JMSConsumer consumer = ctx.createConsumer(ctx.createQueue(queue));
                while (consumer.receive(300L) != null) {
                    // keep consuming until the queue is empty (a timed-out receive returns null)
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // (B2) PinningBoundary — JEP-491 on Java 25: synchronized no longer pins a blocking virtual thread.
    // ------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("Fronteira de pinning (JEP-491 no Java 25): synchronized nao prende mais a virtual thread")
    class PinningBoundary {

        @Test
        @DisplayName("DETERMINISTICO: N virtual threads bloqueando DENTRO de synchronized -> pinning NAO escala por bloco synchronized (JEP-491)")
        void synchronizedBlockingDoesNotPinOnJava25() throws Exception {
            // The exact PRE-JEP-491 pinning trigger: a virtual thread that PARKS (blocks) WHILE HOLDING A
            // MONITOR (inside a synchronized block). On Java <= 21 this WOULD pin the carrier thread and emit a
            // jdk.VirtualThreadPinned JFR event on essentially every such block (>= the task count). On Java 25
            // (JEP-491, "synchronized no longer pins") the monitor does NOT pin — so the pinned-event count does
            // NOT scale with the number of synchronized blocks; only a FEW INCIDENTAL pins remain (class-loading
            // / native frames, NOT the monitor), keeping the count well below the task count. We observe via
            // programmatic JFR (jdk.jfr.Recording) — NOT the removed -Djdk.tracePinnedThreads flag (gone in JDK
            // 24+; the JFR event is the supported observation path). A jdk.VirtualThreadStart positive control
            // proves the recording actually captured VT events, so a low pinned count is real, not a silent
            // instrumentation failure. (An "exactly 0" assertion would be flaky: incidental class-loading pins
            // appear non-deterministically in the 0..~handful range — the deterministic claim is non-scaling.)
            Path jfrFile = Files.createTempFile("vt-pinning-evidence-", ".jfr");
            List<RecordedEvent> pinnedEvents;
            int vtStartCount;
            try {
                try (Recording recording = new Recording()) {
                    // Capture ALL pins, not just the default >20ms threshold, so even a brief pin would be seen.
                    recording.enable(VT_PINNED_EVENT).withoutThreshold();
                    // Positive control: also capture virtual-thread START events (one per task) to prove the
                    // recording actually observed VT activity — so a zero pinned count is real, not a silent
                    // instrumentation failure.
                    recording.enable(VT_START_EVENT);
                    recording.start();

                    // Each virtual thread acquires a shared monitor and BLOCKS while holding it (Thread.sleep
                    // inside synchronized). A shared lock + a barrier maximise contention so, on a pre-JEP-491
                    // runtime, carriers would be exhausted and pinning would be unmistakable.
                    Object lock = new Object();
                    CountDownLatch ready = new CountDownLatch(PINNING_TASK_COUNT);
                    CountDownLatch go = new CountDownLatch(1);
                    try (ExecutorService vexec = Executors.newVirtualThreadPerTaskExecutor()) {
                        List<Future<?>> futures = new ArrayList<>(PINNING_TASK_COUNT);
                        for (int i = 0; i < PINNING_TASK_COUNT; i++) {
                            futures.add(vexec.submit(() -> {
                                ready.countDown();
                                go.await();
                                // Park WHILE HOLDING THE MONITOR — the canonical pinning case pre-JEP-491.
                                synchronized (lock) {
                                    Thread.sleep(20L);
                                }
                                return null;
                            }));
                        }
                        ready.await();
                        go.countDown();
                        for (Future<?> future : futures) {
                            future.get();
                        }
                    }

                    recording.stop();
                    recording.dump(jfrFile);
                }

                pinnedEvents = readEvents(jfrFile, VT_PINNED_EVENT);
                vtStartCount = countEvents(jfrFile, VT_START_EVENT);
            } finally {
                Files.deleteIfExists(jfrFile);
            }
            int pinnedEventCount = pinnedEvents.size();

            log.info("JEP-491 boundary: {} virtual threads each blocked (Thread.sleep) INSIDE a synchronized "
                            + "block; jdk.VirtualThreadPinned count = {} (JEP-491: synchronized no longer pins, "
                            + "so pins do NOT scale per synchronized-block); jdk.VirtualThreadStart count = {} "
                            + "(positive control, expected > 0). Any residual pins are incidental "
                            + "(class-loading/native), shown below:",
                    PINNING_TASK_COUNT, pinnedEventCount, vtStartCount);
            for (RecordedEvent pin : pinnedEvents) {
                log.info("  residual jdk.VirtualThreadPinned top frame: {}", topStackFrame(pin));
            }

            assertThat(vtStartCount)
                    .as("positive control: the JFR recording must have captured virtual-thread start events "
                            + "(the %d pinning tasks each start one) — otherwise the pinned-event count would "
                            + "be meaningless (broken/renamed instrumentation or an unstarted recording), not "
                            + "evidence of JEP-491.", PINNING_TASK_COUNT)
                    .isGreaterThan(0);

            assertThat(pinnedEventCount)
                    .as("JEP-491 (Java 25): a virtual thread that BLOCKS while holding a monitor "
                            + "(Thread.sleep inside synchronized) must NOT pin its carrier PER BLOCK. Pre-JEP-491 "
                            + "(Java <= 21) this %d-task workload pins on essentially every synchronized "
                            + "acquisition/park (at least the task count); on Java 25 synchronized no longer pins, "
                            + "so pins do NOT scale with the synchronized-block count — only a few incidental "
                            + "class-loading/native pins remain (logged above). The count must stay well below "
                            + "the task count.", PINNING_TASK_COUNT)
                    .isLessThan(PINNING_TASK_COUNT / 2);
        }

        @Test
        @DisplayName("EVIDENCIA: workload de I/O MQ em virtual threads sob JFR -> registra (sem assertar contagem) qualquer pinning residual nativo")
        void mqIoOnVirtualThreadsUnderJfrLogsResidualNativePinning() throws Exception {
            // Supporting (evidence-only, NOT a hard count assertion): run the RIGHT-pattern MQ I/O workload on
            // virtual threads under a JFR recording and LOG any jdk.VirtualThreadPinned events with their top
            // stack frame. Any residual pinning on Java 25 is environment-dependent and lives in the MQ
            // client's NATIVE/FFI frames (not synchronized) — so we observe and report rather than assert a
            // count (a count assertion here would be flaky across hosts/driver builds).
            MQConnectionFactory mqCf = buildConnectionFactory();

            JmsPoolConnectionFactory pool = new JmsPoolConnectionFactory();
            pool.setConnectionFactory(mqCf);
            pool.setMaxConnections(POOL_MAX_CONNECTIONS);

            Path jfrFile = Files.createTempFile("vt-mqio-pinning-evidence-", ".jfr");
            List<RecordedEvent> pinnedEvents;
            int delivered;
            try {
                try (Recording recording = new Recording()) {
                    recording.enable(VT_PINNED_EVENT).withoutThreshold();
                    recording.start();

                    CountDownLatch ready = new CountDownLatch(MESSAGE_COUNT);
                    CountDownLatch go = new CountDownLatch(1);
                    List<Future<?>> futures = new ArrayList<>(MESSAGE_COUNT);
                    try (ExecutorService vexec = Executors.newVirtualThreadPerTaskExecutor()) {
                        for (int i = 0; i < MESSAGE_COUNT; i++) {
                            final int seq = i;
                            futures.add(vexec.submit(() -> {
                                ready.countDown();
                                go.await();
                                try (JMSContext ctx = pool.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
                                    Queue queue = ctx.createQueue(RIGHT_PATTERN_QUEUE);
                                    ctx.createProducer().send(queue, "{\"vtio\":" + seq + "}");
                                }
                                return null;
                            }));
                        }
                        ready.await();
                        go.countDown();
                        for (Future<?> future : futures) {
                            future.get();
                        }
                    }

                    recording.stop();
                    recording.dump(jfrFile);
                }

                pinnedEvents = readEvents(jfrFile, VT_PINNED_EVENT);
            } finally {
                pool.stop(); // release the pool's physical MQ connections (mirror RightVsWrong's pool.stop())
                Files.deleteIfExists(jfrFile);
            }

            delivered = awaitDepthAtLeast(RIGHT_PATTERN_QUEUE_NAME, MESSAGE_COUNT, Duration.ofSeconds(20));

            if (pinnedEvents.isEmpty()) {
                log.info("MQ I/O on virtual threads under JFR: delivered={} of {}; ZERO residual "
                                + "jdk.VirtualThreadPinned events recorded.",
                        delivered, MESSAGE_COUNT);
            } else {
                log.info("MQ I/O on virtual threads under JFR: delivered={} of {}; {} residual "
                                + "jdk.VirtualThreadPinned event(s) recorded (residual pinning is native/FFI in "
                                + "the MQ client, NOT synchronized — see top frames below):",
                        delivered, MESSAGE_COUNT, pinnedEvents.size());
                for (RecordedEvent event : pinnedEvents) {
                    String topFrame = topStackFrame(event);
                    log.info("  jdk.VirtualThreadPinned top frame: {}", topFrame);
                }
            }

            // Drain so a rerun starts clean (cleanup only — no count assertion on the pinned events).
            try (JMSContext ctx = mqCf.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
                JMSConsumer consumer = ctx.createConsumer(ctx.createQueue(RIGHT_PATTERN_QUEUE));
                while (consumer.receive(300L) != null) {
                    // keep consuming until empty
                }
            }

            // The MQ I/O itself must succeed (the evidence is only meaningful if the workload actually ran).
            assertThat(delivered)
                    .as("the MQ I/O evidence workload must deliver all %d messages on virtual threads",
                            MESSAGE_COUNT)
                    .isEqualTo(MESSAGE_COUNT);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // JFR helpers — read a dumped recording and count/collect events of a given type.
    // ------------------------------------------------------------------------------------------------

    /** Counts the events of the given type name in a dumped JFR file. */
    private static int countEvents(Path jfrFile, String eventTypeName) throws Exception {
        return readEvents(jfrFile, eventTypeName).size();
    }

    /** Collects every event whose {@code getEventType().getName()} equals the given name from a JFR file. */
    private static List<RecordedEvent> readEvents(Path jfrFile, String eventTypeName) throws Exception {
        List<RecordedEvent> events = new ArrayList<>();
        try (RecordingFile recordingFile = new RecordingFile(jfrFile)) {
            while (recordingFile.hasMoreEvents()) {
                RecordedEvent event = recordingFile.readEvent();
                if (event.getEventType().getName().equals(eventTypeName)) {
                    events.add(event);
                }
            }
        }
        return events;
    }

    /** Returns the top stack frame of a recorded event as "ClassName.method:line", or a placeholder. */
    private static String topStackFrame(RecordedEvent event) {
        if (event.getStackTrace() == null || event.getStackTrace().getFrames().isEmpty()) {
            return "<no stack trace>";
        }
        var frame = event.getStackTrace().getFrames().get(0);
        var method = frame.getMethod();
        return method.getType().getName() + "." + method.getName() + ":" + frame.getLineNumber();
    }
}
