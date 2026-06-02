#!/usr/bin/env bash
# =============================================================================
# #21 sustained-load run on the k3d harness (ADR-0007).
#
# Bounded-yet-sustained: drive the publisher at the target rate until a KNOWN total N is produced, then
# stop and let in-flight reports drain — so `load-verify.sh` reads completeness/latency AFTER the system
# settles, not mid-flight. The known N (= replicas x count-per-pod) is the denominator for the zero-loss
# assertion (a forever-loop has no denominator; balance alone cannot prove zero loss). See ADR-0007.
#
# This is the DEDICATED load profile: excluded from the default `make verify` / `mvn verify` gate. It
# mutates cluster state (truncates the audit/ledger tables and clears the queues for a clean slate), so it
# is intentionally NOT a Maven profile — a k3d load run is not self-contained (issue #21 grill).
#
# Env (all optional; defaults match the Makefile):
#   CLUSTER NS LOAD_PUB_REPLICAS LOAD_COUNT_PER_POD LOAD_INTERVAL_MS LOAD_SETTLE_SECS LOAD_WAIT_SECS
#
# Feasibility probe: run with a small count, e.g.  LOAD_COUNT_PER_POD=500 make load  — and read the
# "offered rate" line. If the box cannot sustain ~167 msg/s, lower the target and document the achievable
# rate as the baseline (ADR-0007), rather than asserting a rate the environment cannot deliver.
# =============================================================================
set -euo pipefail

CLUSTER="${CLUSTER:-ibmmq}"
NS="${NS:-ibmmq-harness}"
KUBECTL=(kubectl --context "k3d-${CLUSTER}" -n "${NS}")

PUB_REPLICAS="${LOAD_PUB_REPLICAS:-4}"
COUNT_PER_POD="${LOAD_COUNT_PER_POD:-12500}"
INTERVAL_MS="${LOAD_INTERVAL_MS:-24}"
SETTLE_SECS="${LOAD_SETTLE_SECS:-60}"
WAIT_SECS="${LOAD_WAIT_SECS:-600}"
N=$(( PUB_REPLICAS * COUNT_PER_POD ))

echo "== #21 load run: ${PUB_REPLICAS} publisher pods x ${COUNT_PER_POD} msgs @ ${INTERVAL_MS}ms/pod (N=${N}) =="

echo "-- quiesce publishers --"
"${KUBECTL[@]}" scale deployment/publisher --replicas=0
sleep 8

echo "-- clean slate: clear queues, then truncate audit/ledger (so delivery_report holds ONLY this run) --"
"${KUBECTL[@]}" exec ibmmq-0 -- bash -c \
  'printf "CLEAR QLOCAL(DEV.QUEUE.1)\nCLEAR QLOCAL(DEV.QUEUE.2)\nCLEAR QLOCAL(DEV.DEAD.LETTER.QUEUE)\n" | runmqsc QM1' \
  >/dev/null || true
sleep 3
"${KUBECTL[@]}" exec postgres-0 -- psql -U corr -d correlation -c "TRUNCATE pending_message, delivery_report;" >/dev/null

echo "-- launch ${PUB_REPLICAS} bounded publishers (each sends EXACTLY ${COUNT_PER_POD}, then idles) --"
"${KUBECTL[@]}" set env deployment/publisher \
  HARNESS_PUBLISH_MAX_COUNT="${COUNT_PER_POD}" \
  HARNESS_PUBLISH_INTERVAL_MILLIS="${INTERVAL_MS}"
"${KUBECTL[@]}" scale deployment/publisher --replicas="${PUB_REPLICAS}"
"${KUBECTL[@]}" rollout status deployment/publisher --timeout=120s

echo "-- waiting for all ${PUB_REPLICAS} publishers to finish (PUBLISH-DONE), max ${WAIT_SECS}s --"
start=$(date +%s)
end=$(( start + WAIT_SECS ))
while :; do
  done=$("${KUBECTL[@]}" logs -l app.kubernetes.io/name=publisher --tail=-1 2>/dev/null \
           | grep -c "PUBLISH-DONE" || true)
  echo "   publishers done: ${done}/${PUB_REPLICAS}  ($(( $(date +%s) - start ))s elapsed)"
  [ "${done}" -ge "${PUB_REPLICAS}" ] && break
  if [ "$(date +%s)" -ge "${end}" ]; then
    echo "TIMEOUT: only ${done}/${PUB_REPLICAS} publishers finished in ${WAIT_SECS}s" >&2
    exit 1
  fi
  sleep 10
done
produce_secs=$(( $(date +%s) - start ))
[ "${produce_secs}" -lt 1 ] && produce_secs=1
echo "   all ${PUB_REPLICAS} publishers done in ~${produce_secs}s (offered rate ~ $(( N / produce_secs )) msg/s)"

echo "-- settle ${SETTLE_SECS}s (let COA/COD reports drain into delivery_report) --"
sleep "${SETTLE_SECS}"

echo "load run complete (N=${N}). Consumers are still connected — now run: make load-verify"
