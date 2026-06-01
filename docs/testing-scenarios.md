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

**Issue #19 extension — recovered MQMD fields:**
The same test now also enables MQMD read on its own report-queue consumer
(`queue:///DEV.QUEUE.2?mdReadEnabled=true`) and, for **each** arriving COA and COD, asserts the six
recovered MQMD values via `ReportDescriptor.from(report, type)`:
- **Strict** — `correlationIdBytes` non-empty (== original `MsgId` bytes under default propagation);
  `reportTypeChar == 'A'` for the COA / `'D'` for the COD; `putTimestampUtc` non-null and plausibly
  recent (within a ±10-minute window); `messageIdBytes` non-null (read-enabled).
- **Tolerant** — `applIdentityData` non-null (may be blank, QMgr-set); `accountingToken` non-null and
  exactly 24 bytes (`MQ_ACCOUNTING_TOKEN_LENGTH`, may be the QMgr default token).

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
   `JMS_IBM_MQMD_AccountingToken` (24 bytes), `getJMSCorrelationIDAsBytes()`, `JMS_IBM_MQMD_MsgId`,
   `JMS_IBM_MQMD_PutDate = "20260531"`, `JMS_IBM_MQMD_PutTime = "13300050"`.
2. `handleReport(coa)` then `handleReport(cod)`.
3. Read both rows back via the `reader` repository (immediately consistent — single instance).

**Key assertions:**
- Both rows carry the same `appl_identity_data`, `accounting_token_hex` (48 hex chars), the two byte[]
  hex columns, and `put_timestamp_utc == 2026-05-31T13:30:00.500` (UTC wall-clock — no zone leakage).
- `report_type_char == "A"` on the COA row and `"D"` on the COD row.

**Guide cross-references:**
- Section 2.4 / 2.7 — id propagation and report descriptor.
- `research-output/phase-f-mqmd-field-recovery.md` — the (R)-all verdict + property keys + GMT→UTC rule.

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
