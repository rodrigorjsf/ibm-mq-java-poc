# Phase G — MQRO report options & the Group A scenario matrix (issue #20)

Validated reference for the **`MQRO_*` report-request option integers** and the Group A integration
scenarios that exercise them. All constant values below were extracted from the authentic
`com.ibm.mq.allclient:9.4.5.0` jar bytecode (sha1-verified, cross-checked against IBM docs) and live in
`com.ibm.mq.constants.CMQC` (re-exported via `com.ibm.mq.constants.MQConstants`).

> **Zero re-research rule.** This file is the source of truth for the with-data report options used by the
> `ScenarioMatrixIT` Group A matrix. Do not re-extract these constants — cite this file.

---

## 1. Report-request option integers — `MQRO_*` (bytecode-verified)

| Constant | Decimal | Hex | Meaning |
| --- | --- | --- | --- |
| `MQRO_COA` | 256 | 0x100 | Request a COA (confirmation on arrival) |
| `MQRO_COA_WITH_DATA` | 768 | 0x300 | COA + up to **100 bytes** of the original body |
| `MQRO_COA_WITH_FULL_DATA` | 1792 | 0x700 | COA + the **entire** original body |
| `MQRO_COD` | 2048 | 0x800 | Request a COD (confirmation on delivery) |
| `MQRO_COD_WITH_DATA` | 6144 | 0x1800 | COD + up to **100 bytes** of the original body |
| `MQRO_COD_WITH_FULL_DATA` | 14336 | 0x3800 | COD + the **entire** original body |
| `MQRO_EXCEPTION_WITH_DATA` | 50331648 | 0x3000000 | Exception report + up to 100 bytes of the original |
| `MQRO_EXCEPTION_WITH_FULL_DATA` | 117440512 | 0x7000000 | Exception report + the entire original |
| `MQRO_EXPIRATION_WITH_DATA` | 6291456 | 0x600000 | Expiration report + up to 100 bytes of the original |
| `MQRO_EXPIRATION_WITH_FULL_DATA` | 14680064 | 0xE00000 | Expiration report + the entire original |

Base (no-data) request options for completeness: `MQRO_EXCEPTION = 16777216` (0x1000000),
`MQRO_EXPIRATION = 2097152` (0x200000). Full id-propagation / discard options are catalogued in
`ai/skills/jms-mq-delivery-report-analyzer/references/constants.md` §2.

### With-data semantics

- **`*_WITH_DATA`** — the generated report carries **up to the first 100 bytes** of the original message
  data. Useful when only a short prefix (a key, a header) is needed for audit.
- **`*_WITH_FULL_DATA`** — the generated report carries the **entire** original message body. The report
  message body therefore reproduces the original payload (a `TextMessage` round-trips as the same text; a
  `BytesMessage`-form report carries the original bytes).
- The with-data bits are a **superset** of the base bit (e.g. `MQRO_COD_WITH_FULL_DATA` = `MQRO_COD` plus
  the data flags), so requesting the with-data option implies the base COA/COD request.

### How the option is set (JMS)

The report request is set on the **message** via the JMS report properties, not on the connection:

```java
msg.setIntProperty(WMQConstants.JMS_IBM_REPORT_COD, MQConstants.MQRO_COD_WITH_FULL_DATA); // 14336
// or, for COA-with-data:
msg.setIntProperty(WMQConstants.JMS_IBM_REPORT_COA, MQConstants.MQRO_COA_WITH_FULL_DATA); // 1792
```

`JMS_IBM_REPORT_COA` / `JMS_IBM_REPORT_COD` are the `WMQConstants` field names; `MQRO_*` come from
`MQConstants` (NOT `WMQConstants`).

---

## 2. Why the IT exercises with-data over the ADMIN connection only

Requesting `*_WITH_FULL_DATA` does **not** by itself change the authority the QMgr needs — the report PUT
onto the ReplyToQ still requires **pass-identity context authority** (the one `app` is missing is `+passid`;
the live-verified full remediation grant is `SET AUTHREC ... AUTHADD(PUT, PASSID, PASSALL, SETID, SETALL)` —
see the "Report-PUT authority gotcha (2035) — CORRECTED on live k3s" note in `CLAUDE.md` and
`research-output/coa-cod-validation-findings.md`). The developer-image `app` user lacks `+passid`, so a
report PUT as `app` fails `MQRC_NOT_AUTHORIZED (2035)` and dead-letters.

