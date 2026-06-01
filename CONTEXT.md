# IBM MQ + JMS 2.0 COA/COD — Domain Context

Glossary of the domain language for this project: integrating Java / Micronaut with IBM MQ over JMS 2.0 (`javax.jms`), centered on COA/COD delivery reports. Terms only — no implementation details (those live in the guide, `docs/adr/`, and the code).

## Language

### Messaging core

**Queue Manager (QMgr)**:
The IBM MQ server that hosts queues, channels, and security; the authority that generates delivery reports.
_Avoid_: broker, MQ server.

**Business message**:
The original application message whose delivery is being tracked. Its arrival and consumption are what COA/COD report on.
_Avoid_: payload message, user message.

**Report message**:
A Queue-Manager-generated control message confirming an event (arrival, delivery, exception, expiration) about a business message.
_Avoid_: ack, receipt, response.

**MQMD (Message Descriptor)**:
The low-level header on every MQ message carrying MessageId, CorrelationId, Report, Feedback, and Persistence.

**JMSContext**:
The JMS 2.0 unified handle (connection + session) used to produce and consume; not thread-safe — one per thread.

**MQSC**:
IBM MQ's administration command language (`DEFINE`, `ALTER`, `SET CHLAUTH`).

### Delivery reports (COA / COD)

**COA (Confirmation on Arrival)**:
The report the QMgr emits when a business message ARRIVES on its destination queue. Under syncpoint it flows only after the producer commits.

**COD (Confirmation on Delivery)**:
The report emitted when a business message is destructively consumed (GET) and the consumer commits. A backed-out consume yields no COD.
_Avoid_: read receipt.

**Feedback code**:
The integer in a report's MQMD Feedback field that classifies it — `MQFB_COA=259`, `MQFB_COD=260`, `MQFB_EXPIRATION=258`, `MQFB_PAN=275`, `MQFB_NAN=276`; exception reports carry an `MQRC_*` instead.

**Exception / Expiration / PAN / NAN report**:
Other report kinds. Exception and Expiration are QMgr-generated; PAN (positive) and NAN (negative) action notifications are the application's responsibility, not the broker's.

**ReplyToQ (report queue)**:
The dedicated queue (the message's `JMSReplyTo`) where COA/COD reports are delivered.
_Avoid_: response queue, reply queue.

### Correlation

**Id propagation**:
By default (`MQRO_COPY_MSG_ID_TO_CORREL_ID`) the original message's MessageId becomes the report's CorrelationId — the key that ties a report back to its business message.

**Correlation store**:
The keyed record of in-flight business messages awaiting their COA/COD, used to reconcile delivery. Must be shared/persistent across replicas, not per-instance memory.
_Avoid_: tracker, cache.

**Log trace context (MDC)**:
The pair of ids (MessageId + CorrelationId) bound into the logging framework's Mapped Diagnostic Context so every log line of one business message's lifecycle (PRODUCE → CONSUME → COA/COD → reconcile) carries them — making a single message greppable end-to-end. This is a **logging/observability** concern, distinct from the Correlation store: it reconciles nothing and holds no delivery state, it only decorates log output.
_Avoid_: naming it with "Correlation" (e.g. `CorrelationContext`) — that overloads the Correlation store; "tracing" without naming MDC.

**Competing consumers**:
Multiple consumer instances (e.g. Kubernetes replicas) reading the same queue; MQ load-balances messages across them, so producer, consumer, and report-receiver are generally different pods.

### Channels & connectivity

**SVRCONN channel**:
The server-connection channel a CLIENT-mode JMS application connects through; governed by `CHLAUTH` and an `MCAUSER` identity.

**CLIENT mode / BINDINGS mode**:
CLIENT mode reaches a remote QMgr over a TCP socket (the microservices case); BINDINGS mode uses shared memory on the same host.

**Connection pool (pooled-jms)**:
The pool that wraps the MQ ConnectionFactory and reuses physical connections instead of opening one per message.
_Avoid_: generic "JMS pool" without naming pooled-jms.

**MCA (Message Channel Agent)**:
The agent that moves messages across a channel, running under the `MCAUSER` identity.

**CONNAME / CCDT / SHARECNV**:
CONNAME is a `host(port)` connection address (a list gives HA); CCDT is the external client-channel definition table; SHARECNV is the number of conversations multiplexed on one TCP socket.

### Reliability & failure

**Persistence inheritance**:
A report inherits the persistence of its original business message — a persistent business message yields persistent COA/COD by default.

**DLQ (Dead Letter Queue)**:
Where the QMgr routes messages it cannot deliver — including reports whose PUT fails authorization.

**Poison message / Backout**:
A message that repeatedly fails processing; after `BOTHRESH` rollbacks the QMgr moves it to the backout queue (`BOQNAME`).

### Security

**MQCSP**:
MQ Connection Security Parameters — the modern user/password authentication flow (`USER_AUTHENTICATION_MQCSP`).

**CHLAUTH**:
Channel Authentication Records — per-channel identity/authorization rules (`SET CHLAUTH`).

**Report-PUT authority (2035 / +setall)**:
To generate and deliver a report the QMgr does a PUT-with-context to the ReplyToQ, which needs context authority (`+setall`); a principal lacking it fails with `MQRC_NOT_AUTHORIZED (2035)` and the report is silently dead-lettered.

**CipherSpec / CipherSuite**:
The TLS algorithm name on the QMgr side (CipherSpec) paired with its Java / JSSE counterpart (CipherSuite).
