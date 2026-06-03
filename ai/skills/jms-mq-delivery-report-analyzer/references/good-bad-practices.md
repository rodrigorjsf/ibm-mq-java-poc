# GOOD vs BAD practices catalogue (de-identified)

Generic patterns for IBM MQ + JMS 2.0 / Jakarta Messaging 3.0 COA/COD code, one block
per dimension. No example carries a project-specific identifier — all object names are
neutral placeholders (`APP.REQUEST.QUEUE`, `APP.REPORT.QUEUE`, `QUEUE.NAME`) and all
symbol names are generic. Pair each GOOD/BAD pair with the matching dimension in
`check-dimensions.md`.

---

## 1. Report-request correctness

- **GOOD** — Request reports explicitly and set the reply-to destination on the same
  message: set the `JMS_IBM_Report_COA` and `JMS_IBM_Report_COD` properties (or
  OR-combine `MQRO_COA | MQRO_COD`) and set `JMSReplyTo` to a dedicated reply-to queue.
  Use a `_WITH_DATA` variant only when the report payload is actually consumed.
- **BAD** — Request COD but never set `JMSReplyTo` (the report has nowhere to go); or
  request `_WITH_FULL_DATA` for high-volume traffic that never reads the payload, paying
  the data-copy cost for nothing.

## 2. Feedback classification

- **GOOD** — Read the feedback code from `JMS_IBM_FEEDBACK`, branch on `MQFB_COA (259)`
  and `MQFB_COD (260)`, handle expiration/PAN/NAN, and provide a **default** branch that
  treats any unrecognised value as an exception report and maps the `MQRC_*` reason code.
- **BAD** — Read only `JMS_IBM_MQMD_FEEDBACK` (null unless MQMD read is enabled); or use a
  `switch` with no default, silently dropping exception reports because there is no
  `MQFB_EXCEPTION` constant to match.

## 3. Id propagation & correlation durability

- **GOOD** — Join reports to originals on `report.JMSCorrelationID == original.JMSMessageID`
  (the default `MQRO_COPY_MSG_ID_TO_CORREL_ID` behaviour), persisting the original-message
  record in a **shared external store** keyed by that id, with idempotent upserts.
- **BAD** — Keep pending correlations in an in-process map. Across competing-consumer
  replicas the receiving pod did not send the original, so every lookup misses; a rolling
  deploy then loses the map entirely.

## 4. Connection pooling & async-listener topology

- **GOOD** — Wrap the MQ connection factory in a pool (`pooled-jms` **2.x for
  `javax.jms` [javax]** / **3.x for `jakarta.messaging` [jakarta]**; package
  `org.messaginghub.pooled.jms.*` unchanged) with a **bounded** maximum connection count
  and configured session limits, reused across messages.
- **GOOD (async listeners)** — Use a **dedicated non-pooled factory** for any async
  `MessageListener` / listener container; keep the pooled factory for producers and
  synchronous consumers. A single pooled factory shared with async listeners starves the
  pool and can deadlock under load (role-based factory split).
- **BAD** — Create a connection (or session) per message, or use an unbounded pool that
  multiplies across replicas until the queue manager's channel limit is exhausted.
- **BAD** — Share a single pooled factory between a `MessageListener` and producers;
  under peak load the listener drains the pool, blocking producer threads.

## 5. Transactions

- **GOOD** — One `JMSContext`/`Session` per thread; choose local transactions unless a
  genuine two-resource atomic commit requires XA; commit the consumer unit of work only
  after the report's downstream effect is durable (remembering a backed-out unit
  suppresses the COD).
- **BAD** — Share a `JMSContext` across worker threads (not thread-safe); or reach for XA
  by default, doubling latency and coupling the fleet to the slowest resource.

## 6. Virtual Threads & the MQ consume path

- **GOOD** — Use **one platform thread per consumer + replica fan-out** for blocking JMS
  work (`receive()`, `commit()`). Parallelism comes from running more replicas (competing
  consumers), not from multiplexing virtual threads inside a pod. The IBM MQ client's
  native frames pin virtual threads regardless of JDK version (JEP 491 / JDK 24 removes
  the `synchronized` pinning boundary, but native-frame pinning survives).
- **BAD** — Assign blocking JMS I/O (`receive()`, `commit()`) to virtual threads, even
  on JDK 24+: native-frame pinning silently caps the pod's effective parallelism. The
  symptom is latency, not an error — easy to misattribute to the broker.

## 7. Report-queue topology

- **GOOD** — A dedicated reply-to queue (`APP.REPORT.QUEUE`) separate from the request
  queue (`APP.REQUEST.QUEUE`), depth-monitored and sized for ~2× business volume when both
  COA and COD are requested.
- **BAD** — Point `JMSReplyTo` at the business request queue, entangling report and
  business consumers as competing readers and under-provisioning depth.

## 8. Persistence inheritance

- **GOOD** — Provision the reply-to queue and its backing store for **persistent** report
  traffic whenever originals are persistent, and set report expiry/retention deliberately.
- **BAD** — Assume reports are non-persistent and size the reply-to queue as ephemeral;
  persistent reports then fill the backing store and stall the broker.

## 9. Poison-message backout & idempotency

- **GOOD** — Set a backout threshold (`BOTHRESH`) and backout queue (`BOQNAME`) on the
  report-consuming queue, and make report processing idempotent (detect redelivery, treat
  a repeat as a no-op).
- **BAD** — No backout threshold (a poison report redelivers forever and ping-pongs
  across replicas) and non-idempotent processing (a redelivered COD double-counts a
  delivery).

## MQMD field recovery from reports

- **GOOD** — Enable MQMD read on the connection factory (`mdReadEnabled=true` URI
  parameter or equivalent) and read the report's own descriptor fields (`PutDate`,
  `PutTime`, `MsgId`, `CorrelId`, `BackoutCount`, `PutApplName`) via the
  `JMS_IBM_MQMD_*` properties. Parse `PutDate`+`PutTime` as UTC (the queue manager
  writes GMT). These fields are recoverable from the report's own descriptor — no
  producer change and no `_WITH_FULL_DATA` variant are required.
- **BAD** — Leave MQMD read disabled and lose per-report timing, backout depth, and
  queue manager metadata that cannot be recovered after the message is consumed.

## Security — report-PUT context authority

- **GOOD** — Grant the app principal the full context authority set on the reply-to queue,
  conceptually `SET AUTHREC PROFILE('APP.REPORT.QUEUE') OBJTYPE(QUEUE)
  PRINCIPAL('<app-principal>') AUTHADD(PUT, PASSID, PASSALL, SETID, SETALL)`, so the
  queue manager's PUT-with-context succeeds. The minimum required is `+passid` — verified
  live: `+put +setall` alone still fails `AMQ8077W … passid`.
- **BAD** — Run with a low-privilege principal lacking `+passid`; the report PUT fails
  `MQRC_NOT_AUTHORIZED (2035)` and the entire report stream is silently dead-lettered
  (even granting `+setall` is not enough without `+passid`).

## Security — TLS / cipher & secrets

- **GOOD** — Enable TLS by setting a cipher on the connection factory, enable MQCSP for
  user/password, and source credentials from a secret manager. On JDK 25 choose a
  non-`TLS_RSA_*` cipher.
- **BAD** — Hard-code credentials in code or images (leaking across every replica/node),
  run the channel unencrypted, rely on `TLS_RSA_*` ciphers on JDK 25, or set the
  cipher-mapping system property removed in MQ 9.4.0.
