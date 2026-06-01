# Phase F fact sheet — COA/COD MQMD field recovery (issue #19)

Validated facts for recovering six report MQMD values from a received COA/COD report. Audience:
anyone implementing or adjusting report-field recovery + its persistence. Goal (zero-re-research):
never re-derive these.

Bytecode source of truth: `com.ibm.mq:com.ibm.mq.allclient:9.4.5.0`
(`~/.m2/repository/com/ibm/mq/com.ibm.mq.allclient/9.4.5.0/com.ibm.mq.allclient-9.4.5.0.jar`,
sha1 `26c8f5cd163847990d270acf2f4f0a7f773c3bbd`), inspected with `javap -p`. Property-key strings were
bytecode-verified in the Phase-A/TASK_2 pass; the accessor signatures and the activation setter were
re-verified this pass (2026-06-01).

## The organizing decision — verdict: ALL SIX from the report's OWN descriptor (R)

A plain `MQRO_COA`/`MQRO_COD` report is a **new** message the queue manager generates; its MQMD
describes the **report**, and its body is **empty**. The six values are recovered from that **own
descriptor (R)** — no `WITH_FULL_DATA`, no embedded-original parsing, no producer change.

| # | Domain value | Verdict | Recovery from the report's own descriptor |
|---|---|---|---|
| 1 | application identity data | **(R)** | `report.getStringProperty(WMQConstants.JMS_IBM_MQMD_APPLIDENTITYDATA)` (read-enabled). The report's own ApplIdentityData (QMgr-set). |
| 2 | accounting token | **(R)** | `report.getObjectProperty(WMQConstants.JMS_IBM_MQMD_ACCOUNTINGTOKEN)` → `byte[]` (**32 bytes**, `MQ_ACCOUNTING_TOKEN_LENGTH=32` / `MQBYTE32` — bytecode-confirmed in `CMQC` and empirically by the broker; NOT 24, which is `MQ_CORREL_ID_LENGTH`/`MQ_MSG_ID_LENGTH`). JMS 2.0 has no `getBytesProperty`; the MQMD byte field comes back as a `byte[]` object property. Handle a hex-`String` fallback defensively. |
| 3 | correlation id (byte[]) | **(R)** | `report.getJMSCorrelationIDAsBytes()` (verified on `com.ibm.jms.JMSMessage`). With the default `MQRO_COPY_MSG_ID_TO_CORREL_ID` this byte[] **IS the original message's MsgId** — this is the cross-report link to the original, and the existing exactly-once reconciliation key. |
| 4 | message id (byte[]) | **(R)** | The report's OWN MsgId via `report.getObjectProperty(WMQConstants.JMS_IBM_MQMD_MSGID)` → `byte[]`. **There is NO `getJMSCorrelationIDAsBytes` sibling for MsgId** (verified: `com.ibm.jms.JMSMessage` exposes `getJMSMessageID()` → `String` only, and `getJMSCorrelationIDAsBytes()` → `byte[]`, but no `getJMSMessageIDAsBytes`). For the ORIGINAL's MsgId use field #3 (cheaper, always-available). |
| 5 | put timestamp → UTC | **(R)** | The report's own `PutDate` (`YYYYMMDD`) + `PutTime` (`HHMMSSTH`, last two = hundredths), both **GMT/UTC**, via `JMS_IBM_MQMD_PUTDATE`/`JMS_IBM_MQMD_PUTTIME` (or the canonical `JMS_IBM_PUTDATE`/`JMS_IBM_PUTTIME`). This is the **report-generation** time (when the COA arrival / COD delivery happened). Parse with an explicit `ZoneOffset.UTC` — never the JVM default zone. |
| 6 | report-type char (`A`/`D`) | **(R)** | Pure derivation from feedback: `MQFB_COA(259) → 'A'`, `MQFB_COD(260) → 'D'`. Add `ReportType.toDomainChar()`. MQ exposes no single-char field. |

