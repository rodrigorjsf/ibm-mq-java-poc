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
        verify console restart down clean test compose-up compose-logs compose-down

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
	docker build -t $(IMAGE) -f $(DOCKERFILE) $(BUILD_CTX)

pull-deps: ## Pull MQ/Postgres/busybox images locally (so import stays offline)
	docker pull $(MQ_IMAGE)
	docker pull $(PG_IMAGE)
	docker pull $(BB_IMAGE)

cluster-up: ## Create the k3d cluster (idempotent)
	@k3d cluster list $(CLUSTER) >/dev/null 2>&1 \
	  && echo "cluster '$(CLUSTER)' already exists" \
	  || k3d cluster create $(CLUSTER) --wait --timeout 240s

import: ## Import app + MQ + Postgres + busybox images into the cluster (no registry)
	k3d image import $(IMAGE) $(MQ_IMAGE) $(PG_IMAGE) $(BB_IMAGE) -c $(CLUSTER)

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
