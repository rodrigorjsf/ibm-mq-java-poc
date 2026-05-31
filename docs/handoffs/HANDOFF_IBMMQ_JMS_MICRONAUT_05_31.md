# Handoff: IBM MQ + JMS 2.0 + Micronaut 4 (COA/COD) — production guide deliverable

**Created:** 2026-05-31
**Repo:** `/home/rodrigo/IBM-MQ` (NOT a git repo — no branches/commits; state lives in files on disk)
**Driver:** the locked brief `research-prompt-ibmmq-jms-micronaut.md` (premises P1–P20 are TRAVADAS — do not reopen)

---

## Summary

Building a production-grade, pt-BR deliverable: a runnable Micronaut 4 + IBM MQ (JMS 2.0 / `javax.jms`) project demonstrating COA/COD delivery reports, plus a Markdown guide, plus a standalone HTML page. **Phases A (fact validation), Environment, B (runnable project — verified end-to-end), and C (Markdown guide) are DONE and verified.** Remaining: **Phase D (single-file HTML)** and **Phase E (final 3.1 checklist validation)**. The slash entry point was `/academic-research-skills:deep-research`, but this is an engineering deliverable executed via the brief's own §4.1 plan (A→E), NOT the ARS academic/APA pipeline — keep it that way.

---

## ⏭️ NEXT SESSION — DO THIS FIRST (mandatory)

