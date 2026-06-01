---
name: jms-mq-delivery-report-analyzer
description: Drives a rigorous LLM review of any IBM MQ + JMS 2.0 application that uses COA/COD delivery reports (Confirmation On Arrival / Confirmation On Delivery). Use when asked to audit, review, or harden COA/COD report handling, feedback-code classification, correlation/reconciliation, report-queue topology, report-PUT authority, persistence, pooling/concurrency, or transactions in a JMS messaging codebase — especially under a distributed (Kubernetes + microservices, high-throughput, competing-consumers) deployment. Reports each finding as WHERE / WHY / IMPACT / SOLUTIONS.
---

# JMS / MQ Delivery-Report Analyzer

A project-agnostic review harness for IBM MQ + JMS 2.0 applications that request and
process **COA** (Confirmation On Arrival) and **COD** (Confirmation On Delivery)
delivery reports. It judges the target code against a **four-cell reviewer matrix ×
nine check dimensions**, and it always reasons under a distributed deployment lens —
never as a single-instance toy.

## Standing assumption (woven into every check)

Assume the target runs in a **distributed environment**: Kubernetes + microservices,
average throughput **~10,000 requests/minute (~167 messages/second)**, with the report
consumer (and often the business consumer) deployed at **N replicas acting as competing
consumers** on shared queues. Every finding must be framed against this topology and
load — correlation, pooling, transactions, Virtual Threads, persistence, and failure
modes all behave differently across replicas and under sustained throughput than they
do on one JVM. The full baseline is in `references/distributed-topology.md`; restate it
when the target's own docs do not.

## How to run the analysis

Run the phases in order. Phase 0 is mandatory before any judgement.

```mermaid
flowchart TD
    P0["Phase 0<br/>Grill-me preamble<br/>(interrogate target + assumptions)"]
    P1["Phase 1<br/>Dispatch 4 reviewer cells<br/>(each owns a disjoint set of the 9 dimensions)"]
    P2["Phase 2<br/>Merge findings<br/>(dedupe, cross-link, rank by IMPACT)"]
    P3["Phase 3<br/>Report<br/>(WHERE / WHY / IMPACT / SOLUTIONS per finding)"]
    P0 e1@--> P1
    P1 e2@--> P2
    P2 e3@--> P3
    e1@{ animate: true }
    e2@{ animate: true }
    e3@{ animate: true }

    classDef phase fill:#cfe0ef,stroke:#4a6fa5,color:#1f2430;
    classDef gate fill:#f4e6c4,stroke:#b08a3e,color:#1f2430;
    class P1,P2,P3 phase;
    class P0 gate;
    linkStyle default stroke:#4a6fa5;
```

### Phase 0 — Grill-me preamble (mandatory)

Before judging anything, interrogate the codebase and the stated assumptions. Do **not**
rate a dimension you have not first grounded in evidence. Ask the questions one at a
time, and when a question can be answered by reading the code, read the code instead of
asking. Resolve each branch before moving on. The full question tree is in
`references/grill-me-preamble.md`; the spine is:

- **Topology:** How many replicas of the report consumer? Competing consumers on one
  shared report queue, or a queue per pod? Is the correlation state in-process,
  external, or absent?
- **Intent:** Which reports are requested (COA, COD, exception, expiration, PAN/NAN)?
  Set how — JMS message properties, the report option int, or the connection factory?
- **Reconciliation:** What is the contract that ties a report back to its original
  message? Which header carries it, and is it asserted?
- **Lifecycle:** Persistent or non-persistent originals? Transacted or auto-ack
  sessions? Local or XA transactions? Where does an un-processable report end up?
- **Authority & transport:** Does the principal that the queue manager uses to PUT the
  report have context authority on the reply-to queue? Is the channel secured?

Capture the answers; they parameterize the reviewer cells.

### Phase 1 — Dispatch the reviewer matrix

Four reviewer personas (cells) partition the nine check dimensions with **no overlap**.
Dispatch one subagent per cell using the single parameterized template in
`templates/reviewer-cell.md`, passing the cell's persona, its assigned dimensions, and
the Phase-0 answers. Each cell returns findings in the WHERE / WHY / IMPACT / SOLUTIONS
contract.

| Cell | Reviewer persona | Owns dimensions |
| --- | --- | --- |
| A | Correlation & Reconciliation reviewer | 1 report-request correctness · 2 feedback classification · 3 id-propagation & correlation durability |
| B | Connectivity, Pooling & Concurrency reviewer | 4 connection pooling · 5 transactions · 6 Virtual Threads pinning |
| C | Reliability & Failure-modes reviewer | 7 report-queue topology / reply-to · 8 persistence inheritance · 9 poison-message backout & idempotency |
| D | Security & Authority reviewer | report-PUT context authority (2035 / +setall) · TLS / cipher & secrets |

The nine numbered dimensions are spelled out in detail (what to look for, the
distributed failure mode, and the validated constants) in
`references/check-dimensions.md`. Cell D's two security dimensions are intentionally
deeper rather than more numerous; see that file.

### Phase 2 — Merge

Collect the four cells' findings. De-duplicate where two cells touch the same line
(e.g. a transaction boundary that is both a concurrency and a reliability concern),
cross-link related findings, and rank by IMPACT (a silently-dropped report or an
orphaned correlation outranks a style nit).

### Phase 3 — Report

Emit each finding with exactly four fields:

- **WHERE** — file, symbol, or configuration key (and the queue/channel object when
  relevant). Use neutral placeholders for any example object you introduce
  (`APP.REPORT.QUEUE`, `APP.REQUEST.QUEUE`, `QUEUE.NAME`).
- **WHY** — the mechanism that makes it wrong or risky, grounded in the validated
  constants in `references/constants.md`.
- **IMPACT** — the consequence **at ~167 msg/s across N replicas** — quantify the blast
  radius (orphaned correlations per minute, reports silently dead-lettered, pinned
  carrier threads, pool exhaustion) rather than asserting "this is bad".
- **SOLUTIONS** — one or more concrete, ordered remediations, the cheapest-correct
  first, each with the distributed trade-off called out.

## Bundled references (progressive disclosure — load on demand)

- `references/constants.md` — validated MQRO_* / MQFB_* / JMS_IBM_Report_* values and
  report semantics. **Self-contained**: the numbers are inlined, not linked out.
- `references/check-dimensions.md` — the nine dimensions in full, each with its
  distributed failure mode.
- `references/good-bad-practices.md` — de-identified GOOD vs BAD catalogue per dimension.
- `references/distributed-topology.md` — the k8s / ~10k rpm / competing-consumers
  baseline this skill assumes.
- `references/grill-me-preamble.md` — the full Phase-0 interrogation tree.
- `templates/reviewer-cell.md` — the one parameterized subagent template the four cells
  instantiate.

## Vocabulary note

IBM's own constant and verb names are **not** repo-specific and must be kept verbatim:
`MQRO_COA`, `MQFB_COD`, `JMS_IBM_FEEDBACK`, `MQRO_DEAD_LETTER_Q`, dead-letter queue /
DLQ, `SET CHLAUTH`, `+setall`. Only ever invent **neutral** example object names. This
skill carries no identifiers from any particular project, so it drops cleanly into any
IBM MQ + JMS 2.0 repository.