### Why (R), not (O) `WITH_FULL_DATA` — design decision (reviewed, deliberate)

Recovering the **original's** ApplIdentityData / AccountingToken / put-time would require:
1. the producer switching to `MQRO_COA_WITH_FULL_DATA` (=1792) / `MQRO_COD_WITH_FULL_DATA` (=14336) so the
   original MQMD + data are embedded in the report body, **plus**
2. the consumer **parsing the binary embedded MQMD** out of the report body, **plus**
3. for the original *identity* to be meaningful, the producer setting identity context
   (`WMQ_MQMD_WRITE_ENABLED` + `WMQ_MQMD_MESSAGE_CONTEXT=SET_IDENTITY/SET_ALL`), which re-triggers the
   **`MQRC_NOT_AUTHORIZED (2035)` `+setid`/`+setall` authority requirement** — the exact class of gotcha
   that dead-lettered reports on the k3s harness in #18 (the IT runs as `admin` and would NOT catch a
   regression; the `app` user would).

That is disproportionate complexity and production-authority risk for this slice. The original message is
**already** linked to every report through field #3 (CorrelId == original MsgId), which is the
load-bearing reconciliation key. So the audit record captures the **report's own** descriptor, and the
**(O) original-identity / original-put-time path is a documented follow-up** (its motivation — the
original AccountingToken as a cross-pod billing/audit key, and original→COD end-to-end latency — is
real, but belongs behind a deliberate authority + body-parsing change, not this slice).

**AC4 consequence:** because the verdict is (R)-all, the producer is **unchanged** (stays plain
`MQRO_COA`/`MQRO_COD`), so the existing happy-path `CoaCodEndToEndIT` flow is untouched — the
with-full-data decision is reconciled by deciding **not** to use it.

## Activation gate — MQMD read is destination-scoped (bytecode-verified)

The `JMS_IBM_MQMD_*` properties are populated **only** when MQMD read is enabled on the **consume
destination**. There is NO setter on the ConnectionFactory. Verified on `com.ibm.mq.jms.MQDestination`:
`setMQMDReadEnabled(boolean)`, `getMQMDReadEnabled()`, `setMQMDWriteEnabled(boolean)`,
`setMQMDMessageContext(int)`, `setMessageBodyStyle(int)`.

The report consumer creates its queue via `context.createQueue("queue:///" + reportQueue)`. Two ways to
enable read; **prefer the URI form** (no provider cast, survives the `JmsPoolConnectionFactory` wrapper):

- **(a) URI property (preferred):** `context.createQueue("queue:///" + reportQueue + "?mdReadEnabled=true")`.
  The destination property key is `mdReadEnabled` (boolean), bytecode-verified as `WMQ_MQMD_READ_ENABLED`'s
  key in `com.ibm.msg.client.wmq.common.CommonConstants`.
- **(b) Cast:** `((com.ibm.mq.jms.MQDestination) queue).setMQMDReadEnabled(true)` before creating the
  consumer. Works because IBM's `createQueue` returns an `MQQueue extends MQDestination`; verify the cast
  is not defeated by a pool wrapper (the IT confirms — if `JMS_IBM_MQMD_*` come back null, the activation
  did not take).

The canonical `JMS_IBM_FEEDBACK` is always populated and needs **no** read-enable (already used today).

## Verified property keys (strings) — from `com.ibm.msg.client.jms.JmsConstants` (inherited by `WMQConstants`)

