# 0007 — Sustained-load & competing-consumers (#21) verified by an in-cluster make-profile assertion on the k3d harness

Status: accepted

## Context

PRD #1 issue #21 requires sustained-throughput and competing-consumers tests at the standing
~10k rpm (~167 msg/s) target, with: correlation completeness under load (zero lost reports), latency
percentiles asserted against documented thresholds, exactly-once confirmation across replicas, excluded
from the default verify gate and runnable via a dedicated profile.

The acceptance criteria contain two clauses that pull toward different homes:

- *"exactly once **across replicas**"* — replica/pod language, satisfied honestly only by real
  competing-consumer pods.
- *"**asserted** via a **dedicated profile**, **excluded from default verify**"* — Maven/JUnit language,
  and issue #20 had just established that exact pattern (`-Pscenarios`, `-Pvt`).

ADR-0003 already decided that the distributed scenarios (competing-consumers correlation, sustained load)
run on the local k3s harness against real pods, *"instead of a single Testcontainers broker"*. A
Testcontainers `-Pload` IT would therefore **contradict ADR-0003** and require superseding it; it would
also make competing consumers threads-in-one-JVM (weaker "across replicas" fidelity).

Two facts from the code shaped the realization:

- `pending_message` (the `JdbcCorrelationStore` reconciliation ledger) is **deleted** on full COA+COD
  confirmation, and it documents a flagless-orphan race that gets likelier at 167/s — so it can neither
  retain latency data nor yield a clean `pending == 0`.
- `delivery_report` (the issue #40 audit table) is **append-only, never deleted**, already carries
  `observed_at` (the report-processing instant) and a **UNIQUE `(correlation_id, feedback)`** constraint
  that already guarantees exactly-once recording.

## Decision

#21 is realized on the **k3d harness with real pods** (ADR-0003 stands), verified by an **in-cluster
`make load` / `make load-verify` profile — NOT a Maven `-Pload` IT**. Rationale: a k3d load run is not
self-contained (it needs a live external cluster), so reusing the `-Pscenarios`/`-Pvt` Maven-profile
idiom would be superficial mimicry that hides a fundamentally different execution contract.
`make load-verify` runs in-cluster (`kubectl exec` → `psql` + `runmqsc`) and **exits non-zero on breach**.

**Latency is captured on the append-only `delivery_report`, not `pending_message`:**

- additive `ALTER TABLE delivery_report ADD COLUMN IF NOT EXISTS sent_at TIMESTAMP` — the same idempotent
  pattern as the issue #19 columns in `DeliveryReportSchema`;
- `ReportMessageConsumer` persists `pending.map(PendingMessage::sentAt)` onto each audit row (already in
  scope via the existing `findByMessageId`);
- latency = `observed_at − sent_at`; the rare COA-before-register case leaves `sent_at` NULL and is
  excluded from the percentiles (a low-tail sample, so p95/p99 stay robust).

**The assertions are made falsifiable:**

- **Zero loss (denominator = a known total N).** The publisher runs a bounded count (a new
  `harness.publish-max-count`; `0` = unbounded, preserving today's behavior), so **N is known a priori**
  (`publisher_replicas × count`, cross-checked against the publishers' final-count logs). Completeness
  asserts `distinct correlation_id with feedback=259 == N` **and** `== 260 == N`. Balance alone
  (`count(259) == count(260)`) is **not** accepted as zero-loss — a systematic drop hitting both equally
  would pass it.
- **Latency (pre-declared ceiling, not run-derived).** `load-verify` asserts the measured p99 against a
  **generous absolute ceiling committed before the run** (a constant — NOT a value computed from the same
  run, which would be circular). The measured p50/p95/p99 are recorded **separately** as the
  non-regression baseline for future runs.
- **Exactly-once across replicas.** Zero duplicate `(correlation_id, feedback)` rows **and** `IPPROCS > 1`
  on `DEV.QUEUE.1` and `DEV.QUEUE.2`, read **while the consumer pods are still connected** (before
  `make down`). Across-replicas fidelity comes from the consumer roles already running at ≥2 replicas
  (business-consumer 3, report-consumer 2); scaling the publisher only adds offered throughput.
- **No report mis-authorization.** DLQ `CURDEPTH == 0`.
- **Orphan residual.** Any non-zero `pending_message` residual is reported as a **diagnostic**, never a
  hard `== 0` assert (the documented flagless-orphan race).

**Bounded-yet-sustained run.** Drive at rate R until N produced (≈ window T), stop, wait for
`delivery_report` to settle, then assert — so completeness/latency are read after in-flight messages drain,
not mid-flight.

**Local single-node k3d is a correctness + latency-baseline environment, explicitly NOT a production-scale
SLA sign-off.** Cross-pod timestamp subtraction is valid because all pods share one node clock (a real
multi-node cluster would require NTP — recorded as a caveat). The target rate is ~167 msg/s; if the WSL2
host cannot sustain it (a **feasibility probe is the first execution step**), the achievable rate is
documented as the baseline rather than asserting an undeliverable rate.

**Execution boundary.** #21 runs autonomously (author + live run + capture evidence + commit), stopping at
the inherently human **#22 acceptance sign-off**.

## Consequences

- Documentation homes (per the documentation-currency rule): thresholds + methodology + caveats →
  `research-output/phase-h-load-baseline-k3d.md`; scenario → `docs/testing-scenarios.md` (LOAD-01);
  how-to → `docs/runbook.md`; the #22 acceptance checklist + the captured load evidence →
  `docs/acceptance/prd1-acceptance.md`.
- The #21 issue body is amended so "dedicated profile" means the k3d `make load` profile (not a Maven
  profile).
- A future move to a real multi-node cluster (or a tight latency SLA) is a separate effort: the
  pre-declared ceiling becomes an SLA, the recorded baseline becomes the regression reference, and clock
  handling moves from single-node to NTP.
- `make verify` keeps its role as the at-a-glance **display** of harness evidence; `make load-verify` is
  the **asserting** gate.
- `harness.publish-max-count` defaults to `0` (unbounded), so the existing harness behavior and the
  `make up` / `make verify` flow are unchanged when the load profile is not in use.
