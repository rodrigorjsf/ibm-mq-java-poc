# =============================================================================
# Makefile — provision & initialize the local Docker environments for this repo.
#
# Two environments:
#   1) k3d distributed harness (issue #18): IBM MQ + Postgres + publisher and
#      competing-consumer microservices on a local k3s-in-Docker cluster.
#      One-shot:  make up        Tear down:  make down        Evidence:  make verify
#   2) Single-broker dev env (docker compose): just IBM MQ QM1 for the guide/IT.
#      make compose-up / compose-logs / compose-down
#
# Prereqs: docker, kubectl, k3d (https://k3d.io), and (for `test`) JDK 25 + Maven.
# Run `make doctor` to check. All targets run from the repo root.
# =============================================================================

# ---- Tunables (override on the command line, e.g. `make up CLUSTER=demo`) ----
IMAGE      ?= ibmmq-jms-harness:1.0.0
CLUSTER    ?= ibmmq
NS         ?= ibmmq-harness
DOCKERFILE ?= ibmmq-jms-guide/Dockerfile
BUILD_CTX  ?= ibmmq-jms-guide
K8S_DIR    ?= deploy/k3s
COMPOSE    ?= ibmmq-jms-guide/docker-compose.yml

# Dependency images imported into the cluster so it never re-pulls them at deploy.
MQ_IMAGE ?= icr.io/ibm-messaging/mq:9.4.5.0-r2
PG_IMAGE ?= postgres:16-alpine
BB_IMAGE ?= busybox:1.36

# Target the cluster explicitly (independent of the current kubectl context).
KUBECTL ?= kubectl --context k3d-$(CLUSTER)

.DEFAULT_GOAL := help
.PHONY: help doctor build pull-deps cluster-up import deploy wait up status logs \
        verify console restart down clean test compose-up compose-logs compose-down \
        load load-verify

help: ## Show this help
	@echo "IBM MQ harness — make targets:"
	@grep -hE '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) \
	  | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-13s\033[0m %s\n", $$1, $$2}'

doctor: ## Check required tooling is installed
	@command -v docker  >/dev/null || { echo "MISSING: docker"; exit 1; }
	@command -v kubectl >/dev/null || { echo "MISSING: kubectl"; exit 1; }
	@command -v k3d     >/dev/null || { echo "MISSING: k3d (https://k3d.io)"; exit 1; }
	@echo "ok: docker $$(docker version -f '{{.Server.Version}}' 2>/dev/null), kubectl present, $$(k3d version | head -1)"

# ----------------------------- k3d distributed harness -----------------------
build: ## Build the multi-role app image (publisher/business/report)
	# --provenance=false keeps a SINGLE-manifest image. BuildKit's default provenance attestations produce a
	# manifest LIST, which `k3d image import` (ctr) cannot import — it fails with
	# "ctr: content digest sha256:... not found" and the app pods then ImagePullBackOff. See
	# deploy/k3s/README.md (troubleshooting) and ADR-0007.
	docker build --provenance=false -t $(IMAGE) -f $(DOCKERFILE) $(BUILD_CTX)

pull-deps: ## Pull MQ/Postgres/busybox images locally (so import stays offline)
	docker pull $(MQ_IMAGE)
	docker pull $(PG_IMAGE)
	docker pull $(BB_IMAGE)

cluster-up: ## Create the k3d cluster (idempotent)
	@k3d cluster list $(CLUSTER) >/dev/null 2>&1 \
	  && echo "cluster '$(CLUSTER)' already exists" \
	  || k3d cluster create $(CLUSTER) --wait --timeout 240s

import: ## Import app + MQ + Postgres + busybox images into the cluster's k8s.io namespace (no registry)
	# `k3d image import` can report success yet NOT land a locally-built image in the node's containerd
	# `k8s.io` namespace on WSL2 (crictl shows nothing → ImagePullBackOff). `docker save | ctr -n k8s.io
	# images import -` writes straight into the namespace the kubelet reads — reliable. Single server node.
	docker save $(IMAGE) $(MQ_IMAGE) $(PG_IMAGE) $(BB_IMAGE) \
	  | docker exec -i k3d-$(CLUSTER)-server-0 ctr -n k8s.io images import -

deploy: ## Apply the k3s manifests (kustomize)
	$(KUBECTL) apply -k $(K8S_DIR)

wait: ## Block until every workload is Ready (MQ creates the QM first)
	$(KUBECTL) -n $(NS) rollout status statefulset/postgres        --timeout=240s
	$(KUBECTL) -n $(NS) rollout status statefulset/ibmmq           --timeout=360s
	$(KUBECTL) -n $(NS) rollout status deployment/report-consumer  --timeout=200s
	$(KUBECTL) -n $(NS) rollout status deployment/business-consumer --timeout=200s
	$(KUBECTL) -n $(NS) rollout status deployment/publisher        --timeout=200s

up: doctor cluster-up build pull-deps import deploy wait status ## Provision + initialize the whole k3d harness
	@echo ""
	@echo "Harness is up. Next: 'make verify' (AC evidence) | 'make logs' | 'make console' | 'make down'"

status: ## Show harness pod status
	$(KUBECTL) -n $(NS) get pods -o wide

logs: ## Follow narrated logs across all harness roles (Ctrl-C to stop)
	$(KUBECTL) -n $(NS) logs -l app.kubernetes.io/component=harness --all-containers --prefix -f --tail=20