```
JMS_IBM_MQMD_APPLIDENTITYDATA = "JMS_IBM_MQMD_ApplIdentityData"
JMS_IBM_MQMD_ACCOUNTINGTOKEN  = "JMS_IBM_MQMD_AccountingToken"
JMS_IBM_MQMD_MSGID            = "JMS_IBM_MQMD_MsgId"
JMS_IBM_MQMD_CORRELID         = "JMS_IBM_MQMD_CorrelId"
JMS_IBM_MQMD_PUTDATE          = "JMS_IBM_MQMD_PutDate"
JMS_IBM_MQMD_PUTTIME          = "JMS_IBM_MQMD_PutTime"
JMS_IBM_MQMD_MSGTYPE          = "JMS_IBM_MQMD_MsgType"   // == CMQC.MQMT_REPORT (4) for a report
JMS_IBM_PUTDATE               = "JMS_IBM_PutDate"        // canonical sibling (no read-enable)
JMS_IBM_PUTTIME               = "JMS_IBM_PutTime"
```
Enablement (in `CommonConstants`): `WMQ_MQMD_READ_ENABLED → "mdReadEnabled"`,
`WMQ_MQMD_WRITE_ENABLED → "mdWriteEnabled"`, `WMQ_MQMD_MESSAGE_CONTEXT → "mdMessageContext"`.

## GMT → UTC conversion rule (AC3 — no JVM-default-zone leakage)

`PutDate` = `YYYYMMDD`, `PutTime` = `HHMMSSTH` (8 chars; last two = hundredths of a second), GMT.
Concatenate and parse with an explicit UTC offset — e.g.
`DateTimeFormatter.ofPattern("yyyyMMddHHmmssSS")` then `LocalDateTime.parse(...)` treated as
`ZoneOffset.UTC`, or build an `Instant` via `.toInstant(ZoneOffset.UTC)` and
`LocalDateTime.ofInstant(instant, ZoneOffset.UTC)`. NEVER use a zone-less parse that defers to the JVM
default zone. Unit-test table-driven, including a midnight / day-boundary case, to prove no leakage on a
non-UTC CI box.

## byte[] handling

`accountingToken`, `correlationIdBytes`, `messageIdBytes` are raw `byte[]`. In a record they break
value-equality and log as garbage — expose hex (or Base64) accessors (e.g. `String accountingTokenHex()`)
and keep extraction allocation-light (~167 msg/s). Persist the byte[] columns as hex `VARCHAR` (or `BYTEA`)
in `delivery_report` — hex `VARCHAR` is simplest and queryable.

## Persistence (AC5 — additive, on #40's `delivery_report`)

Six **nullable** columns added by an idempotent ALTER (so existing rows + the `ON CONFLICT
(correlation_id, feedback) DO NOTHING` idempotency are unaffected; the dedup key is unchanged — COA(259)
and COD(260) already produce distinct rows). Extend `DeliveryReportRecord` (six `@MappedProperty` fields),
the `insertIfAbsent` `@Query` (six new columns in the VALUES list), and `ReportMessageConsumer.persistAudit`.

## Sources

- Bytecode: `com.ibm.mq.allclient:9.4.5.0` (sha1 `26c8f5cd163847990d270acf2f4f0a7f773c3bbd`) — `javap -p`
  on `com.ibm.mq.jms.MQDestination` (the MQMD setters), `com.ibm.jms.JMSMessage`
  (`getJMSCorrelationIDAsBytes` present, no `getJMSMessageIDAsBytes`), `com.ibm.msg.client.jms.JmsConstants`
  + `com.ibm.msg.client.wmq.common.CommonConstants` (property keys).
- IBM MQ docs — Reading/writing the MQMD from an IBM MQ classes for JMS application (destination
  `mdReadEnabled`/`mdWriteEnabled`/`mdMessageContext`): https://www.ibm.com/docs/en/ibm-mq/9.4.x (search
  "Reading and writing the message descriptor (MQMD) from an IBM MQ classes for JMS application").
- IBM MQ docs — Report messages and the MQMD `PutDate`/`PutTime` (GMT) format; report options
  `MQRO_*_WITH_FULL_DATA` and id propagation (`MQRO_COPY_MSG_ID_TO_CORREL_ID`).
- Cross-checked against `research-output/phase-a-fact-sheet.md` (MQRO_*/MQFB_* values, id propagation).
