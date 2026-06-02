# Phase H — Sustained-load, capacity & competing-consumers baseline on the local k3d harness (#21)

Source of truth for issue #21's load verification **and a capacity-planning reference for users**: the
test environment, the measured volumetry/latency, the saturation point, the throughput bottleneck, and the
guidance to size a real deployment. Realization decided in **ADR-0007**; domain terms in `CONTEXT.md`
(*Sustained-load run*, *Latency baseline (vs SLA)*); run how-to in `docs/runbook.md` §5.3.

> **Scope.** The local single-node k3d harness on WSL2 is a **correctness + latency-baseline + capacity-
> characterization** environment, **not** a production-scale SLA sign-off. Every number below is a function
> of THIS environment (below) — read them as relative/illustrative parameters, not absolute SLAs. A real
> multi-node cluster / SLA is a separate effort (ADR-0007 Consequences).

## 1. Test environment (measured)

| Component | Value |
| --- | --- |
| Host | WSL2, kernel `6.6.114.1-microsoft-standard-WSL2` |
| **CPU / RAM** | **4 vCPU / 8 GB** (`MemTotal` 8,133,752 kB ≈ 7.76 GiB) — single k3d node, capacity == allocatable |
| Container runtime | Docker `29.4.0` |
| Cluster | k3d `v5.7.5`, Kubernetes `v1.30.6+k3s1` (1 server node, containerd) |
| IBM MQ | `icr.io/ibm-messaging/mq:9.4.5.0-r2` (QM1; `DEV.APP.SVRCONN`, user `app`) |
| Postgres | `postgres:16-alpine` (single instance; reader datasource → same instance, see ADR-0007) |
| App runtime | Java 25 (Amazon Corretto 25), Micronaut 4, `com.ibm.mq.allclient` 9.4.5.0 |
| App pod resources | requests `cpu=100m, mem=256Mi`; limit `mem=512Mi` (per role) |
| Replicas during load | publisher = tuned (see runs); **business-consumer = 3**, **report-consumer = 2** (competing consumers) |
| Co-resident on the node | IBM MQ + Postgres + **N app JVMs** (e.g. 9 at publisher=4) on **4 vCPU** → CPU is the binding resource |
| Queue depth cap | IBM MQ default `MAXDEPTH` = **5000** on `DEV.QUEUE.2` (report queue) — relevant to overload below |

The whole pipeline (MQ broker, Postgres, every publisher/consumer JVM) shares **4 vCPU**. CPU — not disk
(939 GB free) nor the network — is the binding resource for throughput here.

## 2. What is asserted vs what is recorded

| | Purpose | Falsifiable? |
| --- | --- | --- |
| **Pre-declared ceiling** | Catch pathology (unbounded latency growth). A constant committed *before* the run — never derived from it (circular). | Yes — `make load-verify` exits non-zero if measured p99 exceeds it. |
| **Measured baseline** | Non-regression reference; the real performance/capacity picture. | Recorded, not asserted, on the first run. |

### Pre-declared p99 ceilings (committed before the first run)

| Metric | Ceiling | Rationale |
| --- | --- | --- |
| produce→COA p99 | **≤ 5000 ms** | COA fires on arrival (intra-broker PUT-with-context). A healthy steady-state run is far under 1 s; 5 s only trips on a backed-up report queue / unbounded growth. |
| produce→COD p99 | **≤ 15000 ms** | COD fires on the destructive GET by a competing consumer, so it includes queue dwell + consumer drain. 15 s is generous headroom over a healthy sub-second-to-low-seconds drain. |

In `Makefile` (`LOAD_CEIL_COA_P99_MS` / `LOAD_CEIL_COD_P99_MS`), read by `deploy/k3s/load-verify.sh`. If a
run breaches a ceiling, **investigate** (it means saturation/overload) — do **not** raise the ceiling to
force a pass.

## 3. Methodology

- **Bounded-yet-sustained run.** `make load` drives the publisher at the target rate until a **known total
  N = `LOAD_PUB_REPLICAS × LOAD_COUNT_PER_POD`** is produced, then stops and settles. Sustained is
  preserved: run at rate R for ≈ N/R seconds, then drain. Known N is the **denominator** for zero-loss
  (a forever-loop has none; balance `count(259)==count(260)` alone cannot prove zero loss).
- **Known N.** Each publisher pod sends **exactly** `LOAD_COUNT_PER_POD`, counting only **successful**
  sends, then idles (so a completed pod does not restart into another batch).
