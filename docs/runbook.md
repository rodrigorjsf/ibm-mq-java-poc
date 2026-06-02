# Operations Runbook — IBM MQ COA/COD Demo

A self-contained guide for a new operator to stand up the IBM MQ broker, run
the COA/COD end-to-end demo, validate it, and navigate the IBM MQ web console.

---

## 1. Prerequisites

| Requirement | Notes |
|---|---|
| **JDK 25** (Amazon Corretto 25) | `JAVA_HOME=/home/rodrigo/.local/jdk25`. Required — `pom.xml` targets `maven.compiler.release=25`. |
| **Maven 3.9+** | Binary at `/home/rodrigo/.local/maven-current/bin/mvn`. |
| **Docker Engine 29+** | Container runtime for the broker. Docker Desktop users: the socket must be at `/var/run/docker.sock`. |
| **Toolchain helper** | `source ~/.local/ibmmq-env.sh` (sets `JAVA_HOME` and adds `mvn` to `PATH`). |

Verify the toolchain:

```bash
source ~/.local/ibmmq-env.sh
java -version        # should show: openjdk 25 ... Amazon Corretto
mvn -version         # should show: Apache Maven 3.9.x
docker info          # must not error
```

---

## 2. Configuration Reference

### 2.1 Default configuration — `application.yml`

Located at `ibmmq-jms-guide/src/main/resources/application.yml`.

Key bindings (`@ConfigurationProperties("ibm-mq")`):

| YAML key | Default value | Description |
|---|---|---|
| `ibm-mq.host` | `localhost` | Broker host |
| `ibm-mq.port` | `1414` | Listener port |
| `ibm-mq.channel` | `DEV.APP.SVRCONN` | SVRCONN channel for the `app` user |
| `ibm-mq.queue-manager` | `QM1` | Queue manager name |
| `ibm-mq.user` | `app` | MQCSP user |
| `ibm-mq.password` | env `IBM_MQ_PASSWORD`, fallback `passw0rd` | MQCSP password |
| `ibm-mq.business-queue` | `DEV.QUEUE.1` | Business (destination) queue |
| `ibm-mq.report-queue` | `DEV.QUEUE.2` | Report queue (`JMSReplyTo`) |
| `ibm-mq.tls-enabled` | `false` | TLS off in dev |

### 2.2 Demo override — `application-demo.yml`

Located at `ibmmq-jms-guide/src/main/resources/application-demo.yml`.  
Active only when the Micronaut environment `demo` is set.

```yaml
demo:
  coa-cod:
    enabled: true        # gates CoaCodDemoRunner — absent in normal startup

ibm-mq:
  channel: DEV.ADMIN.SVRCONN    # admin channel for context authority
  user: admin
  password: ${MQ_ADMIN_PASSWORD:passw0rd}
```

> **Why admin?** — The queue manager generates a COA/COD report via a
> PUT-with-context to the `JMSReplyTo` queue: it *passes* the original message's
> identity context into the report, so it requires **pass-identity-context**
> authority (`+passid`) — **not** merely `+setall`. The low-privilege `app` user
> lacks it: the report PUT fails with `MQRC_NOT_AUTHORIZED (2035)`
> (`AMQ8077W ... passid`) and the report lands on the DLQ silently. The `admin`
> user already holds the full context set. In production, grant the application
> principal the full context authority instead of using `admin`:
> `SET AUTHREC ... AUTHADD(PUT, PASSID, PASSALL, SETID, SETALL)` — verified live
> on k3s, where `+put +setall` alone still failed on `passid` (see section 7.1).

Note: `application-demo.yml` does NOT override `business-queue` or
`report-queue`, so the demo still uses **`DEV.QUEUE.1`** (business) and
**`DEV.QUEUE.2`** (reports) — the dev image defaults.

### 2.3 MQSC objects (`ibmmq-jms-guide/mqsc/`)

These are production-style objects applied once at queue manager creation
(autoconfig via `/etc/mqm`). They are reference setup — the demo uses the dev
image defaults (`DEV.*`), not these `APP.*` objects.

