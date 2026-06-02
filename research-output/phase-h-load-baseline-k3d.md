# Phase H — Sustained-load & competing-consumers baseline on the local k3d harness (#21)

Source of truth for issue #21's load verification: the **pre-declared latency ceilings** (the asserted
gate), the **methodology**, the **caveats**, and the **measured baseline** (filled by the live run).
Realization decided in **ADR-0007**; domain terms in `CONTEXT.md` (*Sustained-load run*, *Latency
baseline (vs SLA)*).

> **Scope.** The local single-node k3d harness on WSL2 is a **correctness + latency-baseline**
> environment, **not** a production-scale SLA sign-off. A real multi-node cluster / SLA is a separate
> effort (ADR-0007 Consequences).

## What is asserted vs what is recorded

| | Purpose | Falsifiable? |
| --- | --- | --- |
| **Pre-declared ceiling** (below) | Catch pathology (unbounded latency growth). A constant committed *before* the run — never derived from it (that would be circular). | Yes — `make load-verify` exits non-zero if the measured p99 exceeds it. |
| **Measured baseline** (below) | Non-regression reference for future runs; the real performance picture. | No — recorded, not asserted, on the first run. Future runs may assert non-regression against it. |

### Pre-declared p99 ceilings (committed before the first run)

| Metric | Ceiling | Rationale |
| --- | --- | --- |
| produce→COA p99 | **≤ 5000 ms** | COA fires on arrival (intra-broker PUT-with-context to the ReplyToQ); a healthy local run is far under 1 s. 5 s is generous — it only trips on a backed-up report queue or unbounded growth. |
| produce→COD p99 | **≤ 15000 ms** | COD fires on the destructive GET by a competing business-consumer, so it includes queue dwell + consumer drain. 15 s is generous headroom over a healthy sub-second-to-low-seconds drain at ~167 msg/s with 3 business-consumers. |

These live in the `Makefile` (`LOAD_CEIL_COA_P99_MS` / `LOAD_CEIL_COD_P99_MS`) and are read by
`deploy/k3s/load-verify.sh`. If a run breaches a ceiling, **investigate** (pool exhaustion, slow report
consumer, queue backlog) — do **not** simply raise the ceiling to make it pass (that re-introduces the
circularity the ceiling exists to avoid).

## Methodology

- **Bounded-yet-sustained run.** `make load` drives the publisher at the target rate until a **known total
  N** is produced, then stops and settles. Sustained is preserved: it runs at rate R for ≈ N/R seconds
  (~5 min at the default N=50000 @ ~167 msg/s), then drains. The known N is the **denominator** for the
  zero-loss assertion — a forever-loop has none, and balance alone (`count(259)==count(260)`) cannot prove
  zero loss (a systematic drop hitting both equally would pass).
- **Known N.** Each publisher pod sends **exactly** `LOAD_COUNT_PER_POD` messages (the
  `harness.publish-max-count` bound), incrementing its counter only on a **successful** send, then idles
  (keeping the JVM/pod alive so the Deployment does not restart a completed pod into another batch). With
  `LOAD_PUB_REPLICAS` pods, `N = replicas × count`. `make load` blocks until all pods log `PUBLISH-DONE`.
- **Latency capture.** The append-only `delivery_report` audit table (issue #40) gets an additive,
  nullable `sent_at` column (issue #21): `ReportMessageConsumer` writes the original send instant
  (`pending.sentAt`) onto each COA/COD row. Latency = `observed_at − sent_at`, percentiles via
  `percentile_cont` over the **retained** table. The `pending_message` ledger is deleted on reconciliation
  and cannot retain latency — hence `delivery_report`, not `pending_message` (ADR-0007).
- **Assertions** (`make load-verify`, exit non-zero on breach; run while consumers are connected):
  - **AC1 zero loss:** `distinct correlation_id` with feedback `259 == N` **and** `260 == N`.
  - **AC2 latency:** measured p99 ≤ the pre-declared ceiling; p50/p95/p99 also printed (baseline).
  - **AC3 exactly-once across replicas:** zero duplicate `(correlation_id, feedback)` rows (the writer's
    `UNIQUE` constraint guarantees it; asserting proves it held) **and** `IPPROCS>1` on `DEV.QUEUE.1` and
    `DEV.QUEUE.2` (real competing consumers — consumers run at ≥2 replicas: business 3, report 2).
  - **no mis-auth:** DLQ `CURDEPTH == 0`.

## Caveats (must accompany any baseline number)

1. **Single-node clock validity.** `sent_at` (publisher pod) and `observed_at` (report-consumer pod) are
   subtracted across pods. This is valid here **only because all pods share one k3d node's kernel clock**
   (no skew). On a real multi-node cluster, NTP/clock-skew handling is required before cross-pod latency is
   meaningful.
2. **COA-before-register → NULL `sent_at`, excluded.** The QMgr can emit the COA before the publisher's
   `register()` commits; then `findByMessageId` misses and `sent_at` is NULL → excluded from the
   percentiles. These are low-tail samples, so **p95/p99 stay robust**; `make load-verify` prints the
   excluded count as a diagnostic.
3. **Baseline ≠ SLA.** WSL2 is noisy; the measured numbers are a local reference, not a production SLA.
4. **Feasibility first.** If the box cannot sustain ~167 msg/s (probe: `make load LOAD_COUNT_PER_POD=500`,
   read the "offered rate" line), document the **achievable** rate as the baseline rather than asserting an
   undeliverable rate.

## Measured baseline (filled by the live run)

> _Pending the first live `make load` + `make load-verify` run. To be filled with: run date, harness/host
> specs, configured vs achieved offered rate, N, COA/COD p50/p95/p99, completeness, exactly-once result,
> DLQ depth, and the excluded-NULL-`sent_at` count. The captured `load-verify` output also lands in
> `docs/acceptance/prd1-acceptance.md` (#22)._

| Field | Value |
| --- | --- |
| Run date (UTC) | _TBD_ |
| Host / harness | WSL2 (k3d single node); MQ 9.4.5.0-r2; Postgres 16; business-consumer ×3, report-consumer ×2 |
| Configured rate / N | _TBD_ (`LOAD_PUB_REPLICAS`×`LOAD_COUNT_PER_POD` @ `LOAD_INTERVAL_MS`) |
| Achieved offered rate | _TBD_ msg/s |
| COA completeness | _TBD_ / N |
| COD completeness | _TBD_ / N |
| Exactly-once (dup rows) | _TBD_ |
| COA p50/p95/p99 (ms) | _TBD_ |
| COD p50/p95/p99 (ms) | _TBD_ |
| DLQ CURDEPTH | _TBD_ |
| NULL `sent_at` excluded | _TBD_ |
| Ceilings cleared? | _TBD_ |
