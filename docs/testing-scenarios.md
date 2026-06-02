# Testing Scenarios Catalogue

Canonical catalogue of every **implemented** test scenario in `ibmmq-jms-guide/src/test/`.
Each entry maps to the test file(s) and method(s) that prove it.

For the test conventions (TDD-first, AssertJ, scope separation, `@DisplayName` in pt-BR)
see [`ibmmq-jms-guide/src/test/CLAUDE.md`](../ibmmq-jms-guide/src/test/CLAUDE.md).

---

## Template tiers

**Rich** — used for broker-dependent integration scenarios (IT scope). Includes:
- Scenario description, what it proves, and why it is designed the way it is.
- Pre-conditions (broker, queues, user).
- Step-by-step flow.
- Key assertions.
- Test file + method(s).
- Guide cross-references.

**Compact** — used for pure-logic unit scenarios (no broker). Includes:
- What it proves.
- Key assertion(s) and edge cases covered.
- Test file + method(s).

---

## Integration scenarios (broker-dependent, IT scope)

IT tests run under Maven failsafe (`mvn verify`). They start a real IBM MQ broker via
Testcontainers (`com.ibm.mq:mq-java-testcontainer:2.0.3` wrapping `MQContainer`); the
container lifecycle is managed manually with `@BeforeAll`/`@AfterAll`.

---

### IT-01 — COA + COD end-to-end delivery against a real IBM MQ broker

**Tier:** Rich

**Test file:**
`ibmmq-jms-guide/src/test/java/com/example/ibmmq/integration/CoaCodEndToEndIT.java`

**Test method:** `coaAndCodAreDelivered`

**What it proves:**
- A persistent JMS message sent with `MQRO_COA` + `MQRO_COD` report options and a
  `JMSReplyTo` reply queue causes the Queue Manager to generate both a **COA** (feedback
  `MQFB_COA` = 259) and a **COD** (feedback `MQFB_COD` = 260) on the report queue.
- Each report's `CorrelationId` equals the original message's `MessageId` (default
  `MQRO_COPY_MSG_ID_TO_CORREL_ID` propagation).
- The COD is only released after the consuming session calls `commit()` (transacted session
  semantics — the report does not flow on rollback).

**Why this test connects as `admin` (not `app`):**
The Queue Manager performs a PUT-with-context when delivering a COA/COD to the `ReplyToQ`.
This requires pass-identity context authority (`+passid`), which the low-privilege `app` user of the IBM
MQ developer image does not have (it holds only `put`+`browse` on `DEV.QUEUE.2`). If the test connected as
`app`, the report PUT would fail with `MQRC_NOT_AUTHORIZED` (2035) and the reports would be silently routed
to the DLQ, leaving the report queue empty. The `admin` user holds full authority. The live-verified fix
(see `CLAUDE.md`, "Report-PUT authority gotcha (2035) — CORRECTED on live k3s") is
`SET AUTHREC ... AUTHADD(PUT, PASSID, PASSALL, SETID, SETALL)` for the application principal — `+passid` is
the authority `app` is missing (NOT `+setall`, the prior stale assumption). This is the exact gotcha that
IT-07 reproduces deterministically.

**Pre-conditions:**
- Docker available on the test host.
- IBM MQ container image `icr.io/ibm-messaging/mq:9.4.5.0-r2` reachable (ICR pull or
  local cache).
- Queues `DEV.QUEUE.1` (business) and `DEV.QUEUE.2` (report) pre-created by the dev image.
- Connection via channel `DEV.ADMIN.SVRCONN` as user `admin` (full context authority).

**Step-by-step flow:**

1. **Produce** — a persistent `TextMessage` (`{"pedido":42}`) is sent to `DEV.QUEUE.1` with:
   - `JMSReplyTo = DEV.QUEUE.2`
   - `JMS_IBM_REPORT_COA = MQRO_COA` (256)
   - `JMS_IBM_REPORT_COD = MQRO_COD` (2048)
   - Delivery mode `PERSISTENT`

   The `JMSMessageID` assigned after the `send()` is captured as `originalMessageId`.

2. **Consume** — a `SESSION_TRANSACTED` context destructively receives from `DEV.QUEUE.1`
   within a 15-second timeout, then calls `ctx.commit()`. The commit releases the COD.

3. **Collect reports** — a polling loop on `DEV.QUEUE.2` runs for up to 30 seconds. Each
   received report is checked for `JMS_IBM_FEEDBACK` (259 = COA, 260 = COD) and for
   `JMSCorrelationID == originalMessageId`. The loop exits early when both `coaSeen` and
   `codSeen` are `true`.

**Key assertions:**
- `originalMessageId` is not null after the send.
- The business message is received within the timeout (not null).
- Every report's `CorrelationId` equals `originalMessageId`.
- `coaSeen == true` (COA, `MQFB_COA` = 259) within the 30-second window.
- `codSeen == true` (COD, `MQFB_COD` = 260) within the 30-second window.

**Guide cross-references:**
- Section 2.4 — identifier propagation (`MQRO_COPY_MSG_ID_TO_CORREL_ID`).
- Section 2.6 — timing × transaction: why `commit()` releases the COD.
- Section 2.7 — report persistence inheritance (persistent original → persistent reports).
- Section 5.1 — hybrid testing strategy and Testcontainers setup.
- Section 5.2(d) — the `+passid` / admin authority gotcha (why this test uses `admin`; corrected from `+setall`).

**Issue #19 extension — recovered MQMD fields:**
The same test now also enables MQMD read on its own report-queue consumer
(`queue:///DEV.QUEUE.2?mdReadEnabled=true`) and, for **each** arriving COA and COD, asserts the six
recovered MQMD values via `ReportDescriptor.from(report, type)`:
- **Strict** — `correlationIdBytes` non-empty (== original `MsgId` bytes under default propagation);
  `reportTypeChar == 'A'` for the COA / `'D'` for the COD; `putTimestampUtc` non-null and plausibly
  recent (within a ±10-minute window); `messageIdBytes` non-null (read-enabled).
- **Tolerant** — `applIdentityData` non-null (may be blank, QMgr-set); `accountingToken` non-null and
  exactly 32 bytes (`MQ_ACCOUNTING_TOKEN_LENGTH` = MQBYTE32; NOT 24, which is the MsgId/CorrelId length).

The original CorrelId == MsgId / both-reports-arrive assertions are unchanged (extended, not replaced).

---

### IT-02 — Recovered MQMD fields additively persisted to `delivery_report` (deterministic)

**Tier:** Rich

**Test file:**
`ibmmq-jms-guide/src/test/java/com/example/ibmmq/persistence/DeliveryReportPersistenceIT.java`

**Test method:** `coaAndCodPersistRecoveredMqmdFields`