1. **Recreate the remaining task list via `TaskCreate`** (the prior session's task list does not persist). Create at minimum:
   - **Phase D — Build single-file standalone HTML** (`docs/index.html` or `index.html`) from `docs/guia-ibmmq-jms-micronaut.md`, per brief P17 + P20.
   - **Phase E — Final validation against brief §3.1 checklist** (run `mvn clean verify`, walk the 10-item checklist).
   Add sub-steps as needed (e.g. D: nav/sidebar, syntax highlight + copy, callouts ✅/❌/⚠️/ℹ️, WCAG AA, responsive; E: each checklist line).
2. **Read these for grounding (in order):** `research-prompt-ibmmq-jms-micronaut.md` (§ESTRUTURA, P17, P20, §3.1), `CLAUDE.md` (validated facts + env quirks + the mandatory reference-doc rule), `docs/guia-ibmmq-jms-micronaut.md` (the source content the HTML must mirror).

---

## Skills to reference and USE in the continuation

- **`compound-engineering:ce-frontend-design` — REQUIRED for Phase D (HTML).** Use it ONLY as a code-quality/structure/micro-interaction engine. **P20 OVERRIDES its aesthetic bias:** neutral, eye-friendly palette (no pure `#000` on `#fff`, no saturated colors), reading typography (Inter / system-ui body ≥16px, line-height ~1.6; JetBrains Mono / Fira Code for code), **light mode default**, sober desaturated accent. Do NOT follow frontend-design's "bold colors / avoid Inter / maximalist" advice. Reading comfort > visual impact.
- **`handoff`** — used to produce this document.
- **`academic-research-skills:deep-research`** — the original entry point. Research/validation phase is COMPLETE (see `research-output/`). Re-invoke only if NEW facts must be validated.

---

## Work Completed

### Changes Made
- [x] **Phase A** — validated IBM facts via a Workflow (5 web agents, bytecode-verified against the authentic `allclient:9.4.5.0` jar). Output: `research-output/phase-a-fact-sheet.md` (+ raw JSON).
- [x] **Environment** — installed Amazon **Corretto 25.0.3** (`~/.local/jdk25`) + **Maven 3.9.9** (`~/.local/maven-current`); Docker daemon reachable. Helper: `source ~/.local/ibmmq-env.sh`.
- [x] **Phase B** — generated runnable project `ibmmq-jms-guide/`; **compiles** (`release=21` on Corretto 25), **15 unit tests green**, **IT COA/COD GREEN end-to-end** against a real broker (Testcontainers 2.0.5 + IBM `MQContainer` 2.0.3): COA(259)+COD(260) delivered, `CorrelId == original MessageId`.
- [x] **Phase C** — wrote `docs/guia-ibmmq-jms-micronaut.md` (~9,400 words, 880 lines, 100% pt-BR; all Sections 1–5 + Appendices; 50 callouts; feedback-codes table; ✅/❌ pairs).
- [x] Standing user rule recorded in `CLAUDE.md` + memory `save-reference-docs-to-repo`: persist all stack research into the repo to avoid re-research.

### Key Decisions
| Decision | Rationale | Alternatives |
| --- | --- | --- |
| Execute brief §4.1 (A→E), not ARS academic pipeline | It's an engineering deliverable, not a paper | Running deep-research's FINER/APA flow (rejected) |
| Micronaut platform BOM **4.9.4** | **4.9.9 BOM does not exist** (line stops at 4.9.4); user chose 4.9.4 over 4.10.x | 4.10.x |
| `pooled-jms` **2.0.9** | Current maintained javax line (3.x = jakarta) | 1.2.8 |
| Build/run on **Corretto 25**, target `release=21` | User choice; MQ 9.4 certifies Java 21; runs on 25 with `--enable-native-access=ALL-UNNAMED`, avoid `TLS_RSA_*` | Install JDK 21 (unneeded) |
| **Testcontainers 2.0.5 + `mq-java-testcontainer` 2.0.3** | User-requested; TC 2.x's modern docker-java fixes the Docker Desktop HTTP-400 | TC 1.20.4 GenericContainer (broke on Docker 29.x) |
| IT connects as **`admin`** | QMgr report PUT needs context auth (`+setall`); dev `app` user lacks it → 2035 → DLQ | Grant `SET AUTHREC ... AUTHADD(PUT,SETALL)` to app (documented in guide for prod) |

---

## Files Affected

### Created
- `research-output/phase-a-fact-sheet.md` — validated facts (SOURCE OF TRUTH) + `phase-a-raw.json`.
- `ibmmq-jms-guide/` — full Maven project: `pom.xml`, `src/main/java/com/example/ibmmq/**` (Application, config/MqProperties + MqConnectionFactoryFactory, producer/BusinessMessageProducer, consumer/BusinessMessageConsumer + ReportMessageConsumer, correlation/*, report/ReportFeedbackRouter, model/*), `src/main/resources/{application.yml,logback.xml}`, `src/test/java/.../{InMemoryCorrelationStoreTest, report/ReportFeedbackRouterTest, integration/CoaCodEndToEndIT}.java`, `mqsc/{10-channel-auth,20-queues}.mqsc`, `docker-compose.yml`, `README.md`.
- `docs/guia-ibmmq-jms-micronaut.md` — the pt-BR guide.
- `CLAUDE.md` — repo guide: reference-doc rule + validated facts + env quirks.
- `~/.claude/projects/-home-rodrigo-IBM-MQ/memory/{MEMORY.md,save-reference-docs-to-repo.md}`.
- `~/.local/{jdk25→corretto, maven-current→maven, ibmmq-env.sh}`.

### Modified (during the IT debug saga)
- `ibmmq-jms-guide/pom.xml` — TC 2.0.5 + `mq-java-testcontainer` 2.0.3; `commons-codec:1.16.1` (test); failsafe `--enable-native-access=ALL-UNNAMED` + `DOCKER_API_VERSION=1.44` (harmless).
- `ibmmq-jms-guide/src/test/java/com/example/ibmmq/integration/CoaCodEndToEndIT.java` — final clean version uses `MQContainer` + admin connection.

### Read (reference)
- `research-prompt-ibmmq-jms-micronaut.md` — the locked brief.

---

## Technical Context

- **Architecture:** Micronaut used ONLY for DI / `@ConfigurationProperties` / `@Factory` / lifecycle; JMS managed by hand (`JMSContext`). `JmsPoolConnectionFactory` (pooled-jms) wraps `MQConnectionFactory` (CLIENT mode). Report consumer reads feedback via `WMQConstants.JMS_IBM_FEEDBACK` and correlates `report.JMSCorrelationID → original MessageId`.
- **Toolchain:** `source ~/.local/ibmmq-env.sh` then `JAVA_HOME=~/.local/jdk25` + `~/.local/maven-current/bin/mvn`.
- **MQ image:** `icr.io/ibm-messaging/mq:9.4.5.0-r2` (no bare `9.4.5.0` tag).

---

## Things to Know — Gotchas (the 4 golden lessons, all in the guide §5.2 + CLAUDE.md)

1. **Docker connectivity:** TC 1.20.x docker-java is incompatible with Docker Desktop engine 29.x (HTTP 400 "Could not find a valid Docker environment"). `DOCKER_API_VERSION` did NOT fix it; **TC 2.0.5 did**.
2. **commons-codec:** `docker-java-transport-zerodep:3.7.1` needs `org.apache.commons.codec.Charsets` (removed in 1.17+) → pin **`commons-codec:1.16.1`** (test).
3. **Corretto 25:** run with `--enable-native-access=ALL-UNNAMED`; avoid `TLS_RSA_*` ciphers.
4. **Report-PUT authority (2035):** low-priv user lacks `+setall` → QMgr COA/COD report PUT fails `MQRC_NOT_AUTHORIZED (2035)` → report silently goes to DLQ → report queue empty. IT uses `admin`; prod grants `SET AUTHREC ... AUTHADD(PUT, SETALL)`.

**Fact corrections baked in (do not regress):** report messages **INHERIT persistence** from the original (brief's "non-persistent by default" was wrong); `MQRO_*`/`MQFB_*` live in `com.ibm.mq.constants.CMQC`/`MQConstants` (NOT `WMQConstants`); MQCSP field is `USER_AUTHENTICATION_MQCSP` (no `WMQ_` prefix); MQSC verb is `SET CHLAUTH`; `useIBMCipherMappings` removed in 9.4.0.

**When running the IT via a Claude Bash tool: disable the sandbox** (`dangerouslyDisableSandbox: true`) so the forked JVM reaches `/var/run/docker.sock`.

---

## Current State

### Working
- `ibmmq-jms-guide` compiles; 15 unit tests pass; COA/COD IT passes end-to-end on a real broker.
- `docs/guia-ibmmq-jms-micronaut.md` complete and content-verified.

### Pending
- **Phase D** — the single-file HTML doc has NOT been started.
- **Phase E** — the §3.1 checklist has NOT been run as a formal gate.

### Tests
- [x] Unit tests: 15 passing (`mvn test`).
- [x] Integration test: COA/COD passing (`mvn verify`, Docker required, sandbox off).
- [ ] Manual: HTML page not built yet.

---

## Next Steps

### Immediate (Phase D — HTML)
1. Create the remaining `TaskCreate` list (see top).
2. Generate a **single standalone HTML file** (embedded CSS/JS, no backend, no required external deps) that mirrors ALL sections of `docs/guia-ibmmq-jms-micronaut.md`, per **P17** (fixed left nav with active-section highlight; navigable TOC; code blocks with syntax highlight + copy button; distinct callouts ✅ Boa prática / ❌ Má prática / ⚠️ Atenção / ℹ️ Nota — the guide already uses these markers; responsive; WCAG AA contrast ≥4.5:1) and **P20** palette/typography (see Skills section). Prefer delegating to a subagent (P18) that receives the consolidated guide and returns the finished HTML; pass it the P20 override verbatim. `ce-frontend-design` = quality engine only.

### Subsequent (Phase E — validation)
3. Run `cd` env + `mvn -f ibmmq-jms-guide/pom.xml clean verify` (sandbox off) for a pristine end-to-end pass.
4. Walk brief **§3.1** item by item: 2 artifacts (MD + HTML), 100% pt-BR (incl. HTML UI), runnable project, all sections, COA/COD exhaustive + feedback table, ✅/❌ pairs, HTML nav/highlight/copy/callouts/WCAG, P20 palette, IBM-validated APIs, context-efficient execution. Report pass/fail per line.

### Blocked On
- Nothing. Docker is up; toolchain installed.

---

## Commands to Run

```bash
source /home/rodrigo/.local/ibmmq-env.sh           # JAVA_HOME (Corretto 25) + Maven on PATH
P=/home/rodrigo/IBM-MQ/ibmmq-jms-guide
mvn -f $P/pom.xml test                              # unit tests (fast)
# IT (needs Docker; run via Bash tool with dangerouslyDisableSandbox:true):
export DOCKER_API_VERSION=1.44 DOCKER_HOST=unix:///var/run/docker.sock TESTCONTAINERS_RYUK_DISABLED=true
mvn -f $P/pom.xml clean verify                      # full: unit + COA/COD IT
```

### Search Queries
- `grep -rn "JMS_IBM_REPORT" ibmmq-jms-guide/src` — report-option wiring.
- `grep -n "AUTHADD(PUT, SETALL)" ibmmq-jms-guide` — the 2035 authority fix.
- headings: `grep -nE '^## ' docs/guia-ibmmq-jms-micronaut.md` — sections the HTML must mirror.

---

## Open Questions
- [ ] HTML output filename/location: `docs/index.html` vs repo-root `index.html` (brief says "abrir direto no navegador"; pick one and note it in Phase E).
- [ ] Keep the IT on `admin`, or add a `withStartupMQSC` `SET AUTHREC` grant so it runs as `app` (more production-realistic)? Current `admin` approach is green and documented; optional polish.

---

## Session Notes
- The IT took ~7 iterations to go green; root causes were all environment/dev-container, not COA/COD logic (logic was unit-validated and MQMD-verified `Report=2304` early). Don't re-litigate the logic.
- Orchestration used the `Workflow` tool (Phase A fan-out) and `Agent` subagents (Phases B, C) to keep the main window lean (P18). Continue that pattern for Phase D.
- Communication with the user is **pt-BR**; durable artifacts (code, this handoff, CLAUDE.md, guide stays as-is) follow the English/pt-BR split in the user's global rules (guide is an explicit pt-BR deliverable).

---

_Generated via the `handoff` skill. Start a new session, read the "DO THIS FIRST" block, and continue at Phase D._
