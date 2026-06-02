#!/usr/bin/env bash
# =============================================================================
# #21 load-verify — the ASSERTING gate for the sustained-load run (ADR-0007).
#
# Unlike `make verify` (which only DISPLAYS evidence), this exits NON-ZERO on any breach. Run it AFTER
# `make load`, while the consumer pods are still connected (so IPPROCS reflects live competing consumers,
# read before `make down`).
#
# Falsifiable assertions (issue #21 ACs):
#   AC1 zero loss        : distinct COA == N  AND  distinct COD == N   (N = known publisher total; balance
#                          alone — count(259)==count(260) — is NOT accepted, a systematic drop would pass).
#   AC2 latency          : measured p99 <= a PRE-DECLARED generous ceiling (a constant committed before the
#                          run, NOT derived from this run → not circular). p50/p95/p99 are also printed and
#                          recorded as the non-regression baseline.
#   AC3 exactly-once     : zero duplicate (correlation_id, feedback) rows  AND  IPPROCS>1 on both queues.
#   no mis-auth          : DLQ CURDEPTH == 0.
# Diagnostics (NOT gating): pending_message residual (flagless-orphan race), NULL sent_at rows
#   (COA-before-register, excluded from the latency percentiles).
# =============================================================================
set -uo pipefail   # deliberately NOT -e: accumulate ALL failures, report every one, then exit.

CLUSTER="${CLUSTER:-ibmmq}"
NS="${NS:-ibmmq-harness}"
KUBECTL=(kubectl --context "k3d-${CLUSTER}" -n "${NS}")

PUB_REPLICAS="${LOAD_PUB_REPLICAS:-4}"
COUNT_PER_POD="${LOAD_COUNT_PER_POD:-12500}"
CEIL_COA_P99="${LOAD_CEIL_COA_P99_MS:-5000}"
CEIL_COD_P99="${LOAD_CEIL_COD_P99_MS:-15000}"
N=$(( PUB_REPLICAS * COUNT_PER_POD ))
fail=0

echo "== #21 load-verify (expected N=${N}; ceilings COA p99<=${CEIL_COA_P99}ms, COD p99<=${CEIL_COD_P99}ms) =="

# --- Postgres metrics in ONE query (space-delimited scalars; -1 = no latency samples) ---
metrics=$("${KUBECTL[@]}" exec postgres-0 -- psql -U corr -d correlation -t -A -F' ' -c "
  SELECT
    (SELECT count(DISTINCT correlation_id) FROM delivery_report WHERE feedback=259),
    (SELECT count(DISTINCT correlation_id) FROM delivery_report WHERE feedback=260),
    (SELECT count(*) FROM (SELECT 1 FROM delivery_report GROUP BY correlation_id,feedback HAVING count(*)>1) d),
    (SELECT count(*) FROM pending_message),
    COALESCE((SELECT round(percentile_cont(0.5)  WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (observed_at-sent_at))*1000)) FROM delivery_report WHERE feedback=259 AND sent_at IS NOT NULL),-1),
    COALESCE((SELECT round(percentile_cont(0.95) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (observed_at-sent_at))*1000)) FROM delivery_report WHERE feedback=259 AND sent_at IS NOT NULL),-1),
    COALESCE((SELECT round(percentile_cont(0.99) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (observed_at-sent_at))*1000)) FROM delivery_report WHERE feedback=259 AND sent_at IS NOT NULL),-1),
    COALESCE((SELECT round(percentile_cont(0.5)  WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (observed_at-sent_at))*1000)) FROM delivery_report WHERE feedback=260 AND sent_at IS NOT NULL),-1),
    COALESCE((SELECT round(percentile_cont(0.95) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (observed_at-sent_at))*1000)) FROM delivery_report WHERE feedback=260 AND sent_at IS NOT NULL),-1),
    COALESCE((SELECT round(percentile_cont(0.99) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (observed_at-sent_at))*1000)) FROM delivery_report WHERE feedback=260 AND sent_at IS NOT NULL),-1),
    (SELECT count(*) FROM delivery_report WHERE feedback IN (259,260) AND sent_at IS NULL);
" 2>/dev/null | tr -s ' ' | sed 's/^ *//;s/ *$//')

read -r coa_n cod_n dups pending coa_p50 coa_p95 coa_p99 cod_p50 cod_p95 cod_p99 null_sentat <<<"${metrics}"