Issue #19 deliberately kept the **production** `BusinessMessageProducer` on plain `MQRO_COD` (no data, no
`+setid/+setall` escalation) — the recovered-MQMD-fields approach reads the report's own descriptor and
needs no embedded original. The `ScenarioMatrixIT` `WithDataPayload` scenario therefore sets the with-data
flag on a message it builds **itself** and sends over the IT's **admin** connection (`DEV.ADMIN.SVRCONN`,
user `admin`), which already holds full context authority. The production producer is **unchanged**.

---

## 3. Group A scenario matrix (`ScenarioMatrixIT`)

One default-gated IT (`mvn verify`, failsafe) with a **single shared `MQContainer`** and `@Nested`
scenario classes. A `@BeforeEach` drains `DEV.QUEUE.1` and `DEV.QUEUE.2` so scenarios are order-independent.

| Scenario (`@Nested`) | Report option(s) | What it proves |
| --- | --- | --- |
| `PersistenceInheritance` | `MQRO_COA` + `MQRO_COD` on a PERSISTENT original | The received COA **and** COD both report `getJMSDeliveryMode() == PERSISTENT` — reports **inherit** the original's persistence (refutes the brief's "non-persistent by default" assumption). |
| `SyncpointCoaCodTiming` | `MQRO_COA` + `MQRO_COD` | COA is generated at **PUT** time; COD only after the consumer's **transacted commit**. Before consuming: COA present (259), no COD (260); after a `SESSION_TRANSACTED` destructive get + commit: a COD appears. Bounded polling (no fixed sleep). |
| `WithDataPayload` | `MQRO_COD_WITH_FULL_DATA` (14336) | The received COD report **body carries the original payload** (the with-full-data report reproduces the original message data). Sent over the admin connection. |

### Field recovery (Group C, already covered — catalogued, not duplicated)

The AC "Field-recovery asserted on both report kinds (COA and COD)" is **already satisfied** by
`CoaCodEndToEndIT.assertRecoveredMqmdFields(...)`, which runs **per report** inside the collection loop
(gated by `coaSeen`/`codSeen`) and asserts the six recovered MQMD fields for **both** the COA and the COD:
`applIdentityData`, `accountingToken` (32 bytes = `MQ_ACCOUNTING_TOKEN_LENGTH`), `correlationIdBytes`
(== original `MsgId` under default `MQRO_COPY_MSG_ID_TO_CORREL_ID`), `messageIdBytes`, `putTimestampUtc`,
and the derived `reportTypeChar` (`'A'` / `'D'`). It is **not** re-implemented in `ScenarioMatrixIT`.

---

## 4. Broker-setup facts (live-verified, MQ 9.4.5.0-r2 dev image)

Verified live against `icr.io/ibm-messaging/mq:9.4.5.0-r2` while implementing the deterministic Group A
scenarios (`ReportPutAuthToDlq`, `PoisonMessageBackout`, `QueueFull`) in `ScenarioMatrixIT`. These are the
broker facts the IT depends on — recorded here so they are never re-researched.

### Dead-letter queue + the `app` authority gotcha (refines §2)

