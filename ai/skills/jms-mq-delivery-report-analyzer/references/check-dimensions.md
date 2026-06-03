# The nine check dimensions

The four reviewer cells partition these nine dimensions with **no overlap**. Each entry
gives: what to look for, the distributed failure mode (the ~167 msg/s × N-replica lens),
and the validated constants that ground a finding (full values in `constants.md`).

---

## Cell A — Correlation & Reconciliation reviewer

### Dimension 1 — Report-request correctness

**Look for:** how COA/COD (and exception/expiration) are requested. The two legitimate
routes are the JMS message properties (`JMS_IBM_Report_COA`, `JMS_IBM_Report_COD`, …) or
the underlying report-option integer (`MQRO_COA = 256`, `MQRO_COD = 2048`, OR-combined).
Confirm the requested options actually match the intent, that `_WITH_DATA` / `_WITH_FULL_DATA`
variants are used only when the report payload is needed, and that `JMSReplyTo` is set —
a report request with no reply-to destination produces a report the queue manager cannot
deliver.

**Distributed failure mode:** every producer pod must request reports identically; a
config drift where some replicas omit COD means a fraction of traffic silently has no
delivery confirmation — invisible until reconciliation gaps appear at volume.

### Dimension 2 — Feedback classification via `JMS_IBM_FEEDBACK`

**Look for:** how received reports are classified. The code must read the feedback code
from **`JMS_IBM_FEEDBACK`** (canonical, always populated), not only
`JMS_IBM_MQMD_FEEDBACK` (populated only when MQMD read is enabled). It must branch on
`MQFB_COA = 259`, `MQFB_COD = 260`, and handle `MQFB_EXPIRATION = 258`, `MQFB_PAN = 275`,
`MQFB_NAN = 276`. Crucially, there is **no `MQFB_EXCEPTION` constant** — an exception
report carries an `MQRC_*` reason code in the feedback field, so the classifier needs a
default branch that treats an unrecognised feedback value as an exception and maps the
`MQRC_*`. A `switch` with no default silently swallows exception reports.

**Distributed failure mode:** misclassification scales linearly — at 167 msg/s a missing
default branch can drop hundreds of exception reports per minute with no error.

### Dimension 3 — Id propagation & correlation-store durability across replicas

**Look for:** the reconciliation join. With the default `MQRO_COPY_MSG_ID_TO_CORREL_ID`
(value 0), the join is `report.JMSCorrelationID == original.JMSMessageID`. Confirm the
code uses that key (or, if `MQRO_PASS_CORREL_ID` was requested, the matching alternative)
and that the original-message record is stored in a **shared, durable** store, not an
in-process map.

**Distributed failure mode:** this is the highest-value check. An in-process correlation
map is **per-pod** — the replica that receives a report almost never sent the original,
so the lookup misses every time and reports cannot be reconciled. A rolling deploy
additionally evaporates the map, orphaning thousands of in-flight correlations at 167
msg/s. The store must be external (keyed by the join id) and idempotent under
redelivery.

---

## Cell B — Connectivity, Pooling & Concurrency reviewer

### Dimension 4 — Connection pooling

**Look for:** use of a JMS connection pool (the `pooled-jms` line — versions 1.x and 2.x
are `javax.jms`; 3.x is `jakarta.jms`) with a **bounded** maximum connection count, and
no per-message creation/teardown of connections or sessions. Confirm idle/eviction and
max-sessions-per-connection are configured.

**Distributed failure mode:** at 167 msg/s × N pods, a pool that is unbounded exhausts
the queue manager's channel limit (each replica multiplies the connection count); a pool
that is too small serialises throughput. Per-message connection churn collapses under
sustained load even though it passes a single-message test.

### Dimension 5 — Transactions (local vs XA, per-thread JMSContext)

**Look for:** the session/transaction model. Local transacted sessions vs XA;
auto-acknowledge vs client/transacted acknowledge. A `JMSContext` (and its `Session`) is
**not thread-safe** — confirm one context per thread, never shared across worker threads.
Tie this to COD semantics: a COD lives inside the consumer's unit of work and is not sent
if that unit is **backed out** (see `constants.md` §5), so transaction boundaries
directly determine whether confirmations fire.

**Distributed failure mode:** XA across the broker and a database doubles latency and can
stall the whole fleet on a slow resource; a shared `JMSContext` across threads corrupts
state non-deterministically and surfaces only under the concurrency that production load
produces. Backing out a consumer transaction suppresses the COD, so reconciliation must
not treat "no COD yet" as "delivered".

### Dimension 6 — Virtual Threads pinning (JEP 491)

