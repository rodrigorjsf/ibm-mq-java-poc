# IBM MQ + JMS 2.0 + Micronaut 4 — Project Guide

Production-grade reference for integrating **Java 21 / Micronaut 4** with **IBM MQ** via **JMS 2.0 (`javax.jms`)**, focused on **COA/COD delivery reports**. Audience: engineers who do not yet know IBM MQ but must operate it in a real, high-concurrency, critical environment.

## ⭐ Reference-documentation rule (MANDATORY — standing user directive)

Any document, fact sheet, validated API reference, MQSC snippet, or research finding that is needed to make **future adjustments or implementations** on this stack — and that would otherwise force **re-researching the same topics** — MUST be persisted **into this repository** as durable reference documentation. The goal is **zero re-research**.

- **Canonical homes:** `research-output/` for validated facts (source of truth), `docs/` for guides/runbooks.
- **Before** researching any IBM MQ / JMS / Micronaut topic for this project, check `research-output/` first.
- **After** any non-trivial research, write the result there and reference it from this `CLAUDE.md`.
- Reference docs are durable artifacts → written in **English** (per global conventions); narrative deliverables for the user are pt-BR.

## Validated facts (full detail: `research-output/phase-a-fact-sheet.md`)

Constant values below were extracted from the **authentic `com.ibm.mq.allclient:9.4.5.0` jar bytecode** (sha1 verified) and cross-checked against IBM docs.

- **Client:** `com.ibm.mq:com.ibm.mq.allclient:9.4.5.0` — `javax.jms` / JMS 2.0 (depends on `javax.jms:javax.jms-api:2.0.1`). Jakarta sibling: `com.ibm.mq.jakarta.client`.
- **Pool:** `org.messaginghub:pooled-jms:2.0.9` — javax line (1.x and 2.x are javax; **3.x is jakarta**). Class `org.messaginghub.pooled.jms.JmsPoolConnectionFactory`.
- **Micronaut:** platform BOM `io.micronaut.platform:micronaut-platform:4.9.4` — **`4.9.9` does NOT exist** (BOM line stops at 4.9.4). Plugin `io.micronaut.maven:micronaut-maven-plugin:4.11.6`.
- **Report constants:** `MQRO_*` and `MQFB_*` live in `com.ibm.mq.constants.CMQC` / `MQConstants` (**NOT** `WMQConstants`). JMS report request props are `WMQConstants.JMS_IBM_REPORT_*` (field UPPER_SNAKE; String value mixed-case, e.g. `"JMS_IBM_Report_COA"`).
- **Feedback codes:** `MQFB_COA=259`, `MQFB_COD=260`, `MQFB_EXPIRATION=258`, `MQFB_PAN=275`, `MQFB_NAN=276`. Exception reports carry an `MQRC_*` in the Feedback field (there is no `MQFB_EXCEPTION`). Read via `WMQConstants.JMS_IBM_FEEDBACK` (canonical, always-populated; `JMS_IBM_MQMD_FEEDBACK` only with `WMQ_MQMD_READ_ENABLED=true`).
- **Report semantics:** default id propagation `MQRO_COPY_MSG_ID_TO_CORREL_ID` (=0) → original MessageId becomes the report's CorrelationId. Report messages **INHERIT persistence from the original** (a persistent original yields a persistent COD/COA by default — the brief's "non-persistent by default" assumption was refuted).
- **MQCSP auth:** `WMQConstants.USER_AUTHENTICATION_MQCSP` (no `WMQ_` prefix), boolean. TLS: do **not** set the removed `com.ibm.mq.cfg.useIBMCipherMappings` (gone since MQ 9.4.0); avoid `TLS_RSA_*` ciphers (disabled on Java 25). MQSC verb is `SET CHLAUTH` (not `DEFINE`).
- **Container:** `icr.io/ibm-messaging/mq:9.4.5.0-r2` (MQ Advanced for Developers, `LICENSE=accept`); dev defaults `DEV.QUEUE.1/2/3`, `DEV.DEAD.LETTER.QUEUE`, channels `DEV.APP.SVRCONN`/`DEV.ADMIN.SVRCONN`, users `app`/`admin`, listener 1414, console 9443. There is no bare `9.4.5.0` tag (tags are `-rN`).

## Local toolchain & environment quirks

- **JDK:** Amazon Corretto **25** at `~/.local/jdk25` (`JAVA_HOME`). **Maven** 3.9.9 at `~/.local/maven-current`. Helper: `source ~/.local/ibmmq-env.sh`.
- Project targets **`maven.compiler.release=21`** (production = Java 21) but builds/runs on Corretto 25 locally.
- Run/test with JVM arg **`--enable-native-access=ALL-UNNAMED`** (silences the MQ client native-access warning on JDK 25; already wired into surefire/failsafe `argLine`).
- **Testcontainers stack:** core **`org.testcontainers:testcontainers:2.0.5`** + official IBM module **`com.ibm.mq:mq-java-testcontainer:2.0.3`** (class `com.ibm.mq.testcontainers.MQContainer`, extends `GenericContainer`; the module 2.0.3 pulls core 2.0.3 transitively — core 2.0.5 is declared directly to override it). IT uses manual lifecycle (`@BeforeAll`/`@AfterAll`), so the `org.testcontainers:junit-jupiter` module is NOT needed.
- **Docker connectivity (root cause + real fix):** on Docker Desktop / engine 29.x (API 1.54, min 1.40), the **docker-java bundled in Testcontainers 1.20.x is incompatible** with the Docker Desktop socket proxy → daemon returns **HTTP 400** → "Could not find a valid Docker environment". `DOCKER_API_VERSION=1.44` did **NOT** fix this; the real fix was **migrating to Testcontainers 2.0.5** (modern docker-java 3.7.1). (`/var/run/docker.sock` is healthy — `curl /info` returns 200.) The `DOCKER_API_VERSION=1.44` env in the failsafe plugin is kept as harmless belt-and-suspenders.
- **commons-codec pin:** `docker-java-transport-zerodep:3.7.1` (via TC 2.0.5) references `org.apache.commons.codec.Charsets`, removed in commons-codec 1.17+. Pin **`commons-codec:commons-codec:1.16.1`** (test scope) or the container fails to start with `NoClassDefFoundError`.
- **Report-PUT authority gotcha (2035):** for the QMgr to generate+deliver a COA/COD report it does a PUT-with-context to the ReplyToQ, needing context authority (`+setall`). The dev image's low-priv `app` user lacks it → report PUT fails `MQRC_NOT_AUTHORIZED (2035)` and the report silently goes to the DLQ (report queue stays empty). The IT therefore connects as **`admin`** (`DEV.ADMIN.SVRCONN`); production grants the app principal `SET AUTHREC ... AUTHADD(PUT, SETALL)`. Verified end-to-end: COA(259)+COD(260) delivered, CorrelId == original MessageId.
- **Sandbox:** when running the IT through a Claude Bash tool, disable the sandbox so the forked JVM can reach `/var/run/docker.sock`.

## Project layout

- `ibmmq-jms-guide/` — runnable Micronaut Maven project (COA/COD end-to-end demo; `mvn test` for unit, `mvn verify` for the Testcontainers IT).
- `research-output/` — validated fact sheets + raw research JSON (source of truth).
- `research-prompt-ibmmq-jms-micronaut.md` — the locked research brief driving this work.
- Markdown guide + standalone HTML doc — primary deliverables (generated by the pipeline).