- The Queue Manager DLQ is **`DEV.DEAD.LETTER.QUEUE`** (the dev image's default).
- The low-privilege **`app`** user holds only **`put` + `browse`** on `DEV.QUEUE.2` — **NO `passid`**
  (pass-identity context). So when `app` produces an original requesting a COA/COD with
  `JMSReplyTo = DEV.QUEUE.2`, the QMgr's report PUT-with-context onto the ReplyToQ fails
  `MQRC_NOT_AUTHORIZED` (2035) and the report is routed to `DEV.DEAD.LETTER.QUEUE` — the report queue stays
  empty. This is the authority that matters: **`passid`** (corrected here and in `CLAUDE.md`), **not**
  `+setall` (the stale assumption still recorded in the IT-01 catalogue entry — do not copy it).
- A COA fires at **PUT** time, so the unauthorized report PUT (and dead-lettering) happens immediately —
  the failure path can be reproduced with **no consume step**. Sending the original `PERSISTENT` makes the
  refused report **dead-letter** (inheriting persistence) rather than being silently discarded.
- `admin` (`DEV.ADMIN.SVRCONN`) holds full context authority, which is why every other scenario connects as
  `admin` and its reports are NOT refused.

### `runmqsc` in-container — verified verbs

`mq.execInContainer("bash","-c", "printf 'CMD\\n' | runmqsc QM1")` works for broker setup and inspection.
Verified verbs (each returns its `AMQ8xxxI` success line; exit code 0):

| Verb | Use in the IT |
| --- | --- |
| `DEFINE QLOCAL(SCENARIO.POISON.Q) BOTHRESH(3) BOQNAME(SCENARIO.BACKOUT.Q) REPLACE` | poison-backout setup (`AMQ8006I`) |
| `DEFINE QLOCAL(SCENARIO.BACKOUT.Q) REPLACE` | backout target |
| `DEFINE QLOCAL(SCENARIO.FULL.Q) MAXDEPTH(2) REPLACE` | queue-full setup |
| `DIS QLOCAL(name) CURDEPTH` | robust state proof — parse `CURDEPTH(N)` from stdout (no MQDLH parsing) |
| `CLEAR QLOCAL(name)` | per-scenario isolation (`@BeforeEach` clears DLQ + scenario queues) |
| `SET AUTHREC ... AUTHADD(...)` | (context, not used by these three) grant authority — verb is `SET`, not `DEFINE` |

- After `BOTHRESH(3)` rollbacks of a message on `SCENARIO.POISON.Q` under a transacted session, the QMgr
  requeues it to `BOQNAME` (`SCENARIO.BACKOUT.Q`); the next `receive()` returns null. `JMSXDeliveryCount`
  increments across redeliveries. Commit (not rollback) at the end of the loop — the requeue PUT may ride
  the consumer's UoW, and commit is safe either way.
- A `PUT` past `MAXDEPTH` fails `MQRC_Q_FULL` (2053). Under the JMS 2.0 **simplified** API
  (`JMSContext`/`JMSProducer`), `JMSProducer.send()` throws **`javax.jms.JMSRuntimeException`** (unchecked,
  does NOT extend `JMSException`, has no `getLinkedException()`) — detect 2053 by walking the cause chain
  (e.g. `hasStackTraceContaining("2053")`), not via `((JMSException) e).getLinkedException()`.

### `ALTER QMGR EXPRYINT(...)` is a SYNTAX ERROR in 9.4.5

- `ALTER QMGR EXPRYINT(...)` is **rejected as a syntax error** in MQ 9.4.5 — the `EXPRYINT` attribute does
  **not** exist on this version. Expiration reports cannot be forced via an expiry-scan interval; they must
  be forced **another way** (e.g. a post-TTL destructive `GET` after the message's TTL elapses, which makes
  the QMgr discard the expired message and generate the expiration report). This is why the Group A matrix
  keeps expiration scenarios out of the deterministic default gate.

### Bounce the broker IN-container — NOT `MQContainer.stop()/start()`

The timing-sensitive Group A scenarios (`ScenarioMatrixSlowIT`, `@Tag("scenario")` — `Reconnect`,
`PooledJmsInvalidation`) need the broker to go away and come back **under a live client** so client
auto-reconnect (and pooled-jms stale-connection replacement) actually exercise. The Testcontainers-safe way
to do this is to bounce the **Queue Manager INSIDE the still-running container**, never to stop/start the
container itself:

```java
mq.execInContainer("bash", "-c", "endmqm -i QM1"); // immediate stop of the QMgr (container stays up)
mq.execInContainer("bash", "-c", "strmqm QM1");     // start it again — SAME container, SAME port mapping
// then poll dspmq until STATUS(Running):
mq.execInContainer("bash", "-c", "dspmq -m QM1");   // look for the "STATUS(Running)" token
```

- **Why NOT `MQContainer.stop()` / `start()`:** Testcontainers publishes the MQ listener on a **random host
  port**, and stopping/starting the container **REMAPS** that port. A client configured for auto-reconnect
  (`WMQ_CLIENT_RECONNECT_OPTIONS = WMQ_CLIENT_RECONNECT`) — or a `JmsPoolConnectionFactory` holding a stale
  connection — would then be reconnecting to a port that no longer exists, so the reconnect/replacement
  could **never** succeed. Bouncing only the QMgr keeps the container (and its host:port) stable, so the
  client transparently re-establishes against the same endpoint.
- **`endmqm -i` (immediate)** drops the QMgr promptly so the client observes a real disconnect; `strmqm`
  brings it back. Poll `dspmq -m QM1` for `STATUS(Running)` (bounded) before driving traffic — and ensure
  the QMgr is left **Running** so sibling scenarios sharing the one container are unaffected.
- This is why these scenarios share ONE container yet still simulate a broker outage, and why they are
  tag-gated (`mvn verify -Pscenarios`) rather than on the deterministic default gate.

---

## 4b. Virtual Threads on Java 25 (JEP-491) — Group B evidence (`VirtualThreadsEvidenceIT`)

Validated reference for the Group B Virtual-Thread evidence (issue #20 AC: "Virtual-Thread throughput/latency
measured and the pinning boundary demonstrated"). This is **purely test-source evidence**: production uses
**NO Virtual Threads** — a single platform thread per pod + blocking JMS `receive()`, with parallelism by
Kubernetes replicas. None of this changes production behaviour; it documents how a Virtual-Thread fan-out
SHOULD (and should NOT) be wired against the JMS 2.0 client, and demonstrates the Java 25 pinning boundary.

The tests live in `VirtualThreadsEvidenceIT` (`@Tag("vt")`, run on demand via `mvn verify -Pvt`; excluded
from the default gate by the base failsafe `<excludedGroups>replication,scenario,vt</excludedGroups>`).
Workloads are **bounded** (~300 messages) — short micro-measurements, NOT a sustained load run (sustained
~167 msg/s on a cluster is the deferred slice #21).

### Right vs wrong concurrency pattern (`JMSContext` is NOT thread-safe)

- **Wrong** — sharing ONE `JMSContext` across N concurrent virtual threads (each calling
  `sharedCtx.createProducer().send(...)`) VIOLATES the JMS 2.0 thread-safety contract (`JMSContext`/`Session`
  are NOT thread-safe). **Empirical finding (IBM MQ allclient 9.4.5, live broker):** with this client the
  anti-pattern does NOT manifest as corruption or lost sends — the IBM MQ client SERIALIZES internal session
  access, so all N messages are delivered and no task throws (`runmqsc DIS QLOCAL(...) CURDEPTH` == N,
  `threw=false`). It also does NOT reliably lose on throughput: across repeated runs the one lean, long-lived
  shared session frequently OUT-runs the per-task pattern (which pays `JmsPoolConnectionFactory`
  borrow/create/close churn per task, ~26 ms p50 per send), so the right-vs-wrong throughput ordering is
  **non-deterministic** (observed both orders). The shared context is WRONG regardless: it relies on
  **undefined, provider- and version-specific behaviour** (a spec-strict provider could corrupt or throw) and
  **cannot scale beyond a single session**. The IT therefore runs BOTH patterns, LOGS both throughputs as
  measured evidence, and asserts ONLY the per-task pattern's correctness + virtual-thread execution — it
  deliberately does **not** assert a throughput winner (that would be flaky and would overclaim).
- **Right** — front the MQ `MQConnectionFactory` with an `org.messaginghub.pooled.jms.JmsPoolConnectionFactory`
  (bounded `maxConnections`, e.g. 8) and give **EACH** virtual thread its **OWN** `JMSContext` drawn from the
  pool (`try (JMSContext c = pool.createContext(...)) { ... }`). Virtual threads provide cheap fan-out; the
  pool provides safe, reused physical connections; nothing JMS is shared across threads, so there is no race.
  All N messages are delivered. The IT measures the fan-out: total wall-clock → throughput (msgs/sec) and
  per-send latency → p50/p95/p99 (collected in nanos, sorted, nearest-rank indexed), all logged in English.
- **The rule:** Virtual Threads are for fan-out concurrency only — they do NOT make a `JMSContext`/`Session`
  thread-safe. The correct composition is `Executors.newVirtualThreadPerTaskExecutor()` + one `JMSContext`
  per task from a `JmsPoolConnectionFactory`, **NOT** a shared `JMSContext`.

### JEP-491: `synchronized` no longer pins a blocking virtual thread

- Java 25 ships **JEP-491**: a `synchronized` block no longer **pins** a virtual thread that blocks while
  holding a monitor. Residual pinning on Java 25 is only **native/FFI frames** (e.g. the MQ client's native
  code), never `synchronized`.
- **Deterministic proof (headline):** start a programmatic JFR `jdk.jfr.Recording`, enable the
  `jdk.VirtualThreadPinned` event `.withoutThreshold()` (capture ALL pins, not just the default >20 ms) plus a
  `jdk.VirtualThreadStart` **positive control**, run a workload of virtual threads where EACH task enters a
  `synchronized (lock)` block and BLOCKS inside it (`Thread.sleep(...)` — the exact pre-JEP-491 pinning
  trigger: parking WHILE HOLDING A MONITOR), stop + dump the recording to a temp `.jfr`, read it with
  `jdk.jfr.consumer.RecordingFile`, and count the events. On Java 25 the pinned count **does NOT scale with the
  synchronized blocks** — it stays **well below the task count** (asserted `< taskCount/2`). On Java ≤ 21 this
  same workload pins on essentially every synchronized acquisition/park (≥ the task count) — that contrast is
  the evidence. The count is NOT asserted `== 0`: a few INCIDENTAL class-loading/native pins appear
  non-deterministically (observed 0..~handful) and are logged with their top frame. The `jdk.VirtualThreadStart`
  positive control (count `> 0`) proves the recording actually captured VT events, so a low pinned count is
  real evidence, not a silent instrumentation failure (renamed event / unstarted recording).
- **`-Djdk.tracePinnedThreads` is REMOVED in JDK 24+** — it is not available on Java 25. The supported
  observation path is the JFR `jdk.VirtualThreadPinned` event (via `jdk.jfr.Recording` /
  `jdk.jfr.consumer.RecordingFile`, the `jdk.jfr` standard module), used here.
- **Residual native pinning is evidence-only:** running the right-pattern MQ-I/O workload on virtual threads
  under the same JFR recording may surface a small number of `jdk.VirtualThreadPinned` events whose top frame
  is in the MQ client's NATIVE/FFI code (not `synchronized`). This is environment-dependent (host + driver
  build), so the IT **logs** these events (with their top stack frame) rather than asserting a count — a count
  assertion there would be flaky. The deterministic synchronized-block assertion (pinned count `< taskCount/2`,
  i.e. non-scaling) plus the `jdk.VirtualThreadStart` positive control (count `> 0`) are the hard assertions.

---

## 5. Sources

- Authentic jar bytecode: `com.ibm.mq.allclient:9.4.5.0` (`CMQC` / `MQConstants`), sha1-verified.
- Cross-checked: `ai/skills/jms-mq-delivery-report-analyzer/references/constants.md` §2 (same values).
- Authority gotcha: `research-output/coa-cod-validation-findings.md`, `CLAUDE.md` (report-PUT `+passid`).
- Field recovery: `research-output/phase-f-mqmd-field-recovery.md` (the `(R)`-all verdict).
- Broker-setup facts (§4): live-verified against `icr.io/ibm-messaging/mq:9.4.5.0-r2` during the issue #20
  Group A `ScenarioMatrixIT` implementation; the in-container broker-bounce note (`endmqm -i`/`strmqm`,
  `dspmq` poll, NOT `MQContainer.stop()/start()`) was added with the timing-sensitive `ScenarioMatrixSlowIT`
  (`@Tag("scenario")`, `mvn verify -Pscenarios` — `Expiration`/`Reconnect`/`PooledJmsInvalidation`).
- Virtual Threads on Java 25 (§4b): the Group B evidence (`VirtualThreadsEvidenceIT`, `@Tag("vt")`,
  `mvn verify -Pvt`) — right-vs-wrong concurrency pattern (one `JMSContext` per task from a
  `JmsPoolConnectionFactory` + VT fan-out, NOT a shared `JMSContext`) and the deterministic JEP-491 pinning
  boundary (programmatic JFR: the `jdk.VirtualThreadPinned` count does NOT scale with synchronized blocks,
  asserted `< taskCount/2` with a `jdk.VirtualThreadStart` positive control, for a synchronized-blocking VT workload;
  `-Djdk.tracePinnedThreads` removed in JDK 24+). Java 25 runtime decision: `docs/adr/0001-java-25-runtime.md`.