if [ -z "${coa_n:-}" ]; then
  echo "  [FAIL] could not read metrics from Postgres (is the harness up? did 'make load' run?)" >&2
  exit 2
fi

echo "  COA distinct=${coa_n}  COD distinct=${cod_n}  dup_rows=${dups}  pending_residual=${pending}  null_sent_at=${null_sentat}"
echo "  latency ms — COA p50/p95/p99=${coa_p50}/${coa_p95}/${coa_p99}   COD p50/p95/p99=${cod_p50}/${cod_p95}/${cod_p99}"

# --- AC1: zero loss against the KNOWN denominator N ---
[ "${coa_n}" = "${N}" ] && echo "  [PASS] COA completeness ${coa_n}/${N}" || { echo "  [FAIL] COA loss ${coa_n}/${N}"; fail=1; }
[ "${cod_n}" = "${N}" ] && echo "  [PASS] COD completeness ${cod_n}/${N}" || { echo "  [FAIL] COD loss ${cod_n}/${N}"; fail=1; }

# --- AC3: exactly-once recording (no duplicate report rows) ---
[ "${dups}" = "0" ] && echo "  [PASS] exactly-once: 0 duplicate (correlation_id,feedback) rows" || { echo "  [FAIL] ${dups} duplicate report rows"; fail=1; }

# --- AC2: latency p99 <= pre-declared ceiling (-1 = no samples => fail) ---
if [ "${coa_p99}" -ge 0 ] 2>/dev/null && [ "${coa_p99}" -le "${CEIL_COA_P99}" ]; then
  echo "  [PASS] COA p99 ${coa_p99}ms <= ${CEIL_COA_P99}ms"; else echo "  [FAIL] COA p99 ${coa_p99}ms (ceiling ${CEIL_COA_P99}ms; -1=no samples)"; fail=1; fi
if [ "${cod_p99}" -ge 0 ] 2>/dev/null && [ "${cod_p99}" -le "${CEIL_COD_P99}" ]; then
  echo "  [PASS] COD p99 ${cod_p99}ms <= ${CEIL_COD_P99}ms"; else echo "  [FAIL] COD p99 ${cod_p99}ms (ceiling ${CEIL_COD_P99}ms; -1=no samples)"; fail=1; fi

# --- AC3: competing consumers live (IPPROCS>1) + no mis-auth (DLQ==0) ---
mqval() { "${KUBECTL[@]}" exec ibmmq-0 -- bash -c "echo 'DIS $1($2) $3' | runmqsc QM1" 2>/dev/null \
           | grep -oE "$3\([0-9]+\)" | grep -oE '[0-9]+' | head -1; }
q1=$(mqval QSTATUS DEV.QUEUE.1 IPPROCS); q1="${q1:-0}"
q2=$(mqval QSTATUS DEV.QUEUE.2 IPPROCS); q2="${q2:-0}"
dlq=$(mqval QLOCAL DEV.DEAD.LETTER.QUEUE CURDEPTH); dlq="${dlq:-0}"
[ "${q1}" -gt 1 ] && echo "  [PASS] business-queue competing consumers IPPROCS=${q1}" || { echo "  [FAIL] business-queue IPPROCS=${q1} (expected >1)"; fail=1; }
[ "${q2}" -gt 1 ] && echo "  [PASS] report-queue competing consumers IPPROCS=${q2}" || { echo "  [FAIL] report-queue IPPROCS=${q2} (expected >1)"; fail=1; }
[ "${dlq}" = "0" ] && echo "  [PASS] DLQ CURDEPTH=0 (no report dead-lettered)" || { echo "  [FAIL] DLQ CURDEPTH=${dlq} (reports dead-lettered — check +passid authority)"; fail=1; }

# --- diagnostics (NOT gating) ---
[ "${pending}" != "0" ] && echo "  [diag] pending_message residual=${pending} (flagless-orphan race; not gated — ADR-0007)"
[ "${null_sentat}" != "0" ] && echo "  [diag] ${null_sentat} report rows had NULL sent_at (COA-before-register; excluded from latency percentiles)"

echo ""
[ "${fail}" = "0" ] && echo "== load-verify: ALL CHECKS PASSED ==" || echo "== load-verify: FAILURES DETECTED =="
exit "${fail}"
