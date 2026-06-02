# 0009 — No DeliveryTracker module: the COA/COD lifecycle is composed, not encapsulated

Status: accepted (2026-06-02) — closes issue #28; records a decision **not** to build, so future architecture reviews do not re-suggest it.

## Context

An architecture review (epic #24, 2026-05-31) proposed a deep **DeliveryTracker** module (issue #28) that would own the
COA/COD delivery-report lifecycle end-to-end behind a small `track(businessKey, payload) → messageId` + pump-reports
interface, so a caller would not compose `BusinessMessageProducer` + `ReportMessageConsumer` + the Correlation store
itself, nor hold the `MQRO_COPY_MSG_ID_TO_CORREL_ID` id-propagation invariant. The issue was explicitly speculative and
required a go/no-go before any build. Its three motivating premises were checked against HEAD and all three are now
**stale or overtaken** by work that landed after the review (PR #38 + issue #40):

1. **"No orchestrator exists."** Two now do, both from PR #38: `demo/CoaCodDemoRunner` — an
   `ApplicationEventListener<StartupEvent>` that composes `producer.send` → `consumer.receiveOne` → a deadline-bounded
   `reportConsumer.receiveOneReport` pump → validate (an **in-process** lifecycle composition); and the
   `harness/` family (`AbstractHarnessRunner` + `PublisherHarnessRunner` / `BusinessConsumerHarnessRunner` /
   `ReportConsumerHarnessRunner`) — each role a separate `@Requires(harness.role=…)` pod running a continuous
   competing-consumer loop (the **distributed** composition).
2. **"`DeliveryEvent` is ephemeral; no audit trail."** Refuted by issue #40: `ReportMessageConsumer.persistAudit`
   appends every COA/COD to the `delivery_report` table via `DeliveryReportWriteRepository`
   (idempotent `INSERT … ON CONFLICT (correlation_id, feedback) DO NOTHING`), governed by ADR-0005.
3. **"The id-propagation invariant is asserted nowhere; correlation fails silently."** The one genuine surviving gap —
   orphan-report observability on the production path — is being absorbed into issue #26 (the `recordReport` op returns
   an `ORPHAN` outcome, surfaced as a WARN + an orphan-rate metric). It does not need a deep module.

## Decision

**Do not build a DeliveryTracker.** The COA/COD lifecycle stays **composed** from the exposed parts (producer,
consumers, Correlation store, `ReportFeedbackRouter`), as a deliberate property of this teaching repository, with the
two existing orchestrators as the documented "real-world compositions."

Rationale:

- **A third in-process `track() → pump` module would duplicate `CoaCodDemoRunner`** without adding behaviour.
- **It would mis-model the production topology.** The realistic ~10k-rpm shape (the standing mandate + ADR-0003) is
  **roles split across pods** — a publisher Deployment and report-consumer replicas as competing consumers over a
  shared `JdbcCorrelationStore`. A single-process `track()→handleNextReport` poll loop structurally cannot represent
  that; the harness family already does.
- **The locality benefit the issue ascribed to a future module is already realized** in `ReportMessageConsumer`:
  order-independent reconciliation (`reconcileIfComplete` → `CorrelationStore.removeIfFullyConfirmed`) plus best-effort
  idempotent audit already concentrate the lifecycle logic in one place.
- **The proposed name collides with the glossary.** `CONTEXT.md` lists `_Avoid_: tracker, cache` for the Correlation
  store; "DeliveryTracker" would force the avoided word into the domain language against its own rule.

## Consequences

- **Issue #28 is closed** referencing this ADR. The teaching repo keeps exposing the parts; the demo runner and the
  harness remain the two compositions readers learn from.
- **The orphan-observability gap is owned by issue #26**, not by a new module — `recordReport`'s `ORPHAN` outcome →
  WARN + metric.
- **If ever revisited**, the realistic encapsulation is not an in-process tracker but the **distributed harness shape**
  already built (ADR-0003); any future "should we add a lifecycle façade?" review is answered here first.
- **Reversibility:** trivial — nothing is built, so adopting a composition module later costs only the build; this ADR
  exists to stop the suggestion from recurring, not because the decision is hard to unwind.