verify: ## Show AC1/AC2 evidence: competing consumers, dead-letter depth, reconciliation
	@echo "== AC1: competing consumers (IPPROCS > 1 = multiple pods reading) =="
	-$(KUBECTL) -n $(NS) exec ibmmq-0 -- bash -c 'echo "DIS QSTATUS(DEV.QUEUE.1) IPPROCS" | runmqsc QM1' | grep IPPROCS
	-$(KUBECTL) -n $(NS) exec ibmmq-0 -- bash -c 'echo "DIS QSTATUS(DEV.QUEUE.2) IPPROCS" | runmqsc QM1' | grep IPPROCS
	@echo "== reports must NOT dead-letter (CURDEPTH should be 0) =="
	-$(KUBECTL) -n $(NS) exec ibmmq-0 -- bash -c 'echo "DIS QLOCAL(DEV.DEAD.LETTER.QUEUE) CURDEPTH" | runmqsc QM1' | grep CURDEPTH
	@echo "== AC2: shared correlation store (pending drains to ~0) =="
	-$(KUBECTL) -n $(NS) exec postgres-0 -- psql -U corr -d correlation -c \
	  "SELECT count(*) total, count(*) FILTER (WHERE coa_received) coa, count(*) FILTER (WHERE cod_received) cod, count(*) FILTER (WHERE coa_received AND cod_received) both FROM pending_message;"

console: ## Port-forward the IBM MQ web console (https://localhost:9443/ibmmq/console, admin/passw0rd)
	@echo "Open https://localhost:9443/ibmmq/console  (admin / passw0rd). Ctrl-C to stop."
	$(KUBECTL) -n $(NS) port-forward svc/ibmmq 9443:9443

restart: ## Restart the app pods (pick up a new image / reconnect)
	$(KUBECTL) -n $(NS) delete pods -l app.kubernetes.io/component=harness

down: ## Tear down the k3d cluster
	-k3d cluster delete $(CLUSTER)

clean: down ## Alias for `down` (remove the whole harness cluster)

# ----------------------------- #21 sustained-load profile --------------------
# The DEDICATED load profile (ADR-0007). Excluded from the default `verify`/`test` gate. `load` drives a
# bounded-yet-sustained run at ~167 msg/s (tune below); `load-verify` ASSERTS the result (exit non-zero on
# breach) — run it while consumers are still connected (before `make down`). Feasibility probe: a short run
# e.g. `make load LOAD_COUNT_PER_POD=500` then read the "offered rate" line.
LOAD_PUB_REPLICAS    ?= 4       # publisher pods (competing producers); aggregate rate ~ replicas*1000/interval
LOAD_COUNT_PER_POD   ?= 12500   # messages EACH publisher sends (bounded); N = replicas*count (the denominator)
LOAD_INTERVAL_MS     ?= 24      # per-pod inter-send sleep (ms); 4 pods @ 24ms ~= 167 msg/s; ~5 min for N=50000
LOAD_SETTLE_SECS     ?= 60      # drain wait after publishers finish, before asserting
LOAD_WAIT_SECS       ?= 600     # max wait for all publishers to log PUBLISH-DONE
LOAD_CEIL_COA_P99_MS ?= 5000    # PRE-DECLARED generous COA p99 ceiling (committed before the run; ADR-0007)
LOAD_CEIL_COD_P99_MS ?= 15000   # PRE-DECLARED generous COD p99 ceiling (committed before the run; ADR-0007)
LOAD_ENV = CLUSTER=$(CLUSTER) NS=$(NS) \
  LOAD_PUB_REPLICAS=$(LOAD_PUB_REPLICAS) LOAD_COUNT_PER_POD=$(LOAD_COUNT_PER_POD) \
  LOAD_INTERVAL_MS=$(LOAD_INTERVAL_MS) LOAD_SETTLE_SECS=$(LOAD_SETTLE_SECS) LOAD_WAIT_SECS=$(LOAD_WAIT_SECS) \
  LOAD_CEIL_COA_P99_MS=$(LOAD_CEIL_COA_P99_MS) LOAD_CEIL_COD_P99_MS=$(LOAD_CEIL_COD_P99_MS)

load: ## #21 sustained-load run on the harness (bounded N, then settle). NOT in the default gate (ADR-0007).
	$(LOAD_ENV) bash $(K8S_DIR)/load-run.sh

load-verify: ## #21 ASSERT the load run (completeness, exactly-once, latency ceilings, DLQ); exit non-zero on breach.
	$(LOAD_ENV) bash $(K8S_DIR)/load-verify.sh

# ----------------------------- single-broker dev env (compose) ---------------
compose-up: ## Start the single-broker IBM MQ dev env (docker compose)
	docker compose -f $(COMPOSE) up -d
	@echo "MQ starting. 'make compose-logs' until 'Started queue manager'. Console: https://localhost:9443/ibmmq/console"

compose-logs: ## Follow the single-broker MQ logs
	docker compose -f $(COMPOSE) logs -f mq

compose-down: ## Stop the single-broker env (ARGS=-v also drops the volume)
	docker compose -f $(COMPOSE) down $(ARGS)

# ----------------------------- tests -----------------------------------------
test: ## Run the unit tests (mvn clean test)
	cd $(BUILD_CTX) && mvn clean test