**Look for:** Virtual Threads carrying JMS work, combined with `synchronized` blocks that
hold a lock across a blocking JMS call (send/receive/commit). Before JEP 491 (delivered
in JDK 24), a virtual thread that blocks inside a `synchronized` region **pins** its
carrier platform thread; JEP 491 removes that pin for `synchronized`, but native frames
and other pinning sources remain. Confirm either the runtime is JDK 24+ **or** hot JMS
paths use `ReentrantLock` instead of `synchronized`.

**Distributed failure mode:** pinning silently caps effective parallelism — at 167 msg/s
a handful of pinned carriers throttles a pod far below its configured concurrency, and
the symptom (latency, not error) is easy to misattribute to the broker.

---

## Cell C — Reliability & Failure-modes reviewer

### Dimension 7 — Report-queue topology / dedicated reply-to queue

**Look for:** a **dedicated** reply-to queue for reports (`JMSReplyTo`), separate from
the business request queue, sized for report volume. There is no distinct "report queue"
object type in MQ — reports flow to whatever queue `JMSReplyTo` names via the report
options, so the design must point them at a purpose-built queue with its own depth and
monitoring. Use neutral placeholders in examples (`APP.REPORT.QUEUE` vs
`APP.REQUEST.QUEUE`).

**Distributed failure mode:** if both COA and COD are requested, the reply-to queue
carries **~2× the business volume** (~334 report msg/s for ~167 business msg/s). Reusing
the business queue for reports, or under-provisioning the reply-to queue depth, causes
queue-full conditions at scale; a shared queue also entangles report consumers with
business consumers as competing readers.

### Dimension 8 — Persistence inheritance

**Look for:** any assumption that reports are non-persistent. Reports **inherit** the
original's persistence (copied from the message descriptor): a persistent original
produces a **persistent** COA/COD by default (see `constants.md` §6). Confirm the
reply-to queue and its backing store are provisioned for persistent traffic when
originals are persistent, and that retention/expiry is set deliberately.

**Distributed failure mode:** persistent report traffic at ~334 msg/s writes to the log
on every report; a reply-to queue provisioned as if reports were non-persistent fills its
backing store and stalls the broker. The "reports are cheap/non-persistent" myth is the
defect.

### Dimension 9 — Poison-message backout & idempotency

**Look for:** a backout threshold (`BOTHRESH`) and backout/requeue queue (`BOQNAME`) on
the report-consuming queue, plus **idempotent** report processing (a report can be
redelivered after a crash between processing and acknowledge). Confirm the consumer
detects redelivery and that repeated processing of the same report is a no-op.

**Distributed failure mode:** without a backout threshold a poison report is redelivered
forever, and with competing consumers it **ping-pongs across replicas**, multiplying
load fleet-wide. Without idempotency, a redelivered COD double-counts a delivery in
reconciliation — at 167 msg/s the error budget is consumed quickly.

---

## Cell D — Security & Authority reviewer

This cell owns two dimensions; they are deliberately deeper rather than more numerous.

### Dimension — Report-PUT context authority (2035 / `+passid`)

**Look for:** whether the principal the queue manager uses to PUT the report onto the
reply-to queue has **context authority** (`+passid` minimum). The report PUT is a
PUT-with-context on behalf of the inbound channel's principal; the minimum authority it
actually requires is **`+passid`** (pass identity context) — live-verified on k3d where
`+put +setall` *without* `+passid` still failed `AMQ8077W … passid`. A low-privilege
principal lacking it fails with `MQRC_NOT_AUTHORIZED (2035)`, and the report is
**silently dead-lettered** while the reply-to queue stays empty (see `constants.md` §7).
Confirm the full context authority grant exists, conceptually
`SET AUTHREC ... AUTHADD(PUT, PASSID, PASSALL, SETID, SETALL)` on the reply-to queue
for the app principal. Describe this **generically** — do not name any project's dev
channels.

**Distributed failure mode:** because the failure is silent and routes to the DLQ, at
167 msg/s an authority gap can dead-letter the **entire** report stream with no
application-level error — the reconciliation side just sees a flatline of confirmations.

### Dimension — TLS / cipher & secrets

**Look for:** TLS on the client channel (a cipher must be set on the connection factory
to enable TLS at all), MQCSP authentication enabled for user/password, and credentials
sourced from a secret manager rather than hard-coded or baked into images. On JDK 25,
`TLS_RSA_*` cipher suites are disabled — flag any reliance on them. Do not instruct
setting the cipher-mapping system property that was removed in MQ 9.4.0.

**Distributed failure mode:** in k8s, a credential hard-coded in an image leaks across
every replica and every node that pulls it; rotating it requires a redeploy. An
unencrypted channel exposes business and report traffic across the cluster network. TLS
misconfiguration that "works" on one JVM can fail on a JDK-25 pod because of the disabled
`TLS_RSA_*` suites.
