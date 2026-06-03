# Phase 0 — Grill-me preamble (full interrogation tree)

Before judging any dimension, ground every assumption in evidence from the target. Ask
one question at a time; resolve each branch before opening the next. **When a question
can be answered by reading the code or config, read it instead of asking.** Record every
answer — these answers parameterize the four reviewer cells. Do not rate a dimension you
have not first grounded here.

```mermaid
flowchart TD
    T["Topology<br/>(replicas, competing consumers,<br/>state location)"]
    I["Intent<br/>(which reports, how requested)"]
    R["Reconciliation<br/>(join key, store, assertion)"]
    L["Lifecycle<br/>(persistence, transactions,<br/>poison handling)"]
    A["Authority & transport<br/>(2035/+passid, TLS, secrets)"]

    T e1@--> I
    I e2@--> R
    R e3@--> L
    L e4@--> A
    e1@{ animate: true }
    e2@{ animate: true }
    e3@{ animate: true }
    e4@{ animate: true }

    classDef step fill:#cfe0ef,stroke:#4a6fa5,color:#1f2430;
    classDef sec fill:#f4e6c4,stroke:#b08a3e,color:#1f2430;
    class T,I,R,L step;
    class A sec;
    linkStyle default stroke:#4a6fa5;
```

## Branch 1 — Topology (feeds Cells A & B)

1. How many replicas of the report consumer run in production? Of the business consumer?
2. Do the consumers compete on a single shared reply-to queue, or is there a queue per
   pod? (Shared = competing consumers = correlation state cannot be local.)
3. Where does the pending-correlation / reconciliation state live: in-process, in an
   external shared store, or nowhere?
4. What is the expected sustained throughput? If unknown, apply the baseline
   (~167 msg/s) and say so.
5. What happens to in-flight state on a rolling deploy / pod eviction?

## Branch 2 — Intent (feeds Cell A, dimension 1)

6. Which reports are requested: COA, COD, exception, expiration, PAN/NAN?
7. How are they requested — JMS message properties (`JMS_IBM_Report_*`), the report-option
   integer (`MQRO_*`), or factory-level configuration? Are all producer replicas identical?
8. Is `JMSReplyTo` set on every report-requesting message, and to which destination?
9. Are `_WITH_DATA` / `_WITH_FULL_DATA` variants used, and is the report payload actually
   consumed?

## Branch 3 — Reconciliation (feeds Cell A, dimensions 2 & 3)

10. Which header carries the join key back from report to original? Confirm whether the
    code expects `report.JMSCorrelationID == original.JMSMessageID` (default
    `MQRO_COPY_MSG_ID_TO_CORREL_ID`) or an alternative driven by `MQRO_PASS_CORREL_ID`.
11. How is the feedback code read — `JMS_IBM_FEEDBACK` (canonical) or only
    `JMS_IBM_MQMD_FEEDBACK`?
12. Does the classifier have a default branch for exception reports (no `MQFB_EXCEPTION`
    constant exists; exceptions carry an `MQRC_*`)?
13. Is the reconciliation join asserted/tested anywhere, or merely assumed?

## Branch 4 — Lifecycle (feeds Cells B & C)

14. Are originals persistent or non-persistent? (Reports inherit this — see
    `constants.md` §6.)
15. Are sessions transacted or auto-acknowledge? Local transactions or XA?
16. Is there one `JMSContext`/`Session` per thread, or is a context shared across threads?
17. Are Virtual Threads used for JMS work, and on what JDK (pre- or post-JEP-491 / JDK 24)?
    Is `synchronized` held across blocking JMS calls?
18. Is there a backout threshold (`BOTHRESH`) + backout queue (`BOQNAME`) on the
    report-consuming queue? Is report processing idempotent under redelivery?
19. Is the reply-to queue dedicated and sized for report volume (~2× business volume when
    both COA and COD are requested)?

## Branch 5 — Authority & transport (feeds Cell D)

20. Does the principal the queue manager uses to PUT the report have context authority
    (`+passid` minimum, full grant `AUTHADD(PUT, PASSID, PASSALL, SETID, SETALL)`) on the
    reply-to queue? (Missing `+passid` → `2035` → silent dead-lettering; `+setall` alone
    is not sufficient.)
21. Is the client channel secured with TLS (a cipher set on the connection factory)? Is
    MQCSP enabled for user/password auth?
22. Where do credentials come from — a secret manager, or hard-coded / baked into images?
23. On JDK 25, does anything rely on `TLS_RSA_*` ciphers (disabled) or set the
    cipher-mapping system property removed in MQ 9.4.0?

Once the tree is resolved, dispatch the four reviewer cells with the captured answers.