**`mqsc/10-channel-auth.mqsc`** — channel and authentication:

| Object | Type | Description |
|---|---|---|
| `APP.SVRCONN` | SVRCONN channel | Application channel |
| `APP.IDPWOS` | AUTHINFO (IDPWOS) | OS user/password auth, `CHCKCLNT(REQUIRED)` |
| CHLAUTH rules | — | Block `*MQADMIN` on `APP.SVRCONN`; block `SYSTEM.*`; map `app` → `app` |

**`mqsc/20-queues.mqsc`** — queues:

| Queue | Description |
|---|---|
| `APP.BUSINESS.QUEUE` | Business destination; persistent; backout after 5 rollbacks → `APP.BACKOUT.QUEUE` |
| `APP.REPORT.QUEUE` | Report queue (`JMSReplyTo`); persistent |
| `APP.BACKOUT.QUEUE` | Poison-message backout queue |
| `APP.DLQ` | Dead letter queue (configured as `DEADQ` on the queue manager) |

### 2.4 Environment variables

| Variable | Purpose | Default |
|---|---|---|
| `IBM_MQ_PASSWORD` | Password for the `app` user (normal startup) | `passw0rd` |
| `MQ_ADMIN_PASSWORD` | Password for the `admin` user (demo profile) | `passw0rd` |
| `DATASOURCES_DEFAULT_URL` | Writer/primary JDBC URL (delivery-report audit writes + correlation store) | unset (no datasource) |
| `DATASOURCES_DEFAULT_USERNAME` / `DATASOURCES_DEFAULT_PASSWORD` | Writer credentials | unset |
| `DATASOURCES_READER_URL` | Reader/replica JDBC URL (audit read-model queries only) | unset |
| `DATASOURCES_READER_USERNAME` / `DATASOURCES_READER_PASSWORD` | Reader credentials | unset |

> The committed `application.yml` defines **no** `datasources` block on purpose — a bare run (and the
> unit-test `ApplicationContext`) stays inert with no HikariCP pool. The datasources are supplied by the
> environment (the `docker-compose.yml` Postgres pair below, or the k3s harness ConfigMap/Secret).

### 2.5 Read/write-split persistence of delivery reports (issue #40, ADR-0005)

