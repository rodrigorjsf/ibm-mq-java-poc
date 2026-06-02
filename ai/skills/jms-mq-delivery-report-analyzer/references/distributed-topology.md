# Distributed-topology assumptions

This skill judges every target as if it runs in a real distributed environment. A skill
dropped into another repository will not have that repository's context, so the baseline
is restated here in full. Apply it to **every** finding unless the target's own
documentation explicitly states a different, justified topology.

## Baseline numbers

| Assumption | Value |
| --- | --- |
| Orchestration | Kubernetes + microservices (not a single JVM) |
| Average throughput | **~10,000 requests/minute ≈ ~167 messages/second** sustained |
| Report consumer deployment | **N replicas** (pods) as **competing consumers** on a shared report queue |
| Business consumer deployment | Typically also N replicas as competing consumers |
| Lifecycle event | Rolling deploys, pod evictions, and scale-up/down happen routinely |

## Why the topology changes the answer

```mermaid
flowchart LR
    P["Producer pods<br/>(set COA/COD,<br/>JMSReplyTo)"]
    RQ["APP.REQUEST.QUEUE<br/>(shared)"]
    QM["Queue manager<br/>(generates COA on arrival,<br/>COD on destructive get;<br/>PUTs report with context)"]
    RPTQ["APP.REPORT.QUEUE<br/>(shared reply-to)"]
    BC["Business consumer<br/>replicas 1..N<br/>(competing)"]
    RC["Report consumer<br/>replicas 1..N<br/>(competing)"]

    P e1@--> RQ
    RQ e2@--> BC
    BC e3@--> QM
    P e4@--> QM
    QM e5@--> RPTQ
    RPTQ e6@--> RC
    e1@{ animate: true }
    e2@{ animate: true }
    e5@{ animate: true }
    e6@{ animate: true }

    classDef app fill:#cfe0ef,stroke:#4a6fa5,color:#1f2430;
    classDef broker fill:#f4e6c4,stroke:#b08a3e,color:#1f2430;
    classDef queue fill:#d7e9d2,stroke:#5a8f63,color:#1f2430;
    class P,BC,RC app;
    class QM broker;
    class RQ,RPTQ queue;
    linkStyle default stroke:#4a6fa5;
```

- **Correlation state is not local.** The pod that sent message *M* (and holds the
  in-process record of "M is awaiting COA+COD") is very unlikely to be the pod that
  receives M's reports. Competing consumers spray reports across replicas at random. Any
  correlation/reconciliation store kept **in process** is therefore effectively
  write-only garbage: the receiving pod cannot find the original it never sent. State
  must be **shared** (an external store keyed by the join id) or the design is wrong.

- **Restarts orphan in-flight work.** A rolling deploy replaces every pod. Anything
  in-process — pending-correlation maps, un-acknowledged sessions, buffered reports — is
  lost. At ~167 msg/s, even a few seconds of in-flight state is **thousands** of
  messages; quantify findings in those terms.

- **Throughput stresses bounded resources.** A connection pool sized for one developer
  laptop starves at 167 msg/s × N pods. Per-message connection or session churn that is
  invisible in a unit test becomes the dominant cost in production.

- **Ordering and exactly-once are not free.** Competing consumers give you parallelism
  but not ordering. COD timing depends on *which* replica did the destructive get and
  *when* it committed. Idempotency on the report side is mandatory because a report can
  be redelivered after a consumer crash between processing and acknowledgement.

- **Scaling interacts with backout.** A poison report that one replica backs out is
  immediately re-eligible for every other replica — a poison message can ping-pong
  across the fleet, multiplying load, unless backout threshold + backout queue are
  configured.

## Throughput arithmetic to reuse in IMPACT statements

- ~167 msg/s ⇒ ~10,000/min ⇒ ~600,000/hour.
- If each original requests both COA and COD, that is **~334 report messages/second**
  on the reply-to queue (two reports per original) — the reply-to queue and its
  consumers must be sized for roughly **double** the business volume.
- A 5-second pod restart at this rate strands on the order of ~835 originals' worth of
  in-flight correlations per restarting pod.

Use these figures to make IMPACT concrete rather than hand-wavy.