**What it proves (issue #19, AC5):**
`ReportMessageConsumer.handleReport()` for a COA and a COD additively persists the six recovered MQMD
columns onto the issue-#40 `delivery_report` row — `appl_identity_data`, `accounting_token_hex`,
`correlation_id_bytes_hex`, `message_id_bytes_hex`, `put_timestamp_utc`, `report_type_char`. The dedup
key `(correlation_id, feedback)` is unchanged; the six columns are purely additive (idempotent
`ALTER TABLE ... ADD COLUMN IF NOT EXISTS` in `DeliveryReportSchema`, all NULLABLE).

**Why deterministic (no broker, single Postgres):**
Same harness as the issue-#40 persistence IT — one `GenericContainer` `postgres:16-alpine`, `default`
(writer) and `reader` pointing at the same instance, the report driven by a Mockito-mocked `Message`.
The six MQMD getters are stubbed with fixed fixtures so the persisted hex/timestamp/char values are
exactly asserted (no QMgr non-determinism).

**Pre-conditions:**
- Docker available; `postgres:16-alpine` reachable.
- `default` + `reader` datasources configured to the same container.

**Step-by-step flow:**
1. Stub a report `Message` (feedback 259, then 260) with `JMS_IBM_MQMD_ApplIdentityData`,
   `JMS_IBM_MQMD_AccountingToken` (32 bytes), `getJMSCorrelationIDAsBytes()`, `JMS_IBM_MQMD_MsgId`,
   `JMS_IBM_MQMD_PutDate = "20260531"`, `JMS_IBM_MQMD_PutTime = "13300050"`.
2. `handleReport(coa)` then `handleReport(cod)`.
3. Read both rows back via the `reader` repository (immediately consistent — single instance).

**Key assertions:**
- Both rows carry the same `appl_identity_data`, `accounting_token_hex` (64 hex chars), the two byte[]
  hex columns, and `put_timestamp_utc == 2026-05-31T13:30:00.500` (UTC wall-clock — no zone leakage).
- `report_type_char == "A"` on the COA row and `"D"` on the COD row.

**Guide cross-references:**
- Section 2.4 / 2.7 — id propagation and report descriptor.
- `research-output/phase-f-mqmd-field-recovery.md` — the (R)-all verdict + property keys + GMT→UTC rule.

---

### IT-03 — Report persistence inheritance (persistent original → persistent COA + COD)

**Tier:** Rich

**Test file:**
`ibmmq-jms-guide/src/test/java/com/example/ibmmq/integration/ScenarioMatrixIT.java`

**Nested class / method:** `PersistenceInheritance#persistentOriginalYieldsPersistentReports`

**What it proves (issue #20, Group A):**
A **PERSISTENT** original sent with `MQRO_COA` + `MQRO_COD` produces a COA and a COD that **both** report
`getJMSDeliveryMode() == DeliveryMode.PERSISTENT`. Reports **inherit** the original's persistence — this is
a validated fact that **refutes** the research brief's "reports are non-persistent by default" assumption.

**Why this lives in the shared-container matrix:**
`ScenarioMatrixIT` owns a single `static MQContainer` (`@BeforeAll`/`@AfterAll`); the three `@Nested`
scenarios share it (container boot is ~30-60 s — never one per scenario). A `@BeforeEach` drains
`DEV.QUEUE.1` and `DEV.QUEUE.2` so scenarios are order-independent (JUnit 5 does not order nested classes).

**Pre-conditions:**
- Docker available; `icr.io/ibm-messaging/mq:9.4.5.0-r2` reachable.
- Queues `DEV.QUEUE.1` (business) and `DEV.QUEUE.2` (report).
- Connection via `DEV.ADMIN.SVRCONN` as `admin` (full context authority for report generation).

**Step-by-step flow:**
1. Produce a PERSISTENT `TextMessage` to `DEV.QUEUE.1` with `JMSReplyTo = DEV.QUEUE.2`,
   `JMS_IBM_REPORT_COA = MQRO_COA`, `JMS_IBM_REPORT_COD = MQRO_COD`.
2. Destructively receive the original under a `SESSION_TRANSACTED` context and `commit()` (releases the COD).
3. Poll `DEV.QUEUE.2` (`?mdReadEnabled=true`) up to 30 s, classifying each report by `JMS_IBM_FEEDBACK`
   (259 = COA, 260 = COD) and capturing each report's `getJMSDeliveryMode()`.

**Key assertions (AssertJ):**
- A COA (259) and a COD (260) both arrive within the window.
- The COA's delivery mode is `PERSISTENT`.
- The COD's delivery mode is `PERSISTENT`.

**Guide cross-references:**
- Section 2.7 — report persistence inheritance.
- Section 5.2(d) — the admin/context-authority gotcha.
- `research-output/phase-g-report-options-and-scenarios.md` — Group A matrix.

---

### IT-04 — Syncpoint COA/COD timing (COA at PUT, COD only after the transacted commit)

**Tier:** Rich

**Test file:**
`ibmmq-jms-guide/src/test/java/com/example/ibmmq/integration/ScenarioMatrixIT.java`

**Nested class / method:** `SyncpointCoaCodTiming#codOnlyAppearsAfterTransactedCommit`

**What it proves (issue #20, Group A):**
The COA is generated at **PUT** time, the COD only after the consumer's **transacted commit**. Before the
original is consumed the report queue holds a COA (feedback 259) but **no** COD (260); after a
`SESSION_TRANSACTED` destructive get + `commit()`, a COD (260) appears.

**Why bounded polling (not a fixed sleep):**
Determinism for the default gate. The test polls with short receive timeouts until the COA arrives, then a
short additional sweep confirms no COD exists yet (the original is still on `DEV.QUEUE.1`, never consumed,
so no COD can have been generated). After the commit it polls again for the COD. No `Thread.sleep`.

**Pre-conditions:** same as IT-03 (shared container, admin connection, `DEV.QUEUE.1`/`DEV.QUEUE.2`).

**Step-by-step flow:**
1. Produce a PERSISTENT original with `MQRO_COA` + `MQRO_COD` and `JMSReplyTo = DEV.QUEUE.2`.
2. **Before** consuming: poll `DEV.QUEUE.2` until the COA arrives; a short sweep asserts no COD has appeared.
3. Destructively receive the original under a `SESSION_TRANSACTED` context and `commit()`.
4. **After** the commit: poll `DEV.QUEUE.2` until a COD (260) arrives.

**Key assertions (AssertJ):**
- A COA (259) is present **before** the original is consumed.
- **No** COD (260) exists before the transacted commit.
- A COD (260) appears **after** the commit.

**Guide cross-references:**
- Section 2.6 — timing × transaction (why `commit()` releases the COD).
- `research-output/phase-g-report-options-and-scenarios.md` — Group A matrix.

---

### IT-05 — COD with full data (the report body carries the original payload)

**Tier:** Rich

**Test file:**
`ibmmq-jms-guide/src/test/java/com/example/ibmmq/integration/ScenarioMatrixIT.java`

**Nested class / method:** `WithDataPayload#codWithFullDataCarriesOriginalPayload`

**What it proves (issue #20, Group A):**
Requesting `MQRO_COD_WITH_FULL_DATA` (verified bytecode value **14336**) makes the generated COD report
**carry the original message body**. The received COD report's body reproduces the original payload (a
`TextMessage` round-trips as the same text; a `BytesMessage`-form report carries the original bytes — the
test recovers the body either way and asserts it contains the original text).

**Why the with-data flag is set on a test-built message (not the production producer):**
Issue #19 deliberately kept the production `BusinessMessageProducer` on plain `MQRO_COD` to avoid the
`+setid/+setall` authority escalation (the report PUT needs context authority — see `CLAUDE.md`). This
scenario builds its own message and sends it over the IT's **admin** connection (which already holds full
context authority), mirroring how `CoaCodEndToEndIT` builds its own `TextMessage`. The production producer
is **unchanged**.

**Pre-conditions:** same as IT-03 (shared container, admin connection, `DEV.QUEUE.1`/`DEV.QUEUE.2`).

**Step-by-step flow:**
1. Produce a PERSISTENT `TextMessage` with `JMS_IBM_REPORT_COD = MQRO_COD_WITH_FULL_DATA` (14336) and
   `JMSReplyTo = DEV.QUEUE.2`.
2. Destructively receive the original under a `SESSION_TRANSACTED` context and `commit()`.
3. Poll `DEV.QUEUE.2` (`?mdReadEnabled=true`) up to 30 s for the COD (feedback 260) and recover its body.

**Key assertions (AssertJ):**
- The COD-with-full-data report body is recoverable (not null).
- The body **contains** the original message text (the full original payload was carried in the report).

**Guide cross-references:**
- Section 2.7 — report data options.
- `research-output/phase-g-report-options-and-scenarios.md` — `MQRO_*` with-data semantics + values.

---

### IT-06 — Field recovery on BOTH report kinds (COA and COD) — catalogue of the existing assertion

**Tier:** Rich

**Test file:**
`ibmmq-jms-guide/src/test/java/com/example/ibmmq/integration/CoaCodEndToEndIT.java`

**Method:** `coaAndCodAreDelivered` → `assertRecoveredMqmdFields(report, type, …)` (runs per report)

**What it proves (issue #20 AC "Field-recovery asserted on both report kinds"):**
This AC is **already satisfied** by `CoaCodEndToEndIT` and is **not** duplicated in `ScenarioMatrixIT`.
Inside the report-collection loop, `assertRecoveredMqmdFields(...)` is invoked **per report** (gated by
`coaSeen`/`codSeen`), so the six recovered MQMD fields are asserted for **both** the COA and the COD:

- `applIdentityData` — recovered (read-enabled; may be blank, QMgr-set).
- `accountingToken` — recovered, **32 bytes** (`MQ_ACCOUNTING_TOKEN_LENGTH`; may be the QMgr default token).
- `correlationIdBytes` — non-empty; `== original MsgId` bytes under default `MQRO_COPY_MSG_ID_TO_CORREL_ID`.
- `messageIdBytes` — the report's own MsgId, recovered (read-enabled).
- `putTimestampUtc` — non-null and plausibly recent (±10-minute window).
- `reportTypeChar` — `'A'` for the COA, `'D'` for the COD.

**Why catalogued here (not re-implemented):**
The field-recovery scenario is the Group C catalogue entry for issue #20. `CoaCodEndToEndIT` already runs
the six-field assertion against a real broker for both report kinds (it predates the AssertJ convention and
uses raw JUnit asserts — left as-is, not retrofitted). Duplicating it would add cost without coverage.

**Guide cross-references:**
- IT-01 (above) — the parent end-to-end scenario this assertion lives inside.
- `research-output/phase-f-mqmd-field-recovery.md` — the (R)-all field-recovery verdict.
- `research-output/phase-g-report-options-and-scenarios.md` — Group A/C matrix overview.

---

### IT-07 — Report-PUT authority gotcha (`app` lacks `passid` → report dead-letters)

**Tier:** Rich

**Test file:**
`ibmmq-jms-guide/src/test/java/com/example/ibmmq/integration/ScenarioMatrixIT.java`

**Nested class / method:** `ReportPutAuthToDlq#reportFromLowPrivAppDeadLetters`

**What it proves (issue #20, Group A):**
When the **low-privilege `app` user** produces an original requesting a **COA** with
`JMSReplyTo = DEV.QUEUE.2`, the Queue Manager's report PUT-with-context onto the ReplyToQ fails
`MQRC_NOT_AUTHORIZED` (2035) and the report is routed to **`DEV.DEAD.LETTER.QUEUE`** — it never reaches the
ReplyToQ. This is the live-verified report-PUT authority gotcha: the dev image grants `app` only
**`put`+`browse`** on `DEV.QUEUE.2` — **no `passid`** (pass-identity context) — which is exactly the
authority the QMgr needs to PUT the report with the original's context.

**Why this scenario connects as `app` (the only one that does):**
Every other scenario connects as `admin` (full context authority) precisely so reports are NOT refused.
This scenario builds a **second** connection factory on channel `DEV.APP.SVRCONN` as user `app` to
reproduce the failure path. The COA is requested (not COD) because a COA fires at **PUT** time, so the
unauthorized report PUT — and the dead-lettering — happens immediately, with **no consume step needed**.
The original is sent `PERSISTENT` so the report inherits persistence and is **dead-lettered** (not silently
discarded) when its ReplyToQ PUT is refused.

**Why the assertions read CURDEPTH via `runmqsc` (not JMS):**
The robust, header-free proof. Parsing the `MQDLH` dead-letter header in JMS is brittle; instead the test
runs `DIS QLOCAL(name) CURDEPTH` via `mq.execInContainer("bash","-c", "… | runmqsc QM1")` and parses
`CURDEPTH(N)`. Dead-lettering is async, so the DLQ depth is polled with a bounded budget (≤ 20 s).

**Pre-conditions:**
- Docker available; `icr.io/ibm-messaging/mq:9.4.5.0-r2` reachable.
- Queues `DEV.QUEUE.1`, `DEV.QUEUE.2`, and `DEV.DEAD.LETTER.QUEUE` (all pre-created by the dev image).
- A `DEV.APP.SVRCONN` / `app` connection (low privilege — **no `passid`** on `DEV.QUEUE.2`).
- The shared `@BeforeEach` also `CLEAR`s `DEV.DEAD.LETTER.QUEUE` so a prior scenario's dead-letter cannot
  bleed into this scenario's CURDEPTH assertion.

**Step-by-step flow:**
1. As `app`, produce a PERSISTENT original to `DEV.QUEUE.1` with `JMS_IBM_REPORT_COA = MQRO_COA` and
   `JMSReplyTo = DEV.QUEUE.2`.
2. Poll `DEV.DEAD.LETTER.QUEUE` CURDEPTH (via `runmqsc`) up to 20 s until it reaches ≥ 1.

**Key assertions (AssertJ):**
- `DEV.QUEUE.2` CURDEPTH `== 0` (no report reached the ReplyToQ).
- `DEV.DEAD.LETTER.QUEUE` CURDEPTH `>= 1` (the refused report dead-lettered).

**Guide cross-references:**
- Section 5.2(d) — the context-authority (`+passid`) gotcha and the `admin` workaround used elsewhere.
- `research-output/phase-g-report-options-and-scenarios.md` §"Broker-setup facts" — the live-verified
  `app` authority + DLQ name.
- `CLAUDE.md` — report-PUT authority gotcha (`+passid`, corrected from `+setall`).

---

### IT-08 — Poison-message backout (after `BOTHRESH` rollbacks → requeued to `BOQNAME`)

**Tier:** Rich

**Test file:**
`ibmmq-jms-guide/src/test/java/com/example/ibmmq/integration/ScenarioMatrixIT.java`

**Nested class / method:** `PoisonMessageBackout#poisonMessageMovesToBackoutQueueAfterThreshold`

**What it proves (issue #20, Group A):**
A message repeatedly **rolled back** under a transacted session is, after `BOTHRESH` backouts, **requeued
by the QMgr to `BOQNAME`** (the backout queue). With `SCENARIO.POISON.Q` defined `BOTHRESH(3)
BOQNAME(SCENARIO.BACKOUT.Q)`, three rollbacks move the poison message off `SCENARIO.POISON.Q` and onto
`SCENARIO.BACKOUT.Q`. The test also proves `JMSXDeliveryCount` **increments** across the redeliveries.

**Why one `SESSION_TRANSACTED` context, bounded loop, and a final `commit()`:**
`BackoutCount` lives on the MQMD; one consumer under one transacted context is the cleanest harness. The
loop is bounded (`BOTHRESH + 2`) so it always terminates: each iteration `receive()`s, reads
`JMSXDeliveryCount` (strictly greater than the previous), then `rollback()`s; when a `receive()` returns
null the QMgr has already requeued the message to `BOQNAME`. The loop ends with `commit()` (never
`rollback()`) — the requeue PUT may ride the current unit of work, and `commit()` is safe whether or not it
does. CURDEPTH is read **after** the context closes.

**Why queues are defined in `@BeforeAll`:**
The dev image does not pre-create `SCENARIO.*`. A `@BeforeAll`-time `runmqsc` `DEFINE QLOCAL(... ) REPLACE`
(verified — returns `AMQ8006I`) creates `SCENARIO.POISON.Q` (`BOTHRESH(3)`, `BOQNAME(SCENARIO.BACKOUT.Q)`)
and `SCENARIO.BACKOUT.Q` once, after the container is up. The `@BeforeEach` `CLEAR`s both so the scenario is
order-independent.

**Pre-conditions:**
- Docker available; `icr.io/ibm-messaging/mq:9.4.5.0-r2` reachable.
- `SCENARIO.POISON.Q` (`BOTHRESH(3)`, `BOQNAME(SCENARIO.BACKOUT.Q)`) and `SCENARIO.BACKOUT.Q` defined.
- Connection via `DEV.ADMIN.SVRCONN` as `admin` (full authority).

**Step-by-step flow:**
1. PUT one PERSISTENT message onto `SCENARIO.POISON.Q`.
2. Under a `SESSION_TRANSACTED` context, loop: `receive()`, assert `JMSXDeliveryCount` increased,
   `rollback()`; break when `receive()` returns null (the message has been requeued to the backout queue).
   End with `commit()`.
3. Poll CURDEPTH (via `runmqsc`) after the context closes.

**Key assertions (AssertJ):**
- `SCENARIO.BACKOUT.Q` CURDEPTH `>= 1` (the poison message was requeued).
- `SCENARIO.POISON.Q` CURDEPTH `== 0` (the message left the poison queue).
- The maximum observed `JMSXDeliveryCount` `>= 2` (the delivery count climbed across redeliveries).

**Guide cross-references:**
- `mqsc/20-queues.mqsc` — the `BOTHRESH`/`BOQNAME` backout-queue concept.
- `research-output/phase-g-report-options-and-scenarios.md` §"Broker-setup facts" — `runmqsc DEFINE QLOCAL
  … BOTHRESH … BOQNAME` verified working in-container.

---

### IT-09 — Queue full (`PUT` past `MAXDEPTH` fails `MQRC_Q_FULL` 2053)

**Tier:** Rich

**Test file:**
`ibmmq-jms-guide/src/test/java/com/example/ibmmq/integration/ScenarioMatrixIT.java`

**Nested class / method:** `QueueFull#thirdPutOnMaxDepth2QueueFailsWithQueueFull`

**What it proves (issue #20, Group A):**
A `PUT` onto a queue already at its `MAXDEPTH` fails with `MQRC_Q_FULL` (2053). With `SCENARIO.FULL.Q`
defined `MAXDEPTH(2)`, the first two `send()`s succeed and the **third** `send()` throws, carrying MQ reason
**2053**.

**Why a non-transacted `AUTO_ACKNOWLEDGE` context and `hasStackTraceContaining("2053")`:**
Under a transacted session a queue-full could defer to `commit()`; a non-transacted `AUTO_ACKNOWLEDGE`
context makes the third `send()` throw **synchronously**. The JMS 2.0 simplified API's
`JMSProducer.send()` throws `javax.jms.JMSRuntimeException` — which does **not** extend `JMSException` and
has **no** `getLinkedException()`, so the AssertJ assertion is `assertThatThrownBy(...)` +
`hasStackTraceContaining("2053")` (walks the full cause chain to the linked `MQException` carrying MQRC
2053), tolerant of exactly how 2053 surfaces. The assertion does **not** pin the exception type.

**Why the queue is defined in `@BeforeAll`:**
Same as IT-08 — `runmqsc DEFINE QLOCAL(SCENARIO.FULL.Q) MAXDEPTH(2) REPLACE` (verified) creates it once
after the container is up; the `@BeforeEach` `CLEAR`s it.

**Pre-conditions:**
- Docker available; `icr.io/ibm-messaging/mq:9.4.5.0-r2` reachable.
- `SCENARIO.FULL.Q` defined `MAXDEPTH(2)`.
- Connection via `DEV.ADMIN.SVRCONN` as `admin` (full authority).

**Step-by-step flow:**
1. Under a non-transacted `AUTO_ACKNOWLEDGE` context, `send()` two PERSISTENT messages (fills `MAXDEPTH(2)`).
2. `send()` a third message and assert the throw.
3. Read CURDEPTH (via `runmqsc`) to confirm the queue is still exactly at `MAXDEPTH`.

**Key assertions (AssertJ):**
- The third `send()` throws an exception whose stack trace contains `2053` (`MQRC_Q_FULL`).
- `SCENARIO.FULL.Q` CURDEPTH `== 2` (the overflow PUT did not land).

**Guide cross-references:**
- `research-output/phase-g-report-options-and-scenarios.md` §"Broker-setup facts" — `runmqsc DEFINE QLOCAL
  … MAXDEPTH` verified working in-container; `JMSRuntimeException` (not `JMSException`) for the simplified API.
- UT-01 — `ReportFeedbackRouter` maps 2053 (`MQRC_Q_FULL`) to `EXCEPTION` (the unit-side mirror of this code).

---

### Timing-sensitive Group A scenarios — `@Tag("scenario")`, run via `mvn verify -Pscenarios`

The next three scenarios (IT-10..IT-12) are the **timing-sensitive** Group A tier. They live in a SEPARATE,
tag-gated class `ScenarioMatrixSlowIT` annotated `@Tag("scenario")` at the class level (JUnit 5 inherits the
tag to its `@Nested` scenarios). The default `mvn verify` gate **excludes** them (the base failsafe config
sets `<excludedGroups>replication,scenario</excludedGroups>`); they run **on demand** via
`mvn verify -Pscenarios` (the `scenarios` profile inverts the filter with `<groups>scenario</groups>` +
the `<excludedGroups>none</excludedGroups>` sentinel — an EMPTY excludedGroups would NOT override the
inherited value). Like `ScenarioMatrixIT` they share ONE `static MQContainer`; the reconnect/pool scenarios
**bounce the Queue Manager inside the running container** (`endmqm -i QM1` / `strmqm QM1` via
`execInContainer`, then poll `dspmq` for `STATUS(Running)`) — NOT `MQContainer.stop()/start()`, which would
remap the random host port and defeat client auto-reconnect. Each scenario leaves the QMgr Running; a
`@BeforeEach` re-ensures Running and drains `DEV.QUEUE.1/2/3`.

---

### IT-10 — Expiration report (TTL-expired message → `MQRO_EXPIRATION`, feedback 258)

**Tier:** Rich

**Test file:**
`ibmmq-jms-guide/src/test/java/com/example/ibmmq/integration/ScenarioMatrixSlowIT.java`

**Nested class / method:** `Expiration#expiredMessageYieldsExpirationReport`

**Gating:** `@Tag("scenario")` (class-level) — runs via `mvn verify -Pscenarios`; excluded from default `mvn verify`.

**What it proves (issue #20, Group A, timing tier):**
A **NON-persistent** message sent to `DEV.QUEUE.3` with a short time-to-live (`setTimeToLive(2000)` = 2 s)
and an expiration report requested (`JMS_IBM_REPORT_EXPIRATION = MQRO_EXPIRATION`, `JMSReplyTo = DEV.QUEUE.2`)
yields an **expiration report** (feedback `MQFB_EXPIRATION` = 258) on the report queue once the TTL elapses.
The report's `CorrelationId` equals the original `MessageId` (default `MQRO_COPY_MSG_ID_TO_CORREL_ID`).

**Why a destructive GET forces the expiry (no background scan):**
`ALTER QMGR EXPRYINT(...)` is a **syntax error** in MQ 9.4.5 (the attribute does not exist), so there is no
background expiry scan to rely on. The test waits out the TTL (a bounded `Thread.sleep`, > 2 s) and then does
a **destructive GET** on `DEV.QUEUE.3` (expecting `null` — the message is gone): the QMgr discards the expired
message **during the GET scan** and only **then** generates the `MQRO_EXPIRATION` report onto the ReplyToQ.
A bounded poll on `DEV.QUEUE.2` (`?mdReadEnabled=true`) collects it.

**Pre-conditions:**
- Docker available; `icr.io/ibm-messaging/mq:9.4.5.0-r2` reachable.
- Queues `DEV.QUEUE.3` (the TTL original's home) and `DEV.QUEUE.2` (report), both pre-created by the dev image.
- Connection via `DEV.ADMIN.SVRCONN` as `admin` (full context authority for report generation).

**Step-by-step flow:**
1. Produce a NON-persistent `TextMessage` to `DEV.QUEUE.3` with `setTimeToLive(2000)`,
   `JMS_IBM_REPORT_EXPIRATION = MQRO_EXPIRATION`, `JMSReplyTo = DEV.QUEUE.2`; capture `JMSMessageID`.
2. Sleep > TTL (bounded ~3 s), then do a destructive `receive(2000)` on `DEV.QUEUE.3` — assert it is `null`
   (the message expired; the GET scan triggered the report).
3. Poll `DEV.QUEUE.2` (`?mdReadEnabled=true`) up to 30 s for a report with `JMS_IBM_FEEDBACK == 258`.

**Key assertions (AssertJ):**
- The destructive GET on `DEV.QUEUE.3` returns `null` (the TTL-expired message is gone).
- An expiration report (feedback `MQFB_EXPIRATION` = 258) arrives on `DEV.QUEUE.2`.
- The report's `CorrelationId` equals the original `MessageId`.

**Guide cross-references:**
- `research-output/phase-g-report-options-and-scenarios.md` §4 — the `EXPRYINT` syntax-error fact and the
  post-TTL destructive-GET technique to force expiry.
- IT-01 — `MQRO_COPY_MSG_ID_TO_CORREL_ID` id propagation (same CorrelId == original MsgId rule).

---

### IT-11 — Client auto-reconnect (round-trip survives a Queue Manager bounce)

**Tier:** Rich

**Test file:**
`ibmmq-jms-guide/src/test/java/com/example/ibmmq/integration/ScenarioMatrixSlowIT.java`

**Nested class / method:** `Reconnect#roundTripSurvivesQueueManagerBounce`

**Gating:** `@Tag("scenario")` (class-level) — runs via `mvn verify -Pscenarios`; excluded from default `mvn verify`.

**What it proves (issue #20, Group A, timing tier):**
With client auto-reconnect enabled on the CF
(`WMQ_CLIENT_RECONNECT_OPTIONS = WMQ_CLIENT_RECONNECT`, `WMQ_CLIENT_RECONNECT_TIMEOUT = 30`), a `send` +
`receive` round-trip through `DEV.QUEUE.1` **succeeds** after a **Queue Manager bounce** — the client
reconnects to the recovered broker on the same `host:port`. The test holds a `JMSContext` open across the
bounce to exercise the held-context auto-reconnect path; if that context's reconnect window lapsed during the
outage (the client begins reconnecting the instant `endmqm` breaks the connection, before the listener is
back), the test transparently falls back to a **fresh** context against the same endpoint. Either path proves
the client reconnects to the recovered broker; the test does not distinguish them, so it is robust rather than
flaky (it does not assert that the held context specifically auto-reconnected).

**Why the QMgr is bounced IN-container (not `MQContainer.stop()/start()`):**
Testcontainers publishes the MQ listener on a **random host port**; stopping/starting the container would
**remap** it, so a reconnecting client could never reach the same endpoint. Instead the test bounces only the
QMgr inside the still-running container — `endmqm -i QM1` then `strmqm QM1` via `execInContainer`, polling
`dspmq` for `STATUS(Running)` — so the container's port mapping stays stable and auto-reconnect actually
works. The scenario leaves the QMgr Running for its siblings.

**Pre-conditions:**
- Docker available; `icr.io/ibm-messaging/mq:9.4.5.0-r2` reachable.
- Queue `DEV.QUEUE.1` (business), pre-created by the dev image.
- Connection via `DEV.ADMIN.SVRCONN` as `admin`.

**Step-by-step flow:**
1. Build the CF with `WMQ_CLIENT_RECONNECT_OPTIONS = WMQ_CLIENT_RECONNECT` and a bounded
   `WMQ_CLIENT_RECONNECT_TIMEOUT` (30 s); open a `JMSContext` and hold it open.
2. Bounce the QMgr (`endmqm -i QM1` / `strmqm QM1`, poll `dspmq` → `STATUS(Running)`).
3. Wait for the SVRCONN listener to actually accept a connection (`awaitListenerReady()` — `dspmq STATUS(Running)`
   does not mean the listener is bound to the port yet), so the round-trip below is not racing listener startup.
4. `send` a `TextMessage` to `DEV.QUEUE.1` then `receive` it (10 s inner receive, retried under a 30 s budget),
   preferring the held context and falling back to a fresh context if its reconnect window lapsed.

**Key assertions (AssertJ):**
- The post-bounce round-trip body equals the sent payload — the client reconnected to the recovered broker
  (via held-context auto-reconnect or the fresh-context fallback).

**Guide cross-references:**
- `research-output/phase-a-fact-sheet.md` — `WMQ_CLIENT_RECONNECT*` field names + values (bytecode-verified).
- `research-output/phase-g-report-options-and-scenarios.md` §4 — in-container broker-bounce technique.

---

### IT-12 — Pooled-JMS stale-connection invalidation (second borrow yields a working connection)

**Tier:** Rich

**Test file:**
`ibmmq-jms-guide/src/test/java/com/example/ibmmq/integration/ScenarioMatrixSlowIT.java`

**Nested class / method:** `PooledJmsInvalidation#secondBorrowAfterBounceYieldsWorkingConnection`

**Gating:** `@Tag("scenario")` (class-level) — runs via `mvn verify -Pscenarios`; excluded from default `mvn verify`.

**What it proves (issue #20, Group A, timing tier):**
A `org.messaginghub.pooled.jms.JmsPoolConnectionFactory` fronting the MQ `MQConnectionFactory` **detects and
replaces a stale physical connection**. After a first borrow round-trips a message, the Queue Manager is
bounced (so the pooled physical connection goes dead); a **second** borrow from the same pool yields a
**working** connection — a fresh `send`/`receive` succeeds because pooled-jms discarded the dead connection
and established a new one.

**Why the QMgr is bounced IN-container:**
Same reason as IT-11 — bouncing only the QMgr (`endmqm -i QM1` / `strmqm QM1`, poll `dspmq`) keeps the
container's host port stable so the pool's replacement connection re-establishes against the same endpoint;
`MQContainer.stop()/start()` would remap the port. The pool is closed in a `finally` (`pool.stop()`), and the
scenario leaves the QMgr Running for its siblings.

**Pre-conditions:**
- Docker available; `icr.io/ibm-messaging/mq:9.4.5.0-r2` reachable.
- Queue `DEV.QUEUE.1` (business), pre-created by the dev image.
- Connection via `DEV.ADMIN.SVRCONN` as `admin`; `pooled-jms` 2.0.9 on the classpath (a project dependency).

**Step-by-step flow:**
1. Build a `JmsPoolConnectionFactory`, `setConnectionFactory(mqCf)`, `setMaxConnections(2)`.
2. Borrow a context from the pool, round-trip a message through `DEV.QUEUE.1` (asserts the pooled connection works).
3. Bounce the QMgr (`endmqm -i QM1` / `strmqm QM1`, poll `dspmq` → `STATUS(Running)`) so the pooled connection goes stale.
4. Borrow AGAIN from the pool and round-trip a message — assert it succeeds. `pool.stop()` in a `finally`.

**Key assertions (AssertJ):**
- The pre-bounce pooled round-trip is **not null**.
- The post-bounce **second** borrow round-trips successfully (**not null**, body matches) — pooled-jms
  replaced the stale connection.

**Guide cross-references:**
- `research-output/pooled-jms-factory-tuning.md` — `JmsPoolConnectionFactory` semantics and tuning.
- `research-output/phase-g-report-options-and-scenarios.md` §4 — in-container broker-bounce technique.
- `docs/adr/0006-role-based-connection-factories.md` — the role-based factory topology this pool fronts.

---

### Virtual-Thread evidence Group B scenarios — `@Tag("vt")`, run via `mvn verify -Pvt`

The next two scenarios (IT-13..IT-14) are the **Virtual-Thread evidence** tier (issue #20 AC: "Virtual-Thread
throughput/latency measured and the pinning boundary demonstrated"). They live in a SEPARATE, tag-gated class
`VirtualThreadsEvidenceIT` annotated `@Tag("vt")` at the class level (JUnit 5 inherits the tag to its
`@Nested` evidence groups). They are **purely test-source evidence** — production uses NO Virtual Threads (a
single platform thread per pod + blocking JMS `receive()`, parallelism by Kubernetes replicas); nothing here
changes production behaviour. The default `mvn verify` gate **excludes** them (the base failsafe config sets
`<excludedGroups>replication,scenario,vt</excludedGroups>`); they run **on demand** via `mvn verify -Pvt`
(the `vt` profile inverts the filter with `<groups>vt</groups>` + the `<excludedGroups>none</excludedGroups>`
sentinel — an EMPTY excludedGroups would NOT override the inherited value). Like the sibling matrix ITs they
share ONE `static MQContainer`. Every workload is **bounded** (~300 messages) — a short micro-measurement, NOT
a sustained load run (sustained ~167 msg/s on a cluster is the deferred slice #21).

---

### IT-13 — Virtual-Thread concurrency pattern: right vs wrong + throughput/latency measured

**Tier:** Rich

**Test file:**
`ibmmq-jms-guide/src/test/java/com/example/ibmmq/integration/VirtualThreadsEvidenceIT.java`

**Nested class / method:**
`RightVsWrongConcurrencyPattern#perTaskContextIsCorrectWhileSharedContextIsAnUnsafeAntiPattern` — one comparative
method that runs BOTH patterns (shared-context wrong, per-task-context right) and measures each.

**Gating:** `@Tag("vt")` (class-level) — runs via `mvn verify -Pvt`; excluded from default `mvn verify`.

**What it proves (issue #20, Group B):**
`JMSContext`/`Session` are NOT thread-safe (JMS 2.0). The **wrong** pattern — ONE shared `JMSContext` whose
`createProducer().send(...)` is called concurrently by N (300) virtual threads — VIOLATES that contract. The
**right** pattern — front the MQ `MQConnectionFactory` with a `JmsPoolConnectionFactory` (`maxConnections=8`)
and give EACH virtual thread its OWN `JMSContext` drawn from the pool (try-with-resources), using virtual
threads only for fan-out — delivers exactly N messages with no race, and the test **measures** the fan-out:
total wall-clock → throughput (msgs/sec) and per-send latency → p50/p95/p99 (collected in nanos, sorted,
nearest-rank indexed). Both patterns' throughputs are logged in English for the right-vs-wrong comparison.

**Empirical finding — why the wrong pattern is evidence-only (no throughput-winner assertion):**
With the IBM MQ allclient 9.4.5 the anti-pattern does NOT manifest as corruption or lost sends: the client
SERIALIZES internal session access, so all 300 messages are delivered and no task throws (`CURDEPTH(VT.WRONG.Q)
== 300`, `threw=false`). It also does not reliably lose on throughput — the one lean, long-lived shared session
often OUT-runs the per-task pattern (which pays pool borrow/create/close churn, ~26 ms p50 per send), so the
right-vs-wrong throughput ordering is **non-deterministic** across runs (observed both orders). The shared
context is WRONG regardless: it relies on undefined, provider-/version-specific behaviour (a spec-strict
provider could corrupt or throw) and cannot scale beyond one session. The IT therefore runs both patterns,
LOGS both throughputs as measured evidence, and asserts ONLY the per-task pattern's correctness — it
deliberately does not assert a throughput winner (flaky and overclaiming). A `CountDownLatch` barrier releases
all virtual threads at once.

**Why the right pattern is one `JMSContext` per task from a pool (not a shared context):**
Virtual threads are cheap fan-out, but a JMS `JMSContext`/`Session` must not be shared across threads. Drawing
one `JMSContext` per task from a bounded pool gives each task a safe, reused physical connection — the correct
composition of `Executors.newVirtualThreadPerTaskExecutor()` with a `JmsPoolConnectionFactory`. The test also
asserts `Thread.currentThread().isVirtual()` inside every task (the fan-out genuinely runs on virtual threads).

**Pre-conditions:**
- Docker available; `icr.io/ibm-messaging/mq:9.4.5.0-r2` reachable.
- Dedicated queues `VT.WRONG.Q` and `VT.RIGHT.Q` (defined once in `@BeforeAll` via `runmqsc DEFINE QLOCAL ...
  REPLACE`; `@BeforeEach` `CLEAR`s both for order-independence).
- Connection via `DEV.ADMIN.SVRCONN` as `admin`; `pooled-jms` 2.0.9 on the classpath.

**Step-by-step flow (one comparative method, wrong then right):**
1. **Wrong** — open ONE `JMSContext`; submit 300 tasks to a virtual-thread-per-task executor, each calling
   `sharedCtx.createProducer().send(VT.WRONG.Q, ...)` after a shared barrier; measure the run's wall-clock →
   throughput, record any throwables and the delivered `CURDEPTH`, and log the observed behaviour. Drain the queue.
2. **Right** — build a `JmsPoolConnectionFactory` (`maxConnections=8`); submit 300 tasks, each
   `try (JMSContext c = pool.createContext(...)) { c.createProducer().send(VT.RIGHT.Q, ...); }`, timing each
   send; measure wall-clock throughput + p50/p95/p99; `pool.stop()` in a `finally`; drain the queue.

**Key assertions (AssertJ):**
- **Wrong** — evidence-only: the run's throughput is recorded (`>= 0`) and logged; no winner is asserted (the
  ordering is non-deterministic — see the empirical finding above).
- **Right** — no task threw; every task ran on a virtual thread; exactly 300 messages delivered
  (`CURDEPTH == 300`); throughput `> 0`; a latency sample collected for every delivered message.

**Guide cross-references:**
- `research-output/pooled-jms-factory-tuning.md` — `JmsPoolConnectionFactory` semantics and `maxConnections`.
- `research-output/phase-g-report-options-and-scenarios.md` §"Virtual Threads on Java 25 (JEP-491)" — the
  right-vs-wrong pattern and why one `JMSContext` per task from a pool is correct.
- `docs/adr/0006-role-based-connection-factories.md` — the role-based factory topology the pool fronts.

---

### IT-14 — JEP-491 pinning boundary on Java 25 (synchronized no longer pins a blocking virtual thread)

**Tier:** Rich

**Test file:**
`ibmmq-jms-guide/src/test/java/com/example/ibmmq/integration/VirtualThreadsEvidenceIT.java`

**Nested class / methods:**
`PinningBoundary#synchronizedBlockingDoesNotPinOnJava25` (deterministic headline result) and
`PinningBoundary#mqIoOnVirtualThreadsUnderJfrLogsResidualNativePinning` (supporting, evidence-only).

**Gating:** `@Tag("vt")` (class-level) — runs via `mvn verify -Pvt`; excluded from default `mvn verify`.

**What it proves (issue #20, Group B):**
Java 25 ships **JEP-491** — `synchronized` no longer **pins** a virtual thread that blocks while holding a
monitor (residual pinning is only native/FFI frames). The deterministic test starts a programmatic JFR
`Recording`, enables `jdk.VirtualThreadPinned` `.withoutThreshold()` (capture ALL pins) plus a
`jdk.VirtualThreadStart` **positive control**, runs 64 virtual threads that EACH enter a `synchronized (lock)`
block and `Thread.sleep(...)` inside it (the exact pre-JEP-491 pinning trigger: parking WHILE HOLDING A
MONITOR), stops + dumps the recording to a temp `.jfr`, reads it with `RecordingFile`, and counts the events.
The pinned count is asserted to **NOT scale with the synchronized blocks** — strictly **less than half the
task count**. On Java ≤ 21 this same workload pins on essentially every synchronized acquisition/park (≥ the
task count); on Java 25 synchronized no longer pins, so only a few INCIDENTAL class-loading/native pins remain
(observed 0..~handful, non-deterministic), each logged with its top frame. An "exactly 0" assertion would be
flaky (incidental pins vary run-to-run); "does not scale" is the deterministic claim. The positive control
(`jdk.VirtualThreadStart` count `> 0`) proves the recording actually captured VT events, so a low pinned count
is real evidence, not a silent instrumentation failure.

**Why programmatic JFR (NOT `-Djdk.tracePinnedThreads`):**
The `-Djdk.tracePinnedThreads` flag was **REMOVED in JDK 24+**, so it is not an option on Java 25.
`jdk.jfr.Recording` + `jdk.jfr.consumer.RecordingFile` (the `jdk.jfr` standard module) is the supported,
deterministic observation path: enable the event with no threshold so even a brief pin would be captured,
then count events whose `getEventType().getName().equals("jdk.VirtualThreadPinned")`.

**Why the second method is evidence-only (no count assertion):**
The supporting method runs the right-pattern MQ-I/O workload (one pooled `JMSContext` per task) on virtual
threads under the same JFR recording and **logs** any `jdk.VirtualThreadPinned` events with their top stack
frame. Any residual pinning on Java 25 is environment-dependent and lives in the MQ client's NATIVE/FFI
frames (not `synchronized`), so a count assertion would be flaky across hosts/driver builds — it observes and
reports instead, asserting only that the MQ I/O workload itself delivered all 300 messages.

**Pre-conditions:**
- Docker available; `icr.io/ibm-messaging/mq:9.4.5.0-r2` reachable.
- Java 25 runtime (JEP-491; `-Djdk.tracePinnedThreads` removed in JDK 24+).
- `jdk.jfr` standard module (always present on Java 25); JFR temp files are cleaned up in a `finally`.
- `VT.RIGHT.Q` defined (shared with IT-13); connection via `DEV.ADMIN.SVRCONN` as `admin`.

**Step-by-step flow:**
1. **Deterministic** — start a `Recording`, `enable("jdk.VirtualThreadPinned").withoutThreshold()` +
   `enable("jdk.VirtualThreadStart")` (positive control); run 64 virtual threads each blocking
   (`Thread.sleep(20)`) inside `synchronized (lock)`; stop + dump to a temp `.jfr`; read both event counts via
   `RecordingFile` and log each residual pin's top frame.
2. **Evidence-only** — under a JFR recording, run 300 virtual threads each sending via its own pooled
   `JMSContext` to `VT.RIGHT.Q`; log any `jdk.VirtualThreadPinned` events + top stack frame; drain the queue.

**Key assertions (AssertJ):**
- **Positive control** — the `jdk.VirtualThreadStart` count is `> 0` (the recording actually captured VT
  events; otherwise a low pinned count would be meaningless instrumentation failure).
- **Deterministic** — the `jdk.VirtualThreadPinned` count is **less than half the task count** (`< 32` for the
  64 tasks): on Java 25 synchronized-blocking does not pin, so pins do not scale per synchronized-block — only
  a few incidental class-loading/native pins remain. (NOT `== 0` — incidental pins are non-deterministic.)
- **Evidence-only** — the MQ I/O workload delivered all 300 messages (no count assertion on pinned events).

**Guide cross-references:**
- `research-output/phase-g-report-options-and-scenarios.md` §"Virtual Threads on Java 25 (JEP-491)" — the
  deterministic-JFR pinning-count fact, residual native/FFI pinning, and the `-Djdk.tracePinnedThreads`
  removal in JDK 24+.
- `docs/adr/0001-java-25-runtime.md` — the Java 25 (LTS) runtime decision that makes JEP-491 applicable.

---

## Unit scenarios (no broker, surefire scope)

Unit tests run under Maven surefire (`mvn test`). They use JUnit 5 + Mockito + AssertJ; no
broker or container is started.

---

### UT-01 — Feedback code → `ReportType` mapping (all known `MQFB_*` values)

**Tier:** Compact

**Test file:**
`ibmmq-jms-guide/src/test/java/com/example/ibmmq/report/ReportFeedbackRouterTest.java`

**Class under test:** `com.example.ibmmq.report.ReportFeedbackRouter`

**What it proves:** `ReportFeedbackRouter.classify(int)` maps each known `MQFB_*` integer
(extracted from bytecode of `CMQC` 9.4.5.0) to the correct `ReportType` enum constant.

| Method | Assertion |
|---|---|
| `mapsKnownFeedbackCodes` (`@ParameterizedTest`, 5 rows) | `259→COA`, `260→COD`, `258→EXPIRATION`, `275→PAN`, `276→NAN` |
| `mapsNoneToUnknown` | `0` (`MQFB_NONE`) → `UNKNOWN` |
| `mapsSystemReasonCodeToException` | `2051` (`MQRC_PUT_INHIBITED`) and `2053` (`MQRC_Q_FULL`) → `EXCEPTION` (system range without a known `MQFB_*` = exception report carrying an `MQRC_*`) |
| `mapsApplicationRangeToUnknown` | `65536` (`MQFB_APPL_FIRST`, outside system range) → `UNKNOWN` |
| `coaCodAreDistinct` | `259→COA`, `260→COD`, and `271` (`MQFB_XMIT_Q_MSG_ERROR`) → `EXCEPTION` (not COA — guards against off-by-one collisions) |

**Note on test count:** the `@CsvSource` parameterized test (`mapsKnownFeedbackCodes`)
generates 5 individual cases at runtime (259, 260, 258, 275, 276), accounting for 5 of the
15 unit test cases reported by surefire.

---

### UT-02 — In-memory correlation store: register, find, atomic flag updates

**Tier:** Compact

**Test file:**
`ibmmq-jms-guide/src/test/java/com/example/ibmmq/correlation/InMemoryCorrelationStoreTest.java`

**Classes under test:**
- `com.example.ibmmq.correlation.InMemoryCorrelationStore`
- `com.example.ibmmq.consumer.ReportMessageConsumer` (integration of store + router)

**What it proves:** the in-memory correlation store correctly registers pending messages,
resolves them by `MessageId`, and applies COA/COD flag updates atomically and idempotently.
`ReportMessageConsumer.handleReport()` is exercised with synthetic (Mockito-mocked)
`javax.jms.Message` objects to verify the full correlation path without a broker.

| Method | Assertion |
|---|---|
| `registerAndFind` | `register()` stores a `PendingMessage`; `findByMessageId()` returns it by the registered key; `pendingCount() == 1`. |
| `findUnknown` | `findByMessageId(null)` and `findByMessageId("ID:naoexiste")` (an unregistered id) both return `Optional.empty()`. |
| `markFlags` | `markCoaReceived()` sets `coaReceived=true` and leaves `codReceived=false`; marking COA a second time is idempotent; `markCodReceived()` then makes `isFullyConfirmed() == true`. |
| `handleCoaReport` | A mocked `Message` with `JMSCorrelationID == originalMessageId` and `JMS_IBM_FEEDBACK == 259` produces a `DeliveryEvent(COA, 259, …)`; `coaReceived` is set; `codReceived` remains false. |
| `handleCodReportRemovesWhenFullyConfirmed` | A mocked COD report (feedback 260) arriving after COA produces `DeliveryEvent(COD, 260, …)` and causes the fully-confirmed entry to be removed from the store (`findByMessageId → empty`, `pendingCount == 0`). |
| `handleOrphanReport` | A mocked report whose `CorrelationId` is not in the store (feedback 2053, `MQRC_Q_FULL`) produces `DeliveryEvent(EXCEPTION, 2053, …)` with `originalMessageId` falling back to the `correlationId` itself. |

**Note (issue #19):** these mocks stub only `getJMSCorrelationID()` + `getIntProperty(JMS_IBM_FEEDBACK)`.
The MQMD extraction added in #19 is fully null-safe, so `handleReport()` still does not throw and the new
`DeliveryEvent` MQMD fields are simply `null` here (the derived `reportTypeChar` still resolves from the
classified type). This is the invariant locked by UT-05.

---

### UT-03 — GMT `PutDate`/`PutTime` → UTC `LocalDateTime` (no JVM-default-zone leakage)

**Tier:** Compact

**Test file:**
`ibmmq-jms-guide/src/test/java/com/example/ibmmq/report/MqmdTimestampsTest.java`

**Class under test:** `com.example.ibmmq.report.MqmdTimestamps`

**What it proves (issue #19, AC3):** the MQMD `PutDate` (`YYYYMMDD`) + `PutTime` (`HHMMSSTH`, last two
digits = hundredths of a second), both GMT, parse to the exact UTC wall-clock `LocalDateTime` with NO
dependency on the JVM default zone.

| Method | Assertion |
|---|---|
| `parsesGmtPutDateTimeToUtcWallClock` (`@CsvSource`, 4 rows) | `20260531/13300050 → 2026-05-31T13:30:00.500`; `…/00000000 → …T00:00:00`; `…/23595909 → …T23:59:59.090`; leap-day `20240229/23595999 → …T23:59:59.990` (hundredths correctly mapped to the fraction-of-second). |
| `noJvmDefaultZoneLeakageAtDayBoundary` | A midnight/day-boundary value parsed under `America/Sao_Paulo` (UTC−3) and `Asia/Tokyo` (UTC+9) yields the **same** `LocalDateTime` and the **same** `Instant` (via explicit `ZoneOffset.UTC`), proving no default-zone leakage on a non-UTC CI box. |
| `nullBlankAndMalformedAreNullSafe` | `null`/blank/wrong-length/non-numeric inputs all return `null` (never throw) — safe on the already-acked report path; `toInstantUtc(null) == null`. |

---

### UT-04 — `ReportType.toDomainChar()` projection (field #6)

**Tier:** Compact

**Test file:**
`ibmmq-jms-guide/src/test/java/com/example/ibmmq/model/ReportTypeTest.java`

**Class under test:** `com.example.ibmmq.model.ReportType`

**What it proves (issue #19, field #6):** the pure derivation `COA → 'A'`, `COD → 'D'`, all other types
→ the sentinel `DOMAIN_CHAR_OTHER` (`'?'`). MQ exposes no single-char report-type field — this is a
projection of the already-classified type.

| Method | Assertion |
|---|---|
| `coaAndCodMapToDomainChars` | `COA.toDomainChar() == 'A'`, `COD.toDomainChar() == 'D'`. |
| `nonCoaCodMapToSentinel` (`@EnumSource`, 5 types) | `EXPIRATION`/`PAN`/`NAN`/`EXCEPTION`/`UNKNOWN` → `DOMAIN_CHAR_OTHER`. |
| `sentinelIsDistinctFromRealChars` | the sentinel never collides with `'A'`/`'D'`. |

---

### UT-05 — `ReportDescriptor` MQMD extraction: null-safety + recovery

**Tier:** Compact

**Test file:**
`ibmmq-jms-guide/src/test/java/com/example/ibmmq/report/ReportDescriptorTest.java`

**Class under test:** `com.example.ibmmq.report.ReportDescriptor` (+ `HexBytes`)

**What it proves (issue #19, AC2):** the six-field extractor recovers all values from the report's OWN
descriptor when present, and degrades gracefully (every MQMD field `null`, never throwing) when MQMD read
is not enabled — the invariant that keeps the already-acked report path safe and the unit-test mocks green.

| Method | Assertion |
|---|---|
| `bareMockIsNullSafeAndDoesNotThrow` | A mock stubbing only feedback + correlationId → `from()` does not throw; all MQMD fields and hex accessors are `null`; the derived `reportTypeChar` still resolves (`'A'`). |
| `throwingGetterIsSwallowed` | A getter that throws `JMSException` is treated as absent (`null`), never propagated. |
| `recoversAllSixWhenPresent` | With all six getters stubbed, `applIdentityData`, `accountingToken` (+`accountingTokenHex == "010203ff"`), `correlationIdBytes`, `messageIdBytes`, `putTimestampUtc == 2026-05-31T13:30:00.500`, `reportTypeChar == 'A'` are all recovered. |
| `hexStringFallbackForBytesProperty` | A `byte[]` MQMD property arriving as a hex `String` (`"0a0b0c"`) is defensively decoded to bytes. |

---

### UT-06 — `CorrelationStore.recordReport` contract: idempotency + ordering matrix, BOTH adapters

**Tier:** Compact (InMemory arm, surefire) + Rich (Jdbc arm, failsafe + Postgres Testcontainer)

**Test files (a shared contract run against BOTH adapters — NOT a shared code path):**
- Shared base: `ibmmq-jms-guide/src/test/java/com/example/ibmmq/correlation/CorrelationStoreContract.java`
- InMemory arm (surefire `*Test`): `ibmmq-jms-guide/src/test/java/com/example/ibmmq/correlation/InMemoryCorrelationStoreContractTest.java`
- Jdbc arm (failsafe `*IT`, Postgres Testcontainer): `ibmmq-jms-guide/src/test/java/com/example/ibmmq/correlation/JdbcCorrelationStoreContractIT.java`

**Classes under test:**
- `com.example.ibmmq.correlation.CorrelationStore` (the `default recordReport(correlationId, ReportType)` method)
- `com.example.ibmmq.correlation.InMemoryCorrelationStore` and `com.example.ibmmq.correlation.JdbcCorrelationStore` (the two adapters' differing primitives)

**What it proves (issue #26):** `recordReport` collapses the consumer's former per-branch two-step
(`markCoaReceived`/`markCodReceived` then `removeIfFullyConfirmed`) into ONE composed call that returns a
`ReconcileResult { Outcome, PendingMessage }`. Because it is a `default` method on the interface (NOT
overridden in either adapter, ADR-0005-safe), the SAME idempotency + ordering invariants must hold on top
of either adapter's primitives (InMemory `compute`/`computeIfPresent`; Jdbc UPSERT…RETURNING + conditional
DELETE). The shared base pins the matrix below; each arm binds `newStore()` to a fresh store. The Jdbc arm
is an `*IT` (it needs a real Postgres) and runs only under `mvn verify`; the InMemory arm is unit-green
under `mvn test`.

| Method | Assertion |
|---|---|
| `coaThenCod` | COA on a known message → `RECORDED` (pair incomplete, row stays); the following COD → `COMPLETED` (this call removed the fully-confirmed row). |
| `codBeforeCoa` | Out-of-order: COD first → `RECORDED`; the COA then completes the pair → `COMPLETED` (order-independent reconciliation). |
| `duplicateCoaIsIdempotent` | A second COA (at-least-once redelivery) stays `RECORDED`, no double-count; the row remains `coa=true, cod=false`. |
| `duplicateCodAfterCompletion` | The COD that closes the pair is `COMPLETED`; a repeated COD after the row was removed has no prior registration → `ORPHAN` (with `pending == null`). |
| `orphanReportHasNoPrior` | A COA for an unregistered correlation id → `ORPHAN` with `pending == null`; the upsert `mark` still creates a stub (mirrors both adapters). |
| `reportAfterPairCompleted` | After COA+COD complete and the row is removed, a redelivered COA re-creates an orphan stub → `ORPHAN` (the orphan-on-redelivery known limitation). |
| `rejectsNonCoaCodTypes` | `recordReport` with `EXCEPTION`/`EXPIRATION` throws `IllegalArgumentException` (loud-fail on misuse — `recordReport` is only ever called for COA/COD). |

**Consumer-side ORPHAN surfacing** (locked in `LoggingFlowTest.ReportStages`):
`orphanCoaReportLogsOrphanStageAndIncrementsCounter` proves an orphan COA in `ReportMessageConsumer`
emits a `[stage=ORPHAN]` WARN, increments the in-process orphan-rate counter
(`getOrphanReportCount() == 1`), and still returns a `DeliveryEvent` (behaviour preserved for known AND
orphan reports). The `[stage=RECONCILE]` line on a `COMPLETED` outcome is unchanged
(`codReportLogsDeliveryAndReconcileStages`).

---

## Load / volumetry scenarios (k3d harness profile, #21)

These are **not** JUnit tests — they run the live **k3d harness** via the dedicated load profile
(`make load` + `make load-verify`, ADR-0007), **excluded** from the default `mvn verify` / `make verify`
gate. The "test file" is the make profile and its in-cluster scripts.

### LOAD-01 — Sustained throughput + competing-consumers correlation at ~167 msg/s

**Tier:** Rich (harness/broker-dependent)

**"Test file":** `Makefile` targets `load` / `load-verify` → `deploy/k3s/load-run.sh` +
`deploy/k3s/load-verify.sh`. Latency capture: the additive `delivery_report.sent_at` column written by
`com.example.ibmmq.consumer.ReportMessageConsumer`; bounded producer via `harness.publish-max-count`
(`com.example.ibmmq.harness.PublisherHarnessRunner`).

**Pre-conditions:** harness up (`make up`); consumers at ≥2 replicas (business-consumer 3, report-consumer
2); shared JDBC correlation store + append-only `delivery_report` audit on Postgres.

**What it proves (issue #21 ACs):** under a bounded-yet-sustained run of a **known total N** at ~167 msg/s
across real competing-consumer pods —

| Check | Assertion (`load-verify` exits non-zero on breach) |
|---|---|
| AC1 zero loss | `distinct correlation_id` with feedback `259 == N` **and** `260 == N` (N = `replicas × count`; balance alone is NOT accepted). |
| AC2 latency | measured p99 ≤ a **pre-declared** generous ceiling (COA ≤ 5000 ms, COD ≤ 15000 ms), NOT run-derived; p50/p95/p99 recorded as the baseline. |
| AC3 exactly-once across replicas | zero duplicate `(correlation_id, feedback)` rows + `IPPROCS>1` on `DEV.QUEUE.1` and `DEV.QUEUE.2` (read live, before teardown). |
| no mis-auth | DLQ `CURDEPTH == 0`. |

**Diagnostics (not gating):** `pending_message` residual (flagless-orphan race); NULL `sent_at` rows
(COA-before-register, excluded from the latency percentiles).

**Why designed this way:** see **ADR-0007** (k3d real pods over a Testcontainers toy; in-cluster make
profile over a Maven `-Pload`; `delivery_report` over `pending_message` for retained latency; pre-declared
ceiling over run-derived; known-N denominator over balance). Run how-to: `docs/runbook.md`. Baseline
numbers + caveats: `research-output/phase-h-load-baseline-k3d.md`.