Every COA/COD report the consumer processes is appended to a durable, queryable **`delivery_report`**
audit table (one row per received report, **never deleted** — unlike the transient `pending_message`
reconciliation ledger that is deleted once a message's COA+COD pair is confirmed). The persistence runs
against an **Aurora-like read/write connection split**: a `default` (writer/primary) datasource and a
`reader` (replica) datasource backed by a streaming-replicated primary + hot-standby.

```mermaid
flowchart LR
    classDef actor fill:#cfe0ef,stroke:#4a6fa5,color:#1f2430;
    classDef writer fill:#f4e6c4,stroke:#b08a3e,color:#1f2430;
    classDef reader fill:#d7e9d2,stroke:#5a8f63,color:#1f2430;
    classDef store fill:#e3d7ef,stroke:#7a5a9f,color:#1f2430;

    RC["ReportMessageConsumer<br/>handleReport (COA/COD)"]:::actor

    subgraph WR["datasources.default — WRITER"]
        PRIM["Postgres primary<br/>delivery_report (append-only)<br/>UNIQUE(correlation_id, feedback)"]:::writer
    end
    subgraph RD["datasources.reader — READER"]
        REPL["Postgres replica (hot-standby)<br/>delivery_report (read-only copy)"]:::reader
    end
    %% Subgraph containers explicitly colored (repo Mermaid rule: every shape, incl. meaningful subgraphs).
    style WR fill:#eef2f7,stroke:#4a6fa5,color:#1f2430;
    style RD fill:#eef5ea,stroke:#5a8f63,color:#1f2430;

    Q["audit queries / projections"]:::store

    RC e1@-->|"INSERT ... ON CONFLICT DO NOTHING<br/>(idempotent, best-effort)"| PRIM
    PRIM e2@-->|"physical streaming replication"| REPL
    Q e3@-->|"findByCorrelationId (eventually consistent)"| REPL

    e1@{ animate: true }
    e2@{ animate: true }
    e3@{ animate: true }
```

Key properties (all in `ReportMessageConsumer` + the `com.example.ibmmq.persistence` package):

- **Writer = `default`.** Naming the writer datasource `default` is load-bearing: `JdbcCorrelationStore`
  injects a bare `DataSource` (resolves to `default`) and exactly-once reconciliation stays on the
  writer — never on the lagging replica. The audit `INSERT`s also go to the writer.
- **Idempotent + best-effort.** Reports are at-least-once (the queue manager redelivers; competing
  consumers may double-process). The writer table carries `UNIQUE (correlation_id, feedback)` and the
  insert is `INSERT ... ON CONFLICT DO NOTHING` — a duplicate is a silent no-op (no duplicate row, no
  thrown exception). Because the report is acked under `AUTO_ACKNOWLEDGE` before processing, a persist
  failure is logged at `WARN` (`[stage=AUDIT]`) and never breaks reconciliation.
- **Reader is query-only.** No read whose result gates a write/dedup decision uses the reader — replica
  lag would make a just-written row invisible for tens of ms. Reads from the reader are eventually
  consistent; callers poll-with-timeout, never assert immediately after a write.
- **HikariCP pool sizing** (k3s harness, `deploy/k3s/31-app-config.yaml`): `default` (writer) 4
  connections/pod, `reader` (replica) 2 connections/pod; at report-consumer `replicas: 2` the worst-case
  is 8 writer + 4 reader connections, comfortably under Postgres' default `max_connections=100`. Recompute
  `writer = 4 x R`, `reader = 2 x R` if you scale replicas.

#### Bring up the writer + replica pair (docker-compose)

`ibmmq-jms-guide/docker-compose.yml` runs the broker **plus** a `postgres-primary` (writer, port 5432)
and a `postgres-replica` (hot-standby by streaming replication, port 5433):

```bash
cd ibmmq-jms-guide
docker compose up -d

# Wait for the broker:
docker compose logs -f mq                  # "Started queue manager"
# Wait for the standby to catch up to the primary:
docker compose logs -f postgres-replica    # "started streaming WAL from primary" /
                                            # "database system is ready to accept read-only connections"
```

Point the app at the split (override `application.yml` via env or `-D`):

```bash
-Ddatasources.default.url=jdbc:postgresql://localhost:5432/correlation   # writer
-Ddatasources.reader.url=jdbc:postgresql://localhost:5433/correlation    # reader/replica
# username/password: corr / corrpass
```

Inspect the audit rows on each endpoint (the replica is a read-only copy):

```bash
# Rows on the WRITER (primary):
docker exec ibmmq-pg-primary psql -U corr -d correlation \
  -c 'SELECT report_type, feedback, correlation_id, observed_at FROM delivery_report ORDER BY observed_at;'

# Same rows visible on the REPLICA by physical replication (eventually consistent):
docker exec ibmmq-pg-replica psql -U corr -d correlation \
  -c 'SELECT count(*) FROM delivery_report;'
```

#### Tests (issue #40, AC5)

| Test | Containers | In default `verify`? | How to run |
|---|---|---|---|
| `DeliveryReportPersistenceIT` | 1 × `postgres:16-alpine` (deterministic, no replication) | **Yes** | `mvn -pl ibmmq-jms-guide verify` |
| `DeliveryReportReplicationIT` (`@Tag("replication")`) | 2 × `bitnami/postgresql:16.4.0` (primary + standby) | **No — excluded** | `mvn -pl ibmmq-jms-guide verify -Preplication` |
| `CorrelationStoreNamedDatasourcesSmokeTest` | none (no DB) | Yes (surefire) | `mvn -pl ibmmq-jms-guide test` |

The replication IT is `@Tag`-gated and excluded from `verify` (failsafe `<excludedGroups>replication</excludedGroups>`)
because a two-container streaming-replication test is expensive and lag-sensitive — improper for the CI
gate. It asserts read-from-reader via poll-with-timeout. The deterministic persistence IT (one container,
no replication) proves the persist/idempotency logic and **stays** in `verify`.

#### k3s harness (live validation is HITL, issue #21)

The k3s ConfigMap (`deploy/k3s/31-app-config.yaml`) already wires both datasources
(`DATASOURCES_DEFAULT_*` → primary, `DATASOURCES_READER_*` → replica) and the Secret
(`deploy/k3s/10-secrets.yaml`) carries both passwords. The **primary+replica StatefulSet manifest** and
its live cluster validation are tracked as a **HITL follow-up (issue #21)** — see
`deploy/k3s/README.md` ("Read/write-split datasources"). The application code, compose pair, and the
deterministic + opt-in replication ITs are complete and verified here.

---

## 3. Bring Up the Broker

All commands run from the `ibmmq-jms-guide/` directory.

```bash
cd ibmmq-jms-guide

# Start the broker (detached)
docker compose up -d

# Tail logs until the queue manager is ready
docker compose logs -f mq
```

**Expected output in the logs (ready signal):**

```
Started queue manager
```

Full readiness indicator: you should see a line like:

```
AMQ5041I: The queue manager task 'AUTOCONFIG' has ended.
...
AMQ5975I: IBM MQ queue manager 'QM1' started.
```

Once ready:

- MQ listener: `localhost:1414`
- Web console: `https://localhost:9443/ibmmq/console`
- Users: `app` / `passw0rd`, `admin` / `passw0rd`

**Tear down** (preserves data):

```bash
docker compose down
```

**Tear down + discard all data**:

```bash
docker compose down -v
```

---

## 4. COA/COD Demo Flow

### 4.1 Architecture

```mermaid
flowchart LR
    classDef queue fill:#d7e9d2,stroke:#5a8f63,color:#1f2430
    classDef actor fill:#cfe0ef,stroke:#4a6fa5,color:#1f2430
    classDef qm    fill:#f4e6c4,stroke:#b08a3e,color:#1f2430
    classDef pass  fill:#d2ecd7,stroke:#4a9460,color:#1f2430

    P([BusinessMessage<br/>Producer]):::actor
    BC([BusinessMessage<br/>Consumer]):::actor
    RC([ReportMessage<br/>Consumer]):::actor
    QM([QM1<br/>Queue Manager]):::qm
    BQ[DEV.QUEUE.1<br/>business queue]:::queue
    RQ[DEV.QUEUE.2<br/>report queue]:::queue
    PASS([resultado=PASS<br/>COA+COD+correlId]):::pass

    P -->|1 PRODUCE<br/>COA+COD enabled<br/>JMSReplyTo=DEV.QUEUE.2| BQ
    BQ -->|2 COA report<br/>feedback=259| QM
    QM -->|2 COA delivered| RQ
    BC -->|3 CONSUME<br/>GET destructive| BQ
    BC -->|4 COMMIT<br/>triggers COD| QM
    QM -->|5 COD report<br/>feedback=260| RQ
    RQ -->|6 CLASSIFY<br/>CORRELATE| RC
    RC --> PASS
```

**Message flow summary:**

1. Producer sends to `DEV.QUEUE.1` with COA+COD report options; `JMSReplyTo=DEV.QUEUE.2`.
2. Queue manager delivers COA (feedback=259) to `DEV.QUEUE.2` immediately on arrival.
3. Business consumer performs a destructive GET from `DEV.QUEUE.1` in a transacted session.
4. Business consumer commits — the commit releases the COD trigger.
5. Queue manager delivers COD (feedback=260) to `DEV.QUEUE.2`.
6. Report consumer reads `DEV.QUEUE.2`, classifies feedback, correlates `correlationId → messageId`.

### 4.2 Run the Demo

**Pre-requisite:** broker must be up (`docker compose up -d`, section 3).

```bash
source ~/.local/ibmmq-env.sh
cd ibmmq-jms-guide

# Capture the run to a log file so the validation checks in section 5 are reproducible.
JAVA_HOME=/home/rodrigo/.local/jdk25 /home/rodrigo/.local/maven-current/bin/mvn mn:run \
  -Dmn.jvmArgs="--enable-native-access=ALL-UNNAMED -Dmicronaut.environments=demo" \
  2>&1 | tee /tmp/coa-cod-demo.log
```

> **Important:** `-Dmicronaut.environments=demo` and `--enable-native-access=ALL-UNNAMED`
> must be placed inside `-Dmn.jvmArgs`, not as bare Maven `-D` flags. `mn:run` forks
> a separate JVM for the application; a bare `-D` on the Maven CLI sets the Maven JVM,
> not the application JVM — the flag would be silently ignored.

> **When is it done?** The demo runs once at startup. Once you see the
> `[demo=COA/COD] [resultado=PASS]` summary line, the flow is complete. The
> application does **not** call `System.exit`; if the process does not return to
> the shell on its own, press **Ctrl-C** to stop it (the summary has already been
> written to `/tmp/coa-cod-demo.log`).

Alternative — using an environment variable (equivalent):

```bash
MICRONAUT_ENVIRONMENTS=demo \
JAVA_HOME=/home/rodrigo/.local/jdk25 /home/rodrigo/.local/maven-current/bin/mvn mn:run \
  -Dmn.jvmArgs="--enable-native-access=ALL-UNNAMED"
```

### 4.3 Expected Log Output

The application emits narrated `INFO` log lines tagged with `[stage=...]` and
`[demo=COA/COD]`. The log text is Brazilian Portuguese (source language) — this
is what you will see verbatim.

**Stage sequence (in order):**

| Token | Meaning |
|---|---|
| `[stage=BANNER]` | Demo runner starting, produce/consume announced |
| `[stage=PRODUCE]` | Message sent to `DEV.QUEUE.1`; `messageId` logged |
| `[stage=CONSUME]` | Message received destructively from `DEV.QUEUE.1` |
| `[stage=COMMIT]` | Transacted commit done — COD trigger released |
| `[stage=CLASSIFY]` | Report received; feedback code classified |
| `[stage=CORRELATE]` | `correlationId` matched back to original `messageId` |
| `[stage=COA]` | COA report (feedback=259) registered |
| `[stage=COD]` | COD report (feedback=260) registered |
| `[stage=RECONCILE]` | Both COA+COD confirmed; pending entry removed |
| `[resultado=PASS]` | Final summary — all assertions passed |

**Example verbatim log excerpt (passing run):**

```
INFO  [stage=BANNER] Iniciando a demo COA/COD ponta-a-ponta (produce -> consume -> COA/COD) contra o broker local.
INFO  [stage=PRODUCE] Mensagem de negocio enviada: businessKey=demo-coa-cod, messageId=ID:..., replyTo=DEV.QUEUE.2
INFO  [stage=CONSUME] Mensagem de negocio consumida (GET destrutivo): messageId=ID:..., body={"demo":"coa-cod","pedido":42}
INFO  [stage=COMMIT] Consumo confirmado (commit): COD liberado para a fila de relatorios, messageId=ID:...
INFO  [stage=CLASSIFY] Relatorio classificado: tipo=COA, feedback=259, correlId=ID:...
INFO  [stage=CORRELATE] Correlacionado a mensagem original: originalMsgId=ID:..., conhecido=true
INFO  [stage=COA] Confirmacao de chegada (arrival) registrada: correlId=ID:..., originalMsgId=ID:...
INFO  [stage=CLASSIFY] Relatorio classificado: tipo=COD, feedback=260, correlId=ID:...
INFO  [stage=COD] Confirmacao de entrega (delivery) registrada: correlId=ID:..., originalMsgId=ID:...
INFO  [stage=RECONCILE] Entrega completa (COA+COD): pendencia reconciliada e removida, ...
INFO  [demo=COA/COD] [resultado=PASS] Fluxo validado: COA(feedback=259)=true, COD(feedback=260)=true, correlId==messageId=true ...
```

---

## 5. Validate

A run is a **PASS** when all of the following hold:

| Check | Expected token in log |
|---|---|
| COA report received | `[stage=COA]` line present |
| COD report received | `[stage=COD]` line present |
| Feedback code for COA | `feedback=259` in the CLASSIFY line |
| Feedback code for COD | `feedback=260` in the CLASSIFY line |
| Correlation is correct | `correlId==messageId=true` in the PASS line |
| Final verdict | `[resultado=PASS]` in the log |

A run is a **FAIL** when the log shows `[resultado=FAIL]` instead.

### 5.1 Step-by-step validation checklist

1. Run the demo command (section 4.2) — it tees output to `/tmp/coa-cod-demo.log`.
2. Once the `[resultado=PASS]` summary line appears, the flow is complete; stop
   the process with **Ctrl-C** if it does not return on its own (section 4.2).
3. Confirm the final verdict is PASS:
   ```bash
   grep '\[resultado=PASS\]' /tmp/coa-cod-demo.log
   ```
   A matching line means the run passed; no match means it failed (section 7).
4. Confirm `[stage=COA]` appears **before** `[stage=COD]` (COA precedes COD):
   ```bash
   grep -nE '\[stage=(COA|COD)\]' /tmp/coa-cod-demo.log
   ```
5. Confirm the same `messageId` appears in the `[stage=PRODUCE]` and PASS lines:
   ```bash
   grep -E '\[stage=PRODUCE\]|\[resultado=PASS\]' /tmp/coa-cod-demo.log
   ```
6. If any check fails, see section 7 (Troubleshooting).

### 5.2 Integration test (alternative automated validation)

The Maven integration test `CoaCodEndToEndIT` spins up a real broker via
Testcontainers and asserts the same conditions programmatically:

```bash
cd ibmmq-jms-guide
JAVA_HOME=/home/rodrigo/.local/jdk25 /home/rodrigo/.local/maven-current/bin/mvn verify
```

`BUILD SUCCESS` confirms COA (feedback=259) + COD (feedback=260) both arrived
with `correlationId == messageId`.

---

### 5.3 Sustained-load profile (#21) — `make load` / `make load-verify`

The **dedicated load profile** (ADR-0007) drives a bounded-yet-sustained run on the live k3d harness and
**asserts** the result. It is **excluded** from the default `make verify` / `mvn verify` gate. All commands
run from the repo root.

```bash
# 0. Harness up (builds the image, spins k3d, deploys all roles)
make up

# 1. Feasibility probe (short run) — read the "offered rate" line to confirm the box sustains ~167 msg/s
make load LOAD_COUNT_PER_POD=500

# 2. Full sustained run (default N=50000 @ ~167 msg/s ≈ 5 min, then 60s settle)
make load
#    Tunables: LOAD_PUB_REPLICAS, LOAD_COUNT_PER_POD, LOAD_INTERVAL_MS, LOAD_SETTLE_SECS

# 3. ASSERT the run (exit non-zero on breach) — run WHILE consumers are still connected (before down)
make load-verify

# 4. Tear down
make down
```

`make load` quiesces the publisher, clears the queues + truncates the audit/ledger (clean slate), launches
`LOAD_PUB_REPLICAS` bounded publishers (each sends exactly `LOAD_COUNT_PER_POD`, then idles), waits for all
to log `PUBLISH-DONE`, and settles. `make load-verify` then asserts, against the known denominator
`N = replicas × count`:

- **zero loss** — `distinct correlation_id` with feedback `259 == N` and `260 == N`;
- **exactly-once across replicas** — no duplicate `(correlation_id, feedback)` rows; `IPPROCS>1` on both queues;
- **latency** — measured p99 ≤ pre-declared ceilings (COA ≤ 5000 ms, COD ≤ 15000 ms), recording p50/p95/p99 as the baseline;
- **no mis-auth** — DLQ `CURDEPTH == 0`.

Baseline numbers + caveats (single-node clock validity, NULL-`sent_at` exclusion, baseline ≠ SLA):
`research-output/phase-h-load-baseline-k3d.md`. The captured `load-verify` output feeds the #22 acceptance
record (`docs/acceptance/prd1-acceptance.md`). **#22's final sign-off remains human (HITL).**

> `make load` leaves the publisher in bounded+idle mode (env `HARNESS_PUBLISH_MAX_COUNT` set). To return to
> the unbounded harness, `make restart` (or re-run `make load`); a `make down`/`make up` cycle resets it.

---

## 6. IBM MQ Web Console Walkthrough

The web console is the IBM MQ graphical administration interface.

URL: **`https://localhost:9443/ibmmq/console`**

### 6.1 Login

1. Open `https://localhost:9443/ibmmq/console` in a browser.
2. Accept the self-signed TLS certificate warning (click "Advanced" →
   "Proceed" or equivalent; this is expected for a local dev container — the
   dev image ships a self-signed cert).
3. Enter credentials:
   - **Username:** `admin`
   - **Password:** `passw0rd` (matches `MQ_ADMIN_PASSWORD` in `docker-compose.yml`)
4. Click **Log in**.

Expected: the IBM MQ console home page loads, showing the queue manager `QM1`.

### 6.2 View the Queue Manager

```mermaid
flowchart LR
    classDef nav  fill:#cfe0ef,stroke:#4a6fa5,color:#1f2430
    classDef page fill:#d7e9d2,stroke:#5a8f63,color:#1f2430
    classDef obj  fill:#f4e6c4,stroke:#b08a3e,color:#1f2430

    A([Login<br/>https://localhost:9443]):::nav -->|Home| B[Queue Managers panel]:::page
    B -->|Click QM1| C[QM1 properties]:::page
    C -->|Queues tab| D[Queue list]:::page
    D -->|Inspect row| E[Queue details<br/>depth / attributes]:::obj
```

Steps:

1. On the console home page, locate the **Queue Managers** panel. It lists `QM1`.
2. Click **QM1** to open the queue manager details page.
3. Click the **Queues** tab in the left-hand navigation (or the Manage section).
4. The queue list shows all local queues, including:
   - `DEV.QUEUE.1` — business queue
   - `DEV.QUEUE.2` — report queue
   - `DEV.DEAD.LETTER.QUEUE` — dead letter queue

The **Depth** column shows the current message count in each queue.

> After running the demo the depths of `DEV.QUEUE.1` and `DEV.QUEUE.2` will
> be **0** — the demo drains both queues. This is expected.

### 6.3 Publish a Test Message to a Queue

These steps let you publish a message directly from the console, independent of
the demo.

1. In the **Queues** list, click the row for **`DEV.QUEUE.1`**.
2. Click the **Create** button (or the **"+"** icon) to open the message
   creation panel.
3. In the message body field, enter a test payload, for example:
   ```json
   {"test": "console-message", "ts": 1}
   ```
4. Click **Put** (or **Send**) to publish the message to the queue.
5. The depth counter for `DEV.QUEUE.1` should increment by 1.

> The exact button labels depend on the IBM MQ console version. Look for
> "Create message", "Put message", or a **+** symbol near the queue row.

### 6.4 Browse Messages

Browsing lets you inspect a message without removing it from the queue (a
non-destructive peek).

1. In the **Queues** list, click the row for **`DEV.QUEUE.1`** (which now has
   depth ≥ 1 from the step above).
2. Click **Browse messages** or the eye/magnifier icon on the row.
3. The console shows the list of messages in the queue, with metadata columns:
   Message ID, Correlation ID, Put timestamp, and a preview of the body.
4. Click any row to expand the full message body and MQMD properties.

You should see the payload you published in step 6.3.

> To clean up after browsing, you can either let the demo consume the message,
> or delete it from the console (select the row → **Delete**).

### 6.5 Inspect Queue Depths at a Glance

The console queue list refreshes on demand. To see the current depth of all
queues, click the **Refresh** icon at the top-right of the queue list.

Key queues for this project:

| Queue | Expected depth after demo | Purpose |
|---|---|---|
| `DEV.QUEUE.1` | 0 (drained by business consumer) | Business destination |
| `DEV.QUEUE.2` | 0 (drained by report consumer) | COA/COD reports |
| `DEV.DEAD.LETTER.QUEUE` | 0 (should be empty on PASS) | DLQ — non-zero means a problem |

> A non-zero depth on `DEV.DEAD.LETTER.QUEUE` after the demo usually indicates
> the 2035 report-PUT authority error (see section 7).

---

## 7. Troubleshooting

### 7.1 Demo logs `[resultado=FAIL]` — report queue empty / no COA or COD

**Symptom:** demo finishes but `[stage=COA]` and/or `[stage=COD]` never appear;
`coaSeen=false` or `codSeen=false` in the FAIL line; `DEV.QUEUE.2` depth is 0
after the demo; `DEV.DEAD.LETTER.QUEUE` depth is non-zero.

**Root cause:** the `app` user lacks **pass-identity-context** authority
(`+passid`) — `+setall` alone is insufficient. The queue manager's report PUT
(a PUT-with-context that passes the original message's identity context) fails
with `MQRC_NOT_AUTHORIZED (2035)` (`AMQ8077W ... passid`) and the report is
routed to the DLQ instead of the report queue.

**Fix:** ensure the demo uses the `admin` channel override by activating the
`demo` Micronaut environment (it applies `DEV.ADMIN.SVRCONN` + `admin` user
from `application-demo.yml`). Use the exact command in section 4.2 — in
particular, pass `-Dmicronaut.environments=demo` **inside** `-Dmn.jvmArgs`.

**In production:** grant the application principal the full context authority
instead of using `admin` — `+setall` alone is insufficient (the QMgr passes the
original message's identity context into the report, so it needs `+passid`;
verified live on k3s where `+put +setall` still failed on `passid`):

```mqsc
SET AUTHREC PROFILE('APP.REPORT.QUEUE') OBJTYPE(QUEUE) +
    PRINCIPAL('appuser') AUTHADD(PUT, PASSID, PASSALL, SETID, SETALL)
```

### 7.2 JVM native-access warning on JDK 25

**Symptom:** the IBM MQ client emits a warning:

```
WARNING: A terminally deprecated method in java.lang.System has been called
...
--enable-native-access
```

**Cause:** the IBM MQ client loads native libraries via `System.loadLibrary`.
JDK 25 requires explicit opt-in.

**Fix:** the demo command already includes `--enable-native-access=ALL-UNNAMED`
inside `-Dmn.jvmArgs`. The Maven Surefire/Failsafe `argLine` also carries it
for test runs. If running the jar directly, add it to the JVM args:

```bash
java --enable-native-access=ALL-UNNAMED -jar target/ibmmq-jms-guide-*.jar
```

### 7.3 Broker not starting / "Could not find a valid Docker environment"

**Symptom:** `docker compose up -d` fails or Testcontainers throws
"Could not find a valid Docker environment".

**Fix:** verify Docker is running and the socket is accessible:

```bash
curl --unix-socket /var/run/docker.sock http://localhost/info | grep -i version
```

If using Docker Desktop on WSL2, ensure the integration is enabled and the
socket is at `/var/run/docker.sock`. Testcontainers 2.0.5 (the version used
here) is compatible with Docker Engine 29+ / API 1.54.

### 7.4 `micronaut.environments=demo` has no effect

**Symptom:** demo runs but the demo runner is not instantiated; the narrated log
lines (`[stage=BANNER]`, `[stage=PRODUCE]`, ...) never appear; `[resultado=...]`
absent.

**Root cause:** `-Dmicronaut.environments=demo` was placed as a bare Maven
`-D` flag instead of inside `-Dmn.jvmArgs`. It configured the Maven JVM, not
the forked application JVM.

**Fix:** use the exact command from section 4.2, with both flags inside
`-Dmn.jvmArgs="..."`.

### 7.5 Build errors on first run (dependency download)

**Symptom:** `mvn mn:run` or `mvn verify` fails on the first run with network
or artifact errors.

**Fix:** the IBM MQ client (`com.ibm.mq.allclient:9.4.5.0`, ~8 MB) is fetched
from Maven Central on first run. Ensure internet access and re-run. If behind
a corporate proxy, configure Maven's `~/.m2/settings.xml` accordingly.