- **Latency capture.** Append-only `delivery_report` (issue #40) + additive nullable `sent_at` (#21):
  `ReportMessageConsumer` writes `pending.sentAt`; latency = `observed_at − sent_at`, percentiles via
  `percentile_cont` over the retained table. COA-before-register leaves `sent_at` NULL (excluded; low-tail).
- **Assertions** (`make load-verify`, exit non-zero on breach; run while consumers connected): AC1 zero
  loss (`distinct 259 == N` AND `distinct 260 == N`); AC2 latency p99 ≤ ceiling; AC3 exactly-once across
  replicas (0 duplicate `(correlation_id, feedback)` rows + `IPPROCS>1` on both queues); DLQ `CURDEPTH==0`.

## 4. Measured results

### 4.1 Throughput-scaling probes (offered rate vs publisher pods)

| Config | N | Result | Offered rate |
| --- | --- | --- | --- |
| 4 pods @ 24 ms, short burst | 2,000 | drained OK (short — backlog cleared in settle) | **~47 msg/s** |
| 4 pods @ 24 ms, sustained ~221 s | 14,000 | **SATURATED** (§4.2) | ~63 msg/s |
| 10 pods @ 0 ms | 5,000 | **COLLAPSE** — only 6/10 pods finished in 600 s | (timed out) |

**Finding — scaling producers does NOT scale throughput here.** Each `send()` opens a fresh `JMSContext`
(TCP + MQ handshake, CPU-bound), so adding publisher pods adds *concurrent handshakes* that thrash the 4
shared vCPUs; aggregate throughput **falls** past ~4 pods. The producer is connection-bound at ~12–16
msg/s per single-thread pod.

### 4.2 Overload run — 63 msg/s sustained (N = 14,000) — **FAIL (expected; characterizes the limit)**

| Metric | Value | Note |
| --- | --- | --- |
| Offered rate | ~63 msg/s for ~221 s | above the sustainable point |
| COA recorded | 8,534 / 14,000 | ~39 % lost |
| COD recorded | 6,243 / 14,000 | ~55 % lost |
| **DLQ CURDEPTH** | **5,000** | report queue hit `MAXDEPTH=5000` → overflow dead-lettered |
| pending residual | 8,837 | backlog never reconciled within settle |
| Latency p50 / p95 / p99 (COA) | ~77,734 / ~97,897 / ~100,116 ms | ~100 s — queue dwell under backlog |
| Latency p50 / p95 / p99 (COD) | ~73,740 / ~97,815 / ~100,041 ms | ~100 s |
| **Exactly-once (dup rows)** | **0** | ✅ the UNIQUE-constraint invariant **held even under overload** |
| IPPROCS (business / report) | 3 / 2 | competing consumers active |
| NULL `sent_at` | 122 | COA-before-register (excluded from latency) |

**Root cause — end-to-end pipeline saturation, report-drain-bound.** The **consumers are also
connect-per-operation** (`ReportMessageConsumer.receiveOneReport` and the business consume each create a
`JMSContext` per call). On 4 vCPU the 2 report-consumers drain ~24–30 reports/s; at 63 msg/s of business
traffic the report inflow is ~126/s (COA+COD). Inflow ≫ drain → the report queue grows unbounded, hits
`MAXDEPTH=5000`, and the QMgr dead-letters the overflow; latency balloons to the queue-dwell time (~100 s);
many pairs never reconcile within the settle window. The **sustainable** business rate is therefore bounded
by `report_drain / 2` ≈ **~12–15 msg/s** on this box.

### 4.3 Sustainable baseline run — steady state — **PASS** ✅

Run: `make load LOAD_PUB_REPLICAS=1 LOAD_INTERVAL_MS=150 LOAD_COUNT_PER_POD=1500` then `make load-verify`
(all checks passed, exit 0).

| Field | Value |
| --- | --- |
| Configured | 1 publisher @ 150 ms inter-send, N = 1,500 |
| Achieved offered rate | **~5 msg/s** over 255 s — steady state, **no backlog** |
| COA completeness | **1,500 / 1,500** — zero loss ✅ |
| COD completeness | **1,500 / 1,500** — zero loss ✅ |
| Exactly-once (dup rows) | **0** ✅ |
| pending residual | **0** — fully reconciled ✅ |
| COA latency p50 / p95 / p99 | **9 / 13 / 16 ms** ✅ (ceiling 5000) |
| COD latency p50 / p95 / p99 | **15 / 22 / 27 ms** ✅ (ceiling 15000) |
| DLQ CURDEPTH | **0** ✅ |
| IPPROCS business / report | 3 / 2 — competing consumers ✅ |
| NULL `sent_at` excluded | 354 / 3,000 rows (~24 %) — see note |
| Ceilings cleared by | ~310× (COA) / ~555× (COD) margin |

**Note — NULL `sent_at` is HIGHER at low rate (~24 % here vs 0.9 % under overload §4.2).** Counter-intuitive
but correct: COA latency is ~10 ms, so at a low, un-backlogged rate the report-consumer picks up the COA
*before* the publisher's `register()` commits → its `sent_at` is unknown → excluded. Under overload the
report-consumer is backlogged, so by the time it processes a COA, `register()` has long committed. These are
near-zero-latency low-tail samples, so excluding them keeps p95/p99 honest (it cannot inflate them). COD
`sent_at` is essentially always present (COD comes later, after `register()`).

**Contrast that proves the saturation point:** steady-state **pending=0, DLQ=0, p99≈20 ms** at ~5 msg/s vs
**pending=8,837, DLQ=5,000, p99≈100,000 ms** at 63 msg/s (§4.2). Same harness; the only difference is
whether the offered rate stays under the report-drain capacity.

## 5. Capacity-planning guidance (for users)

- **Steady-state ceiling on this box:** **validated clean at ~5 msg/s** (zero loss, p99 16/27 ms — §4.3);
  **saturates by 63 msg/s** (§4.2). The true ceiling lies between — estimated **~12–15 msg/s**,
  **report-drain-bound** (not producer-, disk-, or network-bound); not bisected further. Drive above it and
  the report queue backs up to `MAXDEPTH` → dead-letters + ~100 s latency.
- **The ~167 msg/s (~10k rpm) mandate is ~10× this dev box's as-built capacity.** Closing the gap needs,
  in priority order:
  1. **Eliminate connect-per-operation** — reuse a pooled / long-lived `JMSContext` on the producer *and*
     the consumers instead of one per message. This is the single biggest lever (the handshake is the
     CPU cost). Tracked by the role-based `JmsPoolConnectionFactory` work (issue #25; see
     `research-output/pooled-jms-factory-tuning.md`, ADR-0006).
  2. **More vCPU + scale `report-consumer` replicas** — report drain is the binding constraint; add
     report-consumer pods *and* CPU together (recompute the HikariCP pool math in `31-app-config.yaml`).
  3. **Raise the report-queue `MAXDEPTH`** for bursty traffic so transient spikes queue instead of
     dead-lettering.
  4. **Multi-node cluster** (with NTP for cross-pod latency validity — §6) for real horizontal scale.
- **Tuning levers (Makefile / manifests):** `LOAD_PUB_REPLICAS`, `LOAD_INTERVAL_MS`, `LOAD_COUNT_PER_POD`,
  `LOAD_SETTLE_SECS`, `LOAD_CEIL_COA_P99_MS`, `LOAD_CEIL_COD_P99_MS`; `business-consumer`/`report-consumer`
  replica counts; report-queue `MAXDEPTH`; HikariCP pool sizes.
- **Sizing rule of thumb (this architecture):** sustainable business rate ≈ `(report-consumer pods ×
  per-pod report-drain) / 2`. With connect-per-operation, per-pod drain ≈ 12–15/s on a shared vCPU; with
  pooling it rises substantially (re-measure after #25).

### 5.1 Environment limitations — what this harness CANNOT show

The local single-node k3d box (§1) is for **correctness + latency-baseline + capacity characterization
only**. It deliberately does **not** represent a production deployment, and it cannot exercise:

- **Real throughput.** 4 vCPU shared by MQ + Postgres + every JVM caps the end-to-end pipeline at
  ~12–15 msg/s (report-drain-bound) — **~10× below the ~167 msg/s (~10k rpm) mandate**. That ceiling is a
  hardware + connect-per-operation artifact, not the application's intrinsic limit.
- **Inter-node network.** All pods share one node (loopback) → no real pod↔pod / pod↔broker latency, no
  cross-AZ hops, no TLS-at-scale overhead.
- **Cross-node scheduling, failure & rolling deploys.** One node → no pod eviction/reschedule, no node
  failure, no rolling-deploy reconnect storms, no anti-affinity.
- **Real CQRS replica lag.** The reader datasource points at the same single Postgres (no streaming replica
  deployed, ADR-0007) → the audit read-model's replica-lag behavior is NOT exercised.
- **MQ HA.** Single QMgr — no multi-instance / native-HA failover.
- **Clock skew.** Cross-pod latency is valid ONLY because one kernel clock is shared; a real cluster needs
  NTP before the numbers mean anything (§6).
- **SLA-grade measurement.** WSL2 shares CPU with the Windows host → noisy; every number is a relative
  parameter, never an SLA.

### 5.2 Recommended environment to test the REAL ~167 msg/s (~10k rpm) volumetry

To validate the standing mandate, move off the laptop to a **multi-node Kubernetes cluster** and change
both the infrastructure and one application prerequisite:

| Area | Recommendation |
| --- | --- |
| **Cluster** | Managed multi-node k8s (EKS / GKE / AKS) or self-managed — ≥ 3 worker nodes, several vCPU each; spread roles via node pools / anti-affinity; NTP-synced. |
| **App prerequisite (blocking)** | **Connection pooling first (#25).** Without reusing a pooled / long-lived `JMSContext` on producer AND consumers, throughput stays connection-bound regardless of cluster size — this is the single change that must land before 167 msg/s is reachable. |
| **IBM MQ** | Dedicated node(s) or Amazon MQ; raise report-queue `MAXDEPTH` well above peak in-flight depth; size logs/buffers; consider multi-instance / native HA. Channel `MAXINST` / `SHARECNV` sized for pooled-connections × replicas. |
| **Postgres** | Managed primary + a REAL streaming read replica (the CQRS reader); `max_connections` ≥ (writer pool + reader pool) × all replicas. |
| **Replicas** | Scale `business-consumer` and `report-consumer` so aggregate drain ≥ the offered rate (report drain must clear ~334 reports/s at 167 msg/s); recompute the HikariCP pool math in `deploy/k3s/31-app-config.yaml`. |
| **Observability** | p50/p95/p99 latency dashboards; queue-depth + DLQ-depth alerts; pool-saturation + reconnect metrics. |
| **Run** | `make load` (adapted to the cluster) at **167 msg/s sustained for ≥ 10 min**; re-establish the baseline AND the pre-declared ceilings THERE — the local numbers do not transfer. |

> **Bottom line for users:** this repo proves the COA/COD correctness, exactly-once, and audit-latency
> *behavior*; it does **not** prove the *throughput* target on a laptop. Reproduce the ~167 msg/s volumetry
> on the cluster above (with pooling) before trusting any capacity SLA.

## 6. Caveats (must accompany any baseline number)

1. **Single-node clock validity.** `sent_at` (publisher pod) and `observed_at` (report-consumer pod) are
   subtracted across pods — valid here only because all pods share one node's kernel clock (no skew). A
   real multi-node cluster requires NTP before cross-pod latency is meaningful.
2. **COA-before-register → NULL `sent_at`, excluded** from percentiles (low-tail; p95/p99 stay robust).
3. **Baseline ≠ SLA.** WSL2 is noisy and CPU-overcommitted; numbers are relative parameters, not an SLA.
4. **Numbers are environment-bound.** Every value scales with the §1 environment — re-measure on the target.

## 7. Harness defects found on the first live run (fixed — so users do not re-hit them)

Surfaced only on a real cluster (not by `mvn test`); fixed in commit `df51fd7` (ADR-0007):

1. **BuildKit manifest-list** → `k3d image import` (ctr) fails "content digest not found" → ImagePullBackOff.
   Fix: `make build` uses `--provenance=false` (single-manifest image).
2. **`k3d image import` false success on WSL2** — reports success but the local image never lands in the
   node's containerd `k8s.io` namespace. Fix: `make import` uses `docker save | ctr -n k8s.io images import -`.
3. **Reader datasource → non-existent `postgres-replica`** (replica StatefulSet never deployed) → HikariCP
   init fails → schema never created. Fix: single-instance stopgap (reader → `postgres`); real replica is a
   documented follow-up.
4. **`DeliveryReportSchema` never instantiated in production** (lazy `@Singleton`, injected by nobody) → the
   audit table was never created and every audit INSERT failed. Fix: triggered via a `@Nullable` injection
   into the harness-only `ReportConsumerHarnessRunner` at report-consumer startup.
