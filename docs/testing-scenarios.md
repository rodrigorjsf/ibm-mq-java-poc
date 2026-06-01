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
This requires context authority (`+setall`), which the low-privilege `app` user of the IBM
MQ developer image does not have. If the test connected as `app`, the report PUT would fail
with `MQRC_NOT_AUTHORIZED` (2035) and the reports would be silently routed to the DLQ,
leaving the report queue empty. The `admin` user holds full authority. In production, the
minimum fix is `SET AUTHREC ... AUTHADD(PUT, SETALL)` for the application principal (see
Section 5.2(d) of the guide).

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
- Section 5.2(d) — the `+SETALL` / admin authority gotcha (why this test uses `admin`).

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
