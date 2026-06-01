# Handoff: IBM MQ + JMS 2.0 + Micronaut 4 (COA/COD) — production guide deliverable

**Created:** 2026-05-31
**Repo:** `/home/rodrigo/IBM-MQ` (NOT a git repo — no branches/commits; state lives in files on disk)
**Driver:** the locked brief `research-prompt-ibmmq-jms-micronaut.md` (premises P1–P20 are TRAVADAS — do not reopen)

---

## Summary

Building a production-grade, pt-BR deliverable: a runnable Micronaut 4 + IBM MQ (JMS 2.0 / `javax.jms`) project demonstrating COA/COD delivery reports, plus a Markdown guide, plus a standalone HTML page. **Phases A (fact validation), Environment, B (runnable project — verified end-to-end), and C (Markdown guide) are DONE and verified.** **Remaining work is now two-tier.** **Tier 1 — closes the locked brief (P1–P20):** **TASK_5** (Java 21→25 prose alignment — *done this session*, see Work Completed) → **Phase D** (single-file HTML, generated from the corrected guide) → **Phase E** (final §3.1 checklist). **Tier 2 — post-brief hardening backlog** (specified in the APPENDIX at the end of this doc, **not** yet executed): **TASK_2** (COA/COD MQMD field recovery — research + impl + tests), **TASK_3** (integration-test scenario matrix + Virtual Threads + load/volumetry), **TASK_4** (project-agnostic LLM code-analysis skill at `ai/skills/`). Phases A–E close the brief; TASK_2–4 are post-brief — D+E are **not** the only remaining work. The slash entry point was `/academic-research-skills:deep-research`, but this is an engineering deliverable executed via the brief's own §4.1 plan (A→E), NOT the ARS academic/APA pipeline — keep it that way.

---

## ⏭️ NEXT SESSION — DO THIS FIRST (mandatory)

1. **Recreate the remaining task list via `TaskCreate`** (the prior session's task list does not persist). Use this **execution ordering** (rationale in the APPENDIX; TASK_5 is already done):
   - **(done) TASK_5 — Java 21→25 prose alignment.** Already executed this session — verify only (re-grep for stale `Java 21`/`release=21`).
   - **Phase D — Build single-file standalone HTML** (`docs/index.html` or `index.html`) from the **already-corrected** `docs/guia-ibmmq-jms-micronaut.md`, per brief P17 + P20. (TASK_5 ran first precisely so the HTML mirrors corrected content.)
   - **Phase E — Final validation against brief §3.1 checklist** (run `mvn clean verify`, walk the 10-item checklist).
   - **TASK_2 — COA/COD MQMD field recovery** (deep-research + impl + tests). Owns the verified field/property names TASK_3 imports.
   - **TASK_3 — integration-test scenario matrix + Virtual Threads + load/volumetry** (after TASK_2 — its field-recovery IT depends on TASK_2's names).
   - **TASK_4 — project-agnostic LLM analysis skill at `ai/skills/`** (last, for evidence-harvesting; **soft**-coupled to TASK_2/3, not hard-gated).
   Add sub-steps as needed (e.g. D: nav/sidebar, syntax highlight + copy, callouts ✅/❌/⚠️/ℹ️, WCAG AA, responsive; E: each checklist line). **Note:** TASK_3 and TASK_5 both edit the guide — any guide edit after Phase D means the HTML must be **regenerated** (not hand-patched) and Phase E's "HTML mirrors all sections" item re-checked.
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
- [x] **Phase B** — generated runnable project `ibmmq-jms-guide/`; **compiles** (`release=25` on Corretto 25 — *originally `release=21`; superseded 2026-05-31, see TASK_5 / ADR-0001; `mvn clean test-compile` re-verified GREEN this session on Corretto 25.0.3*), **15 unit tests green**, **IT COA/COD GREEN end-to-end** against a real broker (Testcontainers 2.0.5 + IBM `MQContainer` 2.0.3): COA(259)+COD(260) delivered, `CorrelId == original MessageId`.
- [x] **Phase C** — wrote `docs/guia-ibmmq-jms-micronaut.md` (~9,400 words, 880 lines, 100% pt-BR; all Sections 1–5 + Appendices; 50 callouts; feedback-codes table; ✅/❌ pairs).
- [x] Standing user rule recorded in `CLAUDE.md` + memory `save-reference-docs-to-repo`: persist all stack research into the repo to avoid re-research.
- [x] **TASK_1 (2026-05-31)** — hardened `CLAUDE.md` (via `improve-claude`): added the **non-negotiable, no-bypass documentation-currency rule** and the standing **distributed k8s + microservices + ~10k rpm** analysis mandate; preserved the validated-facts/env-quirks blocks.
- [x] **TASK_5 (2026-05-31)** — aligned all prose **Java 21 → 25** (production runtime is now Java 25; **supersedes brief P1**) across `CLAUDE.md`, root `README.md`, `ibmmq-jms-guide/README.md`, the pt-BR guide (incl. the **§5.7 Virtual Threads reframing for JEP 491** and the §1.3 CD/LTS note + §5.9 summary-row), and the `pom.xml` comments; recorded the decision in **`docs/adr/0001-java-25-runtime.md`**. Verified: re-grep shows zero stale `Java 21`/`release=21` outside intentional supersession-explainers. (Grilled + advisor-checked before executing.)

### Key Decisions
| Decision | Rationale | Alternatives |
| --- | --- | --- |
| Execute brief §4.1 (A→E), not ARS academic pipeline | It's an engineering deliverable, not a paper | Running deep-research's FINER/APA flow (rejected) |
| Micronaut platform BOM **4.9.4** | **4.9.9 BOM does not exist** (line stops at 4.9.4); user chose 4.9.4 over 4.10.x | 4.10.x |
| `pooled-jms` **2.0.9** | Current maintained javax line (3.x = jakarta) | 1.2.8 |
| Build/run on **Corretto 25**, target `release=25` *(superseded 2026-05-31 → was `release=21`; ADR-0001 / TASK_5)* | Java 25 is the in-use LTS, documented for MQ 9.4.x; runs with `--enable-native-access=ALL-UNNAMED`, avoid `TLS_RSA_*`; JEP 491 improves the Virtual-Threads story | Java 21 (per brief P1 — now superseded) |
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

**Brief deliverable (P1–P20) — Tier 1:**
- **Phase D** — the single-file HTML doc has NOT been started (build it from the TASK_5-corrected guide).
- **Phase E** — the §3.1 checklist has NOT been run as a formal gate.

**Post-brief hardening (deferred, specified-not-executed — see APPENDIX) — Tier 2:**
- **TASK_2** — COA/COD MQMD field recovery (research + impl + tests). → APPENDIX TASK_2.
- **TASK_3** — integration-test scenario matrix + Virtual Threads + load/volumetry (depends on TASK_2). → APPENDIX TASK_3.
- **TASK_4** — project-agnostic LLM code-analysis skill at `ai/skills/` (soft-coupled to TASK_2/3). → APPENDIX TASK_4.
- **TASK_5** — *DONE this session* (Java 21→25 alignment); see Work Completed.

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
- No environment blockers (Docker up; toolchain installed). But there are **intra-task gates** to honor:
  - **Phase D** ⟸ TASK_5 (HTML must mirror the corrected guide) — *already satisfied, TASK_5 is done.*
  - **TASK_3 Group C (FieldRecoveryIT)** ⟸ TASK_2 (owns the six field/property names; do not re-derive).
  - **TASK_3 Group B (Virtual Threads)** and **TASK_4 concurrency wording** ⟸ TASK_5's JEP-491 reframing — *already satisfied.*
  - Any guide edit by TASK_3/TASK_5 **after** Phase D ⟹ regenerate the HTML and re-check Phase E's "HTML mirrors all sections".

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

**Tier 1 (brief deliverable):**
- [ ] HTML output filename/location: `docs/index.html` vs repo-root `index.html` (brief says "abrir direto no navegador"; pick one and note it in Phase E).
- [ ] Keep the IT on `admin`, or add a `withStartupMQSC` `SET AUTHREC` grant so it runs as `app` (more production-realistic)? Current `admin` approach is green and documented; optional polish.

**Tier 2 (each deferred TASK carries its own in-task open questions — resolved during *its* execution, not blockers for D/E):**
- [ ] **TASK_2** — 4 mechanism questions to close in the deep-research: exact `byte[]` getter for MQMD props (`getObjectProperty`→`byte[]` vs hex `String`); `queue:///` URI param (`mdReadEnabled=true`?) vs `MQDestination` cast under the pool wrapper; per-field **R-vs-O** verdict (does a plain report carry the *original's* values or is `WITH_FULL_DATA` required); whether producer identity-context needs `+setid`/`+setall` (ties to the 2035 gotcha).
- [ ] **TASK_3** — load-harness choice (e.g. `ScheduledExecutorService` vs a tool) and whether the DLQ is browsable as the connected principal (for the A1 assertion).
- [ ] **TASK_4** — whether the dynamic `/workflows` tool is available in the build session (else the skill main thread fans out sequentially); whether to ship a `haiku` lookup-helper subagent or fold it into `cell-analyzer`.

---

## Session Notes
- The IT took ~7 iterations to go green; root causes were all environment/dev-container, not COA/COD logic (logic was unit-validated and MQMD-verified `Report=2304` early). Don't re-litigate the logic.
- Orchestration used the `Workflow` tool (Phase A fan-out) and `Agent` subagents (Phases B, C) to keep the main window lean (P18). Continue that pattern for Phase D.
- Communication with the user is **pt-BR**; durable artifacts (code, this handoff, CLAUDE.md, guide stays as-is) follow the English/pt-BR split in the user's global rules (guide is an explicit pt-BR deliverable).

---

---

# APPENDIX — Post-brief hardening backlog (TASK_2 / TASK_3 / TASK_4) + TASK_5 completion record

> These sections were drafted by a parallel Workflow (4 grounded agents + a coherence critic) and reconciled against a `/grill-with-docs` session with the user on 2026-05-31. **Read the coordination block first** — it carries the binding decisions and cross-section reconciliations that OVERRIDE any "research must decide / TBD" language inside the individual sections.

## ⚠️ Cross-task coordination & reconciliations (READ BEFORE EXECUTING)

### Binding decisions from the 2026-05-31 grilling (these OVERRIDE looser wording below)
1. **Production runtime = Java 25** (supersedes brief P1). Recorded in `docs/adr/0001-java-25-runtime.md`. The TASK_5 prose sweep is **DONE this session** (see the TASK_5 completion record below).
2. **TASK_4 form factor = a SKILL at `ai/skills/jms-mq-delivery-report-analyzer/`** (NOT a single paste-doc). The conditional "doc vs skill" language is resolved → **skill**.
3. **TASK_2 `report type` char = DERIVED**, not a native MQ field: `MQFB_COA(259) → 'A'`, `MQFB_COD(260) → 'D'`. `model/ReportType.java` already encodes COA/COD — add a `char toDomainChar()` projection. (Codebase-confirmed; no research needed for this point.)
4. **TASK_2 `message timestamp` = BOTH** the *original* message's put-time AND the *report's* put-time, each converted to `LocalDateTime` in **UTC** (documented as UTC). Rationale: both enable end-to-end delivery-latency measurement (original-put → COD-generation) under the ~10k-rpm SLO. ⟹ `DeliveryEvent` gains **two** `LocalDateTime` fields (e.g. `originalPutTimestampUtc`, `reportPutTimestampUtc`); recovering the *original's* put-time forces the `WITH_FULL_DATA` / correlation-store path (see R2).

### Reconciliations (resolve the cross-section contradictions the critic found)
- **R1 — Who owns the six-field × {COA,COD} assertion matrix?** **TASK_3 Group C `FieldRecoveryIT` owns it** (the exhaustive 12-assertion matrix). **TASK_2 scopes its `CoaCodEndToEndIT` change down to a minimal wiring smoke-assert** (prove `mdReadEnabled` works + one field non-null per report) plus the isolated `MqmdTimestampsTest` unit test. Do not assert the full matrix in two ITs.
- **R2 — `WITH_FULL_DATA` interaction.** TASK_2's R-vs-O verdict may switch the producer from plain `MQRO_COA`/`MQRO_COD` to `MQRO_COA_WITH_FULL_DATA(1792)`/`MQRO_COD_WITH_FULL_DATA(14336)`. If it does, the existing happy-path `CoaCodEndToEndIT` and TASK_3 A2/A7 assumptions about a **plain/empty report body** change. Reconcile TASK_2's producer-default decision with TASK_3 A7 (`ReportWithDataPayloadIT`) and the happy-path IT **before either lands**.
- **R3 — HTML regeneration obligation (extends to TASK_3).** TASK_3 edits the guide (§5.1/§5.2/§5.7 cross-links + §5.7 VT numbers), exactly like TASK_5 did. If Phase D's single-file HTML already exists when TASK_3 runs, the HTML **MUST be regenerated from the corrected Markdown** (not hand-patched) and Phase E's "HTML mirrors all guide sections" item re-checked. (Do **not** reorder Phase D after TASK_3 — that needlessly delays closing the brief.)
- **R4 — TASK_4 dependency is SOFT, not a hard gate.** TASK_4 is project-agnostic (cannot reference repo impl), so it has **no hard build dependency** on TASK_2/3. It is placed last only to harvest consolidated evidence; its sole hard constraint is that its concurrency-dimension JEP-491 wording stays consistent with TASK_5's §5.7 reframing (already done).
- **R5 — TASK_5 in-file annotations match by CONTENT, not line number.** Appending this appendix shifted every handoff line number. TASK_5's "annotate lines 38/48 as superseded" was applied by matching the text `release=21` / `target release=21` (already done in Work Completed / Key Decisions above), not by the quoted line numbers.
- **R6 — TASK_2's "bytecode-verified" labels are sub-agent-reported, not independently re-confirmed this session.** The JMS-spec-level facts hold (`getJMSCorrelationIDAsBytes()` exists; there is **no** `getJMSMessageIDAsBytes()`), but the IBM-specific `JMS_IBM_MQMD_*` / `WMQ_MQMD_*` property-key strings and enablement flags came from the drafting agent's `javap` run — they were **not** re-checked against the jar this turn. The TASK_2 deep-research pass MUST re-verify each key against `com.ibm.mq.allclient:9.4.5.0` before relying on it; do **not** skip re-checking on the strength of the "bytecode-verified" wording in the TASK_2 section.

### Shared-file & re-validation coordination (the critic's "missing" list)
- **Phase E re-validation hook:** any post-Phase-D guide edit (TASK_3 cross-links, or a late TASK_5 touch) re-triggers a targeted Phase E re-check of the §3.1 "HTML mirrors ALL sections" and "100% pt-BR (incl. HTML UI)" items.
- **`docs/testing-scenarios.md` (TASK_3) ↔ `ai/skills/.../references/scenario-matrix.md` (TASK_4)** cover the same MQ/JMS failure modes from two angles. Keep them **conceptually in sync** — TASK_4's catalog is the de-identified generalization of TASK_3's repo-specific catalog — **without cross-importing** (TASK_4's leak-check forbids repo paths).
- **`DeliveryEvent` is a breaking model change:** TASK_2 extends the record (adds `applIdentityData`, `accountingToken`, byte[] ids, two UTC timestamps, report-type char), changing every call-site and any test asserting its current shape. TASK_3's ITs (which build `DeliveryEvent`s) must track it.
- **`mqsc/20-queues.mqsc` is edited by multiple tasks** (TASK_3 A6 `BOTHRESH`/`BOQNAME`, A8 `MAXDEPTH(5)`; TASK_2/A1 report-queue context-auth). No single section owns the merged file — coordinate the `ALTER`/`DEFINE` statements so they don't collide.

### Skills/tools the continuation must use (standing "reference every used skill" rule)
- **`/workflows`** — used this session to draft these sections; TASK_4's skill itself orchestrates via `/workflows`.
- **`academic-research-skills:deep-research`** — TASK_2 is a new deep-research pass at the same rigor (bytecode-verified + IBM-doc cross-checked + persisted to `research-output/`).
- **`create-skill`** + **`create-subagent`** — TASK_4 builds the `ai/skills/` skill and its subagent *templates* per these conventions (canonical semantic tags; least-privilege read-only tools; `sonnet` default).
- **`grill-me`** — TASK_4's L1 embeds the grill-me interview as its first phase; this session used **`grill-with-docs`** to lock the decisions above.
- **`compound-engineering:ce-frontend-design`** — still REQUIRED for Phase D (HTML), quality-engine-only, with the brief's P20 palette override (see the Skills section above).

## TASK_5 — COMPLETED 2026-05-31 (Java 21 → 25 alignment)

**Status: DONE and verified this session.** Production runtime standardized on **Java 25** (Amazon Corretto 25), superseding brief P1. What was executed:

- **ADR created:** `docs/adr/0001-java-25-runtime.md` (accepted; supersedes P1; trade-off + JEP-491 consequence recorded).
- **Prose swept 21 → 25:** `CLAUDE.md` (title + Java-target bullet now cites the ADR), root `README.md` (title, stack table, "Requires JDK 25+"), `ibmmq-jms-guide/README.md` (pre-reqs), the pt-BR guide (title, stack table, §1.2, §1.3, §4 `release=25`), and `ibmmq-jms-guide/pom.xml` comments (the value was already `25`; only the lying comments were fixed).
- **Two non-mechanical reframings applied (not find-replace):**
  - **§1.3** — re-anchored the "why 9.3 rejected / CD-vs-LTS" narrative on the `9.4.5.0` CD client choice; kept "(LTS)" (Java 25 is an LTS); distinguished bundled Semeru 21 from the chosen Java-25 runtime.
  - **§5.7 + §5.9 summary-row (Virtual Threads)** — reframed for **JEP 491** (JDK 24+): `synchronized`-block pinning **resolved**; residual pinning only on native/JNI frames (measure with `-Djdk.tracePinnedThreads=full` / JFR `jdk.VirtualThreadPinned`); kept the `Session`/`JMSContext`-not-thread-safe warning; added the ~10k-rpm "connection-storm" anti-pattern.
- **NOT touched (historical record):** `research-prompt-ibmmq-jms-micronaut.md` (locked brief) and `research-output/phase-a-fact-sheet.md` (already carries Java-25 evidence).
- **Verification (this turn):** re-grep returns zero stale `Java 21`/`release=21`/"pinning nos synchronized" outside intentional supersession-explainers; `pom.xml` value confirmed `25`.
- **Residual:** none required; Phase D's HTML will inherit the corrected guide automatically — re-run the TASK_5 re-grep as a Phase E spot-check.


## Deferred work — TASK_2: deep-research + implement activation & recovery of COA/COD MQMD fields

> **Status:** specified, not executed. This is an instruction set for a future session. Treat every `JMS_IBM_MQMD_*` / `WMQ_MQMD_*` name below as **bytecode-verified** (extracted this session via `javap -p -constants` against the authentic `com.ibm.mq:com.ibm.mq.allclient:9.4.5.0` jar at `~/.m2/repository/com/ibm/mq/com.ibm.mq.allclient/9.4.5.0/com.ibm.mq.allclient-9.4.5.0.jar`, sha1 `26c8f5cd163847990d270acf2f4f0a7f773c3bbd`). Everything *semantic* (which descriptor a value comes from, what the producer must set, GMT conversion correctness) is the deferred research.

### Goal

Run a **new deep-research pass** with the same rigor as the Phase A work (`research-output/phase-a-fact-sheet.md`): every claim bytecode-verified against the authentic `allclient:9.4.5.0` jar, cross-checked against IBM docs, fully sourced, results persisted to `research-output/` per the repo's reference-doc rule (`CLAUDE.md`). Then wire the findings into the implementation and tests so that, for **both** a COA report and a COD report, the consumer recovers and asserts six values: application identity data, accounting token, correlation id (byte[]), message id (byte[]), put-timestamp (as `LocalDateTime`), and a derived report-type char (`A`/`D`).

### THE ORGANIZING QUESTION (resolve this first — it drives everything else)

For each of the six values, the research must classify the source as one of:

- **(R) Report's OWN descriptor** — the MQMD of the report message itself, readable directly from the received JMS `Message` via canonical/`MQMD` JMS properties. No body needed.
- **(O) ORIGINAL descriptor, embedded in the report body** — only present if the producer requested `MQRO_*_WITH_FULL_DATA` (or `_WITH_DATA`), which makes the QMgr copy (a slice of) the original MQMD + message data into the report's body. A plain `MQRO_COA` / `MQRO_COD` report has an **empty body** and carries only the report's own descriptor.

This matters because the current producer (`BusinessMessageProducer.send()`, lines 76–78) sets plain `MQConstants.MQRO_COA` / `MQConstants.MQRO_COD`. The report that arrives therefore exposes only its OWN MQMD. Its own `MsgId` is freshly generated (default `MQRO_NEW_MSG_ID=0`); its `CorrelId` = the original `MsgId` (default `MQRO_COPY_MSG_ID_TO_CORREL_ID=0`); and its `PutDate`/`PutTime` are the **report's** put time, not the original's. So **fields that need the *original's* ApplIdentityData / AccountingToken / original put-time will require switching the producer to `MQRO_COA_WITH_FULL_DATA` (=1792, 0x700) and `MQRO_COD_WITH_FULL_DATA` (=14336, 0x3800)** — both already in the fact sheet (`research-output/phase-a-fact-sheet.md` lines 83, 86). The research must confirm, per field, whether (R) suffices or (O) `WITH_FULL_DATA` is mandatory, and the consequent edit to `BusinessMessageProducer`.

### The activation gate (canonical vs MQMD-read properties) — bytecode-verified mechanism

There are **two** property families on a received report:

1. **Canonical `JMS_IBM_*`** — ALWAYS populated for reports, no enablement flag. Already used: `WMQConstants.JMS_IBM_FEEDBACK` in `ReportMessageConsumer.handleReport()` (line 84). The verified sibling timestamp keys (in `com.ibm.msg.client.jms.JmsConstants`, inherited by `WMQConstants`) are `JMS_IBM_PUTDATE` (value `"JMS_IBM_PutDate"`) and `JMS_IBM_PUTTIME` (value `"JMS_IBM_PutTime"`).
2. **`JMS_IBM_MQMD_*`** — populated **only** when MQMD read is enabled on the consume destination. Bytecode-verified field → property-key-string (all in `JmsConstants`):
   - `JMS_IBM_MQMD_APPLIDENTITYDATA` → `"JMS_IBM_MQMD_ApplIdentityData"`
   - `JMS_IBM_MQMD_ACCOUNTINGTOKEN` → `"JMS_IBM_MQMD_AccountingToken"`
   - `JMS_IBM_MQMD_MSGID` → `"JMS_IBM_MQMD_MsgId"`
   - `JMS_IBM_MQMD_CORRELID` → `"JMS_IBM_MQMD_CorrelId"`
   - `JMS_IBM_MQMD_PUTDATE` → `"JMS_IBM_MQMD_PutDate"`
   - `JMS_IBM_MQMD_PUTTIME` → `"JMS_IBM_MQMD_PutTime"`
   - `JMS_IBM_MQMD_MSGTYPE` → `"JMS_IBM_MQMD_MsgType"`
   - `JMS_IBM_MQMD_FEEDBACK` → `"JMS_IBM_MQMD_Feedback"`

**Enablement flags** (bytecode-verified in `com.ibm.msg.client.wmq.common.CommonConstants`, inherited by `WMQConstants`):
   - `WMQ_MQMD_READ_ENABLED` → property key `"mdReadEnabled"` (boolean)
   - `WMQ_MQMD_WRITE_ENABLED` → property key `"mdWriteEnabled"` (boolean)
   - `WMQ_MQMD_MESSAGE_CONTEXT` → property key `"mdMessageContext"` (int; values `WMQ_MDCTX_DEFAULT=0`, `WMQ_MDCTX_SET_IDENTITY_CONTEXT=1`, `WMQ_MDCTX_SET_ALL_CONTEXT=2`)

**CODE-PATH GOTCHA (verified, not deferred):** `WMQ_MQMD_READ_ENABLED` is a **destination-scoped** property — there is NO `setMQMDReadEnabled` on `com.ibm.mq.jms.MQConnectionFactory`. The setter lives on `com.ibm.mq.jms.MQDestination`: `setMQMDReadEnabled(boolean)` / `getMQMDReadEnabled()` / `setMQMDWriteEnabled(boolean)` / `setMQMDMessageContext(int)` / `setMessageBodyStyle(int)`. The current report consumer obtains its queue via `context.createQueue("queue:///" + props.getReportQueue())` (`ReportMessageConsumer.receiveOneReport()`, line 65), which yields a **generic `javax.jms.Queue` that does not expose these setters**. The research must resolve and the impl must apply ONE of:
   - **(a) URI property on the `queue:///` form** — e.g. `"queue:///DEV.QUEUE.2?mdReadEnabled=true"` (research must confirm exact URI param name/casing against bytecode/IBM docs);
   - **(b) cast to `com.ibm.mq.jms.MQDestination`** and call `setMQMDReadEnabled(true)` before creating the consumer.
   Document which path works on the `queue:///` URI form and whether the cast is safe under the `JmsPoolConnectionFactory` wrapper.

### Per-field specification (research must fill the verdict column R vs O)

| # | Domain value | Java type | Activation requirement | Recovery + conversion path | Source axis to resolve |
|---|---|---|---|---|---|
| 1 | application identity data | `String` | `WMQ_MQMD_READ_ENABLED=true` on report destination | `report.getStringProperty(WMQConstants.JMS_IBM_MQMD_APPLIDENTITYDATA)` | **Likely (O):** the *report's own* ApplIdentityData is set by the QMgr, not the app. To recover the **original** app's identity data, research whether `WITH_FULL_DATA` + embedded-original parsing is required, or whether MQ propagates identity context into the report descriptor. Tie to `WMQ_MQMD_MESSAGE_CONTEXT` on the producer. |
| 2 | accounting token | `byte[]` (24 bytes, `MQ_ACCOUNTING_TOKEN_LENGTH`) | `WMQ_MQMD_READ_ENABLED=true` | `report.getBytesProperty? ` — research the exact getter: JMS spec has no `getBytesProperty`; confirm whether `JMS_IBM_MQMD_AccountingToken` is exposed as a `byte[]` object property (`getObjectProperty` → `byte[]`) or hex `String`. | **Resolve (R)-vs-(O):** is the report's own AccountingToken the QMgr's or copied from the original? Accounting token is the cross-instance billing/audit key (see scale note) — getting the **original's** token is the point, so likely (O) `WITH_FULL_DATA`. |
| 3 | correlation id | `byte[]` | none for the *value* (canonical), but byte[] form needs care | `report.getJMSCorrelationIDAsBytes()` (verified on `com.ibm.jms.JMSMessage`). By default `MQRO_COPY_MSG_ID_TO_CORREL_ID` ⇒ this byte[] == the **original message's `MsgId`** byte[]. | **(R)** — report's own CorrelId. State explicitly this is the *original MsgId*, not a separate correlation id. |
| 4 | message id | `byte[]` | `WMQ_MQMD_READ_ENABLED=true` for the report's OWN MsgId byte[] | **There is NO `getJMSMessageIDAsBytes()`** — verified: `getJMSMessageID()` returns only a `String` (`"ID:..."` hex), and the provider exposes `getJMSCorrelationIDAsBytes()` but no MsgId byte[] sibling. Recover the report's own MsgId bytes via `JMS_IBM_MQMD_MSGID` (`"JMS_IBM_MQMD_MsgId"`). To recover the **original's** MsgId, prefer field #3 (CorrelId == original MsgId) — that is the cheaper, always-available path. | **(R)** for the report's own MsgId; **#3** for the original's. Clarify which the impl stores. |
| 5 | put timestamp → `LocalDateTime` | `LocalDateTime` | canonical needs none; `JMS_IBM_MQMD_*` form needs read-enable | **Whose time?** `report.getJMSTimestamp()` and the report's own `PutDate`/`PutTime` are the **report-generation** time, NOT the original's put time. To get the **original's** put time you need (O) `WITH_FULL_DATA` + parse the embedded original MQMD. Source fields: `PutDate` = `String` `YYYYMMDD`; `PutTime` = `String` `HHMMSSTH` (last two digits = hundredths of a second). Both are **GMT/UTC**. Conversion: parse with explicit UTC zone — `LocalDateTime.parse(date+time, formatter)` interpreted via `ZoneOffset.UTC` (or build `Instant` then `LocalDateTime.ofInstant(instant, ZoneOffset.UTC)`); **never** rely on the JVM default zone. Read via canonical `JMS_IBM_PUTDATE`/`JMS_IBM_PUTTIME` or MQMD `JMS_IBM_MQMD_PUTDATE`/`JMS_IBM_MQMD_PUTTIME`. | **(R)** for report put-time; **(O)** if the original's put-time is required. Research must state which the audit use-case needs. |
| 6 | report type char (`A`/`D`) | `char` | none (derived) | **Derived, not native** — research must confirm MQ exposes no single-char field. Derive from Feedback: `259 (MQFB_COA) ⇒ 'A'`, `260 (MQFB_COD) ⇒ 'D'`. Guard with `MQMD MsgType == MQMT_REPORT` (verified `CMQC.MQMT_REPORT = 4`) read via `report.getIntProperty(WMQConstants.JMS_IBM_MQMD_MSGTYPE)` when read-enabled, or just trust the report queue + feedback code. `ReportType` enum already encodes COA/COD — make this a trivial projection (a `char toDomainChar()` method on `ReportType`). | n/a — pure derivation; do not over-engineer. |

### Producer-side propagation (what must be set so values reach the report)

The research must clarify, with sources, what the generated report inherits vs not:
- **Inherits from original descriptor:** persistence (refuted-brief fact — see `phase-a-fact-sheet.md` line 123), and `CorrelId` = original `MsgId` (default propagation).
- **NOT in a plain report:** the original's ApplIdentityData / AccountingToken / put-time / message data — these require the producer to request `MQRO_COA_WITH_FULL_DATA` / `MQRO_COD_WITH_FULL_DATA` so the original MQMD+data are embedded in the report body. **Concrete edit:** in `BusinessMessageProducer.send()` (lines 76–78) switch the two `setIntProperty` calls from `MQConstants.MQRO_COA`/`MQRO_COD` to the `_WITH_FULL_DATA` variants **iff** the research confirms (O) for any required field.
- **Identity context:** to make the *original's* ApplIdentityData meaningful, the producer may need `WMQ_MQMD_WRITE_ENABLED=true` + `WMQ_MQMD_MESSAGE_CONTEXT=WMQ_MDCTX_SET_IDENTITY_CONTEXT(1)` (or `SET_ALL_CONTEXT=2`) and to set `JMS_IBM_MQMD_ApplIdentityData` on the outbound message — research whether the QMgr requires `+setid`/`+setall` authority for this (relates to the existing 2035 report-PUT gotcha in `CLAUDE.md`).

### Implementation wiring (name the concrete files)

- **`ibmmq-jms-guide/src/main/java/com/example/ibmmq/consumer/ReportMessageConsumer.java`** — in `handleReport(Message)`, after reading `JMS_IBM_FEEDBACK`, extract the six values using the verified property keys / accessors above; populate the extended `DeliveryEvent`. Keep extraction **non-blocking and allocation-light** (167 msg/s — see scale note). In `receiveOneReport(long)`, enable MQMD read on the report destination via the resolved path (URI `mdReadEnabled=true` or `MQDestination.setMQMDReadEnabled(true)`).
- **`ibmmq-jms-guide/src/main/java/com/example/ibmmq/model/DeliveryEvent.java`** — extend the record with: `String applIdentityData`, `byte[] accountingToken`, `byte[] correlationIdBytes`, `byte[] messageIdBytes`, `LocalDateTime putTimestampUtc`, `char reportTypeChar`. For audit/log safety, expose `byte[]` fields as hex or Base64 (a `String accountingTokenHex()` accessor) — `byte[]` in a record breaks value-equality and logs as garbage.
- **`ibmmq-jms-guide/src/main/java/com/example/ibmmq/model/ReportType.java`** — add `char toDomainChar()` returning `'A'` for `COA`, `'D'` for `COD` (and a sentinel for others). Field #6 is this projection.
- **`ibmmq-jms-guide/src/main/java/com/example/ibmmq/producer/BusinessMessageProducer.java`** — conditionally use `MQRO_COA_WITH_FULL_DATA` / `MQRO_COD_WITH_FULL_DATA` per the research verdict; optionally set write-enable + message-context + `JMS_IBM_MQMD_ApplIdentityData` if propagating original identity.
- **`ibmmq-jms-guide/src/main/java/com/example/ibmmq/config/MqProperties.java`** + **`MqConnectionFactoryFactory.java`** — add a property (e.g. `mqmd-read-enabled`) and, if the destination must be built outside the consumer, surface the read-enable there. Note: read-enable is destination-scoped, so the most robust home is where the report `Queue` is created (the consumer), not the CF.
- New small util (e.g. `ibmmq-jms-guide/src/main/java/com/example/ibmmq/report/MqmdTimestamps.java`) for the GMT `PutDate`+`PutTime` → `LocalDateTime` conversion, unit-tested in isolation.

### Tests (assert all six on BOTH COA and COD)

- **`ibmmq-jms-guide/src/test/java/com/example/ibmmq/integration/CoaCodEndToEndIT.java`** — extend the existing IT (connects as `admin` for context authority — keep that). After producing with the `_WITH_FULL_DATA` variants and enabling `mdReadEnabled` on `DEV.QUEUE.2`, when each report arrives (COA feedback 259, COD feedback 260) assert: `applIdentityData` recovered, `accountingToken` non-empty (24 bytes), `correlationIdBytes` == original `MsgId` bytes, `messageIdBytes` recovered, `putTimestampUtc` non-null and plausibly recent, and `reportTypeChar` == `'A'` for the COA / `'D'` for the COD. The IT already proves `CorrelId == MessageId` (lines 142–143) — extend, don't replace.
- New unit test **`ibmmq-jms-guide/src/test/java/com/example/ibmmq/report/MqmdTimestampsTest.java`** — table-driven GMT conversion cases (e.g. `PutDate="20260531"`, `PutTime="13300050"` → `2026-05-31T13:30:00.500` UTC), including a midnight/zone-boundary case to prove no JVM-default-zone leakage.
- Extend **`ibmmq-jms-guide/src/test/java/com/example/ibmmq/report/ReportFeedbackRouterTest.java`** (and a `ReportTypeTest` if added) to assert `toDomainChar()` mappings.

### Persistence (reference-doc rule — mandatory)

- Write a new validated fact sheet **`research-output/phase-f-mqmd-field-recovery.md`**, fully sourced (bytecode evidence + IBM doc URLs), structured like `phase-a-fact-sheet.md`: the per-field R-vs-O verdict table, the verified property-key strings (already captured above), the activation flags, the GMT conversion rule, and the producer-side `WITH_FULL_DATA` consequence. Carry forward the verified names so no future session re-runs `javap`.
- Update **`CLAUDE.md`** "Validated facts" to add a one-line pointer to `research-output/phase-f-mqmd-field-recovery.md` (the canonical home for MQMD field recovery), next to the existing feedback-codes line.

### Cross-cutting k8s / ~10k-rpm (~167 msg/s) mandate — concrete, not a tagline

- **AccountingToken (#2)** is the cross-pod billing/audit correlation key: in a Kubernetes deployment where any of N consumer replicas may receive a given report, the original's AccountingToken lets reconciliation tie a COD back to the exact producing instance/unit-of-work independent of which pod processed it. This is the strongest argument for the (O) `WITH_FULL_DATA` path for fields 1/2.
- **PutDate/PutTime (#5)** in UTC gives end-to-end delivery latency (original put → COD generation) measured against the delivery SLO. Because PutDate/PutTime are GMT, latency math is replica-clock-independent (no per-pod timezone skew) — but only if conversion never touches the JVM default zone.
- At ~167 msg/s steady state the per-report extraction in `ReportMessageConsumer.handleReport()` must stay non-blocking and low-allocation; reusing the `MqmdTimestamps` parser and avoiding per-message reflective property lookups keeps the report path from becoming the bottleneck. `_WITH_FULL_DATA` enlarges report bodies — note the resulting bandwidth/queue-depth cost on `DEV.QUEUE.2` (report queue) at scale and recommend `_WITH_DATA` (truncated) vs `_WITH_FULL_DATA` (full) as a tuning lever the research should size.

### Open mechanism questions for the research to close

1. Exact getter for `byte[]` MQMD properties (`JMS_IBM_MQMD_AccountingToken`, `JMS_IBM_MQMD_MsgId`): `getObjectProperty` returning `byte[]` vs hex `String`?
2. Exact `queue:///` URI parameter name to enable MQMD read (`mdReadEnabled=true`?) vs requiring an `MQDestination` cast.
3. Per field 1/2/5: does a plain report ever carry the **original's** values, or is `WITH_FULL_DATA` strictly required (the R-vs-O verdict)?
4. Whether setting identity context on the producer needs `+setid`/`+setall` (relates to the existing 2035 report-PUT authority gotcha).

---

## Deferred work - TASK_3: expand integration-test scenarios (edge cases, Virtual Threads, field-recovery evidence, load/volumetry)

The current integration suite has exactly one IT — `ibmmq-jms-guide/src/test/java/com/example/ibmmq/integration/CoaCodEndToEndIT.java` — which proves the happy path (produce persistent message with `JMS_IBM_REPORT_COA`/`JMS_IBM_REPORT_COD`, consume+commit, assert COA `MQFB_COA=259` and COD `MQFB_COD=260` both arrive on `DEV.QUEUE.2` with `CorrelationId == MessageId`). That is necessary but nowhere near sufficient for a system that must run on Kubernetes at ~10,000 requests/min (~167 msg/s) across competing-consumer replicas. This task specifies the full scenario matrix the suite must grow into.

This work is **deferred** — the section below is the build sheet for a future session, not a change log. Implement scenarios as you go; do not assume any are already coded.

### Where this gets documented (doc-currency rule)

Per the repo reference-documentation rule (CLAUDE.md), the scenario catalog is a durable artifact and must live in the repo:

- **Create `docs/testing-scenarios.md`** as the canonical scenario catalog. Every scenario below is one entry, written with the **exact mandatory template**: **Scenario** / **Impact** / **Community reports (with links/sources)** / **Application behavior (explained)** / **Solution (clear WHY it happens and HOW the fix actually resolves it)**. The catalog is the source of truth; the test files are the executable proof of each entry.
- **Cross-link from the guide**: add a pointer from `docs/guia-ibmmq-jms-micronaut.md` §5.1 ("Estratégia de testes híbrida") and §5.2 ("Gotchas reais") to `docs/testing-scenarios.md`, and extend §5.7 (Virtual Threads) once Group B is measured. Do not duplicate the catalog into the guide — link to it.
- **English** for both files (durable artifacts), even though the guide prose is pt-BR.

### Test layout, tagging, and keeping `mvn verify` fast

- Edge-case + field-recovery + VT ITs go under the existing package `com.example.ibmmq.integration` (`ibmmq-jms-guide/src/test/java/com/example/ibmmq/integration/`). Name them `*IT` so **failsafe** (not surefire) runs them on `mvn verify`, matching the existing convention. Reuse the manual `@BeforeAll`/`@AfterAll` `MQContainer` lifecycle from `CoaCodEndToEndIT` (no `junit-jupiter` Testcontainers module is on the classpath).
- Load/volumetry ITs go under a new package `com.example.ibmmq.load` and are **JUnit-`@Tag("load")`-tagged**. Wire `ibmmq-jms-guide/pom.xml` so the default failsafe execution does `<excludedGroups>load</excludedGroups>`, and add a Maven profile (e.g. `-Pload`) whose failsafe execution sets `<groups>load</groups>`. Rationale: a sustained ~167 msg/s run takes minutes and needs more container resources — it must not be in the default `mvn verify` gate, but must be one command away.
- All forked JVMs keep `--enable-native-access=ALL-UNNAMED` in `argLine` (already wired). When running any IT through a Claude Bash tool, disable the sandbox so the forked JVM reaches `/var/run/docker.sock`.

---

### Group A — Known / community edge cases

Each of these is an entry in `docs/testing-scenarios.md` and (where it needs a broker) a `*IT` under `…/integration/`. Several need MQSC that does not yet exist in `ibmmq-jms-guide/mqsc/20-queues.mqsc` — add it there and reference it from the test Javadoc.

**A1 — Report-PUT auth failure → report silently to DLQ (the negative of the happy path).** New `ReportAuthDlqIT`.
- **Scenario**: connect as the low-priv **`app`** user (channel `DEV.APP.SVRCONN`), produce a COA+COD message, consume+commit — exactly like `CoaCodEndToEndIT` but as `app` instead of `admin`.
- **Impact**: the report queue stays **empty**; operators conclude "COA/COD is broken" while the QMgr has actually silently DLQ'd every report. On k8s this is a per-replica silent data-loss of delivery evidence.
- **Community reports**: IBM technote *Return code 2035 MQRC_NOT_AUTHORIZED* (https://www.ibm.com/docs/en/ibm-mq/9.4?topic=problems-return-code-2035-mqrc-not-authorized); IBM Community discussion on COA received but COD landing in the remote DLQ (https://community.ibm.com/community/user/discussion/2035-mqrc-not-authorized); `ibm-messaging/mq-container` Discussion #563 on dev-image 2035 (https://github.com/ibm-messaging/mq-container/discussions/563).
- **Application behavior (explained)**: to generate+deliver a report the QMgr does a **PUT-with-context** onto the `ReplyToQ` (it must stamp the report's identity/origin context). That needs **context authority (`+setall`)**. The dev image's `app` principal lacks it → the report PUT fails `MQRC_NOT_AUTHORIZED (2035)` → the report is routed to `DEV.DEAD.LETTER.QUEUE`, not to `DEV.QUEUE.2`.
- **Solution (WHY + HOW)**: **assert** the report queue is empty after the flow and (if browsable as the connected principal — see open questions) that the DLQ holds a message with reason 2035. The production fix is to grant the app principal `SET AUTHREC PROFILE('APP.REPORT.QUEUE') OBJTYPE(QUEUE) GROUP('appgrp') AUTHADD(PUT, SETALL)` + `REFRESH SECURITY TYPE(AUTHSERV)`. This works because `+setall` is precisely the authority the context-PUT checks; granting it to the *specific* principal (not running the app as `admin`) closes the report path without opening a remote-admin hole. This is the k8s reality: every replica's service account/principal needs `PUT+SETALL` on the report queue or that replica silently loses evidence.

**A2 — Report persistence inheritance.** New `ReportPersistenceInheritanceIT`.
- **Scenario**: produce one **persistent** original and one **non-persistent** original, each with COA+COD; read the reports and assert each report's `JMSDeliveryMode` equals its original's.
- **Impact**: sizing/durability planning. The brief's "reports are non-persistent by default" assumption was **refuted** — a persistent original yields persistent reports, which consume log/disk and survive QMgr restart. At 167 msg/s with COA+COD that is ~334 persistent report PUTs/s of extra logged I/O if originals are persistent.
- **Community reports**: IBM MQ docs *Report options and message flags* / *Report* field (https://www.ibm.com/docs/en/ibm-mq/9.4?topic=fields-report-mqlong); validated locally in `research-output/coa-cod-validation-findings.md`.
- **Application behavior (explained)**: the QMgr copies the original's persistence onto the report MQMD unless `MQRO_PASS_DISCARD_AND_EXPIRY`/explicit options say otherwise; default behavior inherits.
- **Solution (WHY + HOW)**: the test pins the inheritance contract so a future change to producer persistence does not silently change report durability/cost. If non-persistent reports are acceptable for capacity, document setting the report request explicitly rather than relying on inheritance.

**A3 — COA timing under syncpoint: only after producer commit.** New `SyncpointReportTimingIT` (COA half).
- **Scenario**: producer uses `JMSContext.SESSION_TRANSACTED`; send COA-enabled message but **do not commit**; assert no COA appears; then `commit()` and assert COA arrives.
- **Impact**: correlation-store timeout tuning. If you arm a "COA not seen → alert" timer at send time but the producer batches commits, you false-alarm.
- **Community reports**: IBM MQ docs *Confirm on arrival (COA) report* and *Reports and segmented/transacted messages* (https://www.ibm.com/docs/en/ibm-mq/9.4?topic=options-report); narkive thread *REG: COA and COD messages* (https://ibm.software.websphere.mq.narkive.com/fkNiS3zA/reg-coa-and-cod-messages).
- **Application behavior (explained)**: a message PUT under syncpoint is invisible to consumers (and the COA is not generated) until the producing UOW commits. Backed-out PUT → no COA at all.
- **Solution (WHY + HOW)**: start the COA-expectation clock at **commit**, not at `send()`. The test encodes that timing so the correlation store's pending-window logic is calibrated against real broker behavior.

**A4 — COD only after destructive consume + commit; backout ⇒ no COD.** New `SyncpointReportTimingIT` (COD half) or `PoisonMessageBackoutIT`.
- **Scenario**: consumer in `SESSION_TRANSACTED`; receive the COD-enabled message but `rollback()` → assert no COD; receive again and `commit()` → assert COD now arrives.
- **Impact**: COD is your "delivered for real" signal. Treating receive-without-commit as delivery double-counts and, under poison-message backout, you would otherwise wrongly mark a never-processed message as delivered.
- **Community reports**: IBM MQ docs *Confirm on delivery (COD) report* (https://www.ibm.com/docs/en/ibm-mq/9.4?topic=options-report); same narkive thread as A3.
- **Application behavior (explained)**: COD fires on the **destructive GET that commits**. A rolled-back GET returns the message to the queue and generates no COD.
- **Solution (WHY + HOW)**: only mark `withCodReceived()` on an actual COD report, never on receive. The test proves backout produces zero COD, so the correlation store never falsely confirms.

**A5 — Expired message ⇒ `MQFB_EXPIRATION (258)`.** New `ExpiredMessageReportIT`.
- **Scenario**: produce a message with a short `JMSExpiration` and `JMS_IBM_REPORT_EXPIRATION = MQRO_EXPIRATION`, never consume it; assert a report with feedback `MQFB_EXPIRATION=258` arrives and routes to `ReportType.EXPIRATION` via `ReportFeedbackRouter`.
- **Impact**: time-sensitive orders that age out must be observable, not silently dropped.
- **Community reports**: IBM MQ docs *Expiration reports* and the `MQRO_EXPIRATION_WITH_DATA`/`_WITH_FULL_DATA` options (https://www.ibm.com/docs/en/ibm-mq/9.4?topic=options-report); constant `MQFB_EXPIRATION=258` bytecode-verified in `research-output/phase-a-fact-sheet.md`.
- **Application behavior (explained)**: when a message's expiry elapses before consumption, the QMgr discards it and, if expiration reports were requested, emits a feedback-258 report.
- **Solution (WHY + HOW)**: requesting `MQRO_EXPIRATION` turns a silent drop into a routable `DeliveryEvent`. The test asserts `ReportFeedbackRouter.classify(258) == EXPIRATION`, closing the gap between "message gone" and "we know it expired."

**A6 — Poison message ⇒ `BOTHRESH`/`BOQNAME`/DLQ + idempotency.** New `PoisonMessageBackoutIT`.
- **Scenario**: define `DEV.QUEUE.1` (or a dedicated queue) with `BOTHRESH(3)` and `BOQNAME('APP.BACKOUT.QUEUE')` in `mqsc/20-queues.mqsc`; deliver a message that always fails processing (rollback each time); assert after the threshold the message lands on the backout queue and processing was attempted at most `BOTHRESH` times; assert downstream side effects executed **exactly once** (idempotency).
- **Impact**: without this a single bad message in an ordered queue blocks the queue, pins a consumer at 100% CPU in a rollback loop, and (with non-idempotent side effects) duplicates downstream writes once per retry. At 167 msg/s a stuck consumer replica is a real outage.
- **Community reports**: IBM MQ docs *BackoutThreshold (BOTHRESH) and BackoutRequeueQName (BOQNAME)* (https://www.ibm.com/docs/en/ibm-mq/9.4?topic=queues-backout-threshold-backout-requeue-name); guide §5.3.
- **Application behavior (explained)**: `JMSXDeliveryCount`/MQMD `BackoutCount` increments per rollback; once it exceeds `BOTHRESH` the QMgr (driven by the application's reject logic) moves the message to `BOQNAME` instead of redelivering forever.
- **Solution (WHY + HOW)**: `BOTHRESH`+`BOQNAME` bounds retries and isolates the poison message; idempotent processing (dedup on `messageId`/`businessKey`) ensures redeliveries before isolation do not double-apply effects. The test proves both the retry cap and the single-effect guarantee.

**A7 — `MQRO_*_WITH_DATA` / `_WITH_FULL_DATA` payload duplication + PII exposure (sizing).** New `ReportWithDataPayloadIT`.
- **Scenario**: request COA/COD **with data** using the bytecode-verified constants `MQRO_COA_WITH_DATA=768`, `MQRO_COA_WITH_FULL_DATA=1792`, `MQRO_COD_WITH_DATA=6144`, `MQRO_COD_WITH_FULL_DATA=14336` (all four exist for COA/COD per `research-output/phase-a-fact-sheet.md`); assert the report body carries the echoed original payload (first 100 bytes for `_WITH_DATA`, full body for `_WITH_FULL_DATA`).
- **Impact**: capacity + security. With data variants the **report queue now stores a copy of every business payload** — at 167 msg/s × (COA+COD) that doubles-to-quadruples report-queue storage, and any PII in the body is now duplicated onto a second queue with possibly different access controls.
- **Community reports**: IBM MQ docs *Report* field option values (`MQRO_*_WITH_DATA`) (https://www.ibm.com/docs/en/ibm-mq/9.4?topic=fields-report-mqlong); constants verified in `research-output/coa-cod-validation-findings.md` and `phase-a-fact-sheet.md`.
- **Application behavior (explained)**: the data variants instruct the QMgr to copy the original message body (truncated or full) into the report message, so a consumer can correlate without keeping its own copy.
- **Solution (WHY + HOW)**: prefer plain `MQRO_COA`/`MQRO_COD` (the project default) so reports stay tiny and carry no payload; only use the data variants when the report consumer genuinely cannot look up the original, and then size the report queue and lock down its access for the duplicated PII. The test documents the storage/PII cost so the decision is informed.

**A8 — Report queue full.** New `ReportQueueFullIT`.
- **Scenario**: set the report queue `MAXDEPTH` low (e.g. `ALTER QLOCAL('DEV.QUEUE.2') MAXDEPTH(5)`) in MQSC, drive more reports than that without draining; assert the QMgr's report-PUT behavior (report itself fails to be put → its own disposition follows `MQRO_*` discard/DLQ semantics).
- **Impact**: a backed-up report consumer (or a k8s report-consumer deployment scaled to zero) makes the report queue fill; new reports then fail their PUT and you lose delivery evidence — the failure cascades from the consumer side, not the producer side.
- **Community reports**: IBM MQ docs *MQRC_Q_FULL (2053)* and *Report message disposition when the destination is unavailable* (https://www.ibm.com/docs/en/ibm-mq/9.4?topic=codes-2053-0805-rc2053-mqrc-q-full).
- **Application behavior (explained)**: a full destination yields `MQRC_Q_FULL`; for a report message that PUT failure is handled per the report's own disposition options (discard vs. DLQ).
- **Solution (WHY + HOW)**: size `MAXDEPTH`, alarm on depth, and ensure the report consumer deployment cannot be scaled to zero while producers run. The test makes the full-queue failure mode visible instead of a mysterious "missing reports" report.

**A9 — Reconnect mid-flow.** New `ReconnectMidFlowIT`.
- **Scenario**: enable `WMQ_CLIENT_RECONNECT_OPTIONS = WMQ_CLIENT_RECONNECT`; mid-flow restart/pause the `MQContainer` (chaos step) and assert the client transparently resumes and no COA/COD is lost for in-flight correlated messages.
- **Impact**: in k8s, QMgr rollouts, node drains, and network blips are routine; correlation must survive them.
- **Community reports**: IBM technote *Using WebSphere MQ automatic client reconnection with the classes for JMS* (https://www.ibm.com/support/pages/using-websphere-mq-automatic-client-reconnection-websphere-mq-classes-jms); guide §5.4.
- **Application behavior (explained)**: auto-reconnect re-establishes the connection/handles via `CONNECTION_NAME_LIST`/CCDT within `WMQ_CLIENT_RECONNECT_TIMEOUT`; persistent messages and their reports survive a QMgr restart.
- **Solution (WHY + HOW)**: persistent delivery + auto-reconnect + a durable correlation store means an in-flight message's reports arrive after reconnect and still match by `CorrelationId == MessageId`. The chaos test is the only reliable way to validate resumption in your setup.

**A10 — pooled-jms × auto-reconnect sharp edge (P10): validate pool invalidation/renewal.** New `PooledReconnectInvalidationIT`.
- **Scenario**: front the MQ CF with `org.messaginghub.pooled.jms.JmsPoolConnectionFactory` (pooled-jms 2.0.9); kill the broker so a pooled connection goes stale, restart it, then borrow from the pool and assert you get a **live** connection (the pool invalidated/renewed the dead one) — not a silently-dead handle.
- **Impact**: this is the most insidious k8s failure: a pooled connection that "auto-reconnected" or went stale is handed back to a worker that then silently fails to send/receive, dropping correlation without an obvious exception.
- **Community reports**: Red Hat solution *Generic JMS connection pool does not reconnect on failure* — "the pooled connection simply becomes stale, and will never be reconnected" (https://access.redhat.com/solutions/1503743); IBM technote *Removal of internal Connection Pooling for WebSphere MQ classes for JMS* (https://www.ibm.com/support/pages/removal-internal-connection-pooling-websphere-mq-classes-jms); `messaginghub/pooled-jms` repo (https://github.com/messaginghub/pooled-jms); brief premise P10.
- **Application behavior (explained)**: a generic JMS pool has no transport-layer failure detection; a dead connection can be returned from the pool unless an exception listener / connection check invalidates it. pooled-jms relies on `ExceptionListener`-driven invalidation plus `connectionCheckInterval`-style validation.
- **Solution (WHY + HOW)**: configure pooled-jms to invalidate on `JMSException` (exception-listener) and validate-on-borrow; the test forces a stale connection and proves the next borrow is healthy. This works because invalidation removes the dead `Connection` from the pool so the next borrow creates a fresh one against the reconnected QMgr — preventing the "silently dead handle" path.

---

### Group B — Virtual Threads: right vs wrong usage, MEASURED, tied to the Java 25 move (TASK_5)

New `VirtualThreadJmsUsageIT` (integration, broker-backed). This group **depends on TASK_5** (the project standardizes on Amazon Corretto **25**; `pom.xml` already sets `maven.compiler.release=25`). The measurement and its interpretation only make sense on JDK 24+.

- **Scenario (WRONG pattern)**: spawn N virtual threads that **share a single `JMSContext`/`Session`** and concurrently produce/consume. Observe the symptom.
- **Scenario (RIGHT pattern)**: either keep JMS I/O on a **platform-thread pool** with `JmsPoolConnectionFactory` (one `JMSContext` per thread), or give **one `JMSContext` per task** drawn from the pool; use virtual threads only for the business-logic fan-out. Measure both.
- **Impact**: `Session` and `JMSContext` are **not thread-safe** (JMS spec). Sharing one across virtual (or platform) threads corrupts internal state → `javax.jms.IllegalStateException`, messages "disappearing"/duplicating, and throughput *worse* than platform threads. At 167 msg/s the wrong pattern is an outage generator, not a micro-optimization.
- **Community reports (with links/sources)**: JMS 2.0 spec thread-safety of `JMSContext`/`Session`; guide §5.7; JEP 491 *Synchronize Virtual Threads without Pinning* (https://openjdk.org/jeps/491); JEP 444 *Virtual Threads* (https://openjdk.org/jeps/444); analysis "Virtual Threads after JEP 491: the bottleneck moved" (https://tiarebalbi.com/en/blog/virtual-threads-after-jep-491-bottleneck-moved); "Java 24 — Thread pinning revisited" (https://mikemybytes.com/2025/04/09/java24-thread-pinning-revisited/).
- **Application behavior (explained)**: two failure axes combine in the wrong pattern — (1) state corruption from the shared non-thread-safe `JMSContext`, and (2) historically, **pinning**: when a virtual thread blocked inside one of the MQ client's internal `synchronized` blocks it pinned its carrier, killing VT scalability. **Java-25 knock-on (TASK_5 / JEP 491, finalized in JDK 24)**: monitors now track ownership by virtual-thread identity, so blocking inside a `synchronized` **no longer pins** the carrier. The classic guide §5.7 warning about pinning on the MQ client's `synchronized` blocks is therefore **largely resolved on Java 25**. Residual pinning remains **only on native/JNI (and FFM) frames** — and the MQ client does load native libraries via `System.loadLibrary` (guide §5.2c), so a VT blocking inside an MQ native call can still pin. Frame it precisely: **synchronized-block pinning is gone; native-frame pinning may remain.**
- **Solution (WHY + HOW)**: the test must (a) **measure throughput and latency (p50/p95/p99)** of right vs wrong patterns under a fixed offered load and assert the right pattern is both correct and at least as fast; (b) **demonstrate the pre/post-JEP-491 pinning boundary** — run with `-Djdk.tracePinnedThreads=full` (or JFR `jdk.VirtualThreadPinned` events) and assert pinning events occur, if at all, only on native frames, not on the MQ client's `synchronized` blocks. The fix is structural: never share a `JMSContext` across threads, keep blocking JMS I/O on pooled platform threads (or one context per VT task), and reserve virtual threads for the I/O-bound business fan-out — the layer where they actually scale at 167 msg/s. Feed the measured numbers back into guide §5.7 (which currently says "Java 21" and warns about synchronized-block pinning) as part of the TASK_5 supersession.

---

### Group C — Evidence-of-recovery for ALL SIX TASK_2 fields (DEPENDS ON TASK_2)

New `FieldRecoveryIT`. **This group depends on TASK_2** — the six fields and their exact JMS/MQMD accessor property names are **owned by TASK_2**. Do **not** re-derive the constant names here; import whatever TASK_2 finalizes to avoid naming drift between sections. What TASK_3 owns is the *assertion that each field is correctly recovered on BOTH a COA and a COD report*.

- **The six fields to assert (recovered on COA and on COD)**:
  1. **Application identity data** (`ApplIdentityData`)
  2. **Accounting token** (`AccountingToken`)
  3. **Correlation id bytes** (`CorrelationId` as bytes — and that it equals the original `MessageId` bytes under default `MQRO_COPY_MSG_ID_TO_CORREL_ID`)
  4. **Message id bytes** (the report's own `MessageId` bytes)
  5. **Put-timestamp** (`PutDate`+`PutTime`) parsed to a `java.time.LocalDateTime`
  6. **Report type char** — **`'A'` for COA, `'D'` for COD** (the single-character report-type indicator)
- **Precondition (real, easy to miss)**: reading the raw MQMD fields off a report in JMS requires `WMQ_MQMD_READ_ENABLED = true` on the **report consumer's destination**, exactly the same canonical-vs-MQMD split as `JMS_IBM_FEEDBACK` (always populated) vs `JMS_IBM_MQMD_FEEDBACK` (only with read-enabled). TASK_2 must set this; TASK_3's test must connect to a destination that has it enabled or the six fields read back null/absent.
- **Scenario**: produce a COA+COD message that stamps the relevant identity/accounting/timestamp fields; consume+commit to trigger COD; read both reports; for **each** of the six fields assert exact recovery on **both** the COA and the COD report (12 assertions total). Assert the put-timestamp parses to a sane `LocalDateTime` and that field 3 equals the original `MessageId` bytes.
- **Impact**: these fields are the audit/forensic backbone — accounting token for chargeback, app-identity for provenance, put-timestamp for SLA latency, report-type char for COA-vs-COD discrimination at the byte level. If any is silently lost/garbled, post-incident reconstruction fails.
- **Community reports (with links/sources)**: IBM MQ docs *MQMD fields* (`ApplIdentityData`, `AccountingToken`, `PutDate`/`PutTime`) (https://www.ibm.com/docs/en/ibm-mq/9.4?topic=mqi-mqmd-message-descriptor) and *Reading MQMD fields in JMS with WMQ_MQMD_READ_ENABLED* (https://www.ibm.com/docs/en/ibm-mq/9.4?topic=messages-reading-writing-mqmd-fields-from-jms-application); exact accessor constants in the TASK_2 section.
- **Application behavior (explained)**: a report message carries its own MQMD plus (per `MQRO_PASS_*` options) context from the original. With `WMQ_MQMD_READ_ENABLED` the JMS layer exposes these as `JMS_IBM_MQMD_*` properties; without it, only the canonical `JMS_IBM_*` subset is visible.
- **Solution (WHY + HOW)**: enabling MQMD read on the report destination unlocks the raw fields; the test then pins each field's round-trip so a future change to producer context-stamping or report options is caught immediately. Because this validates COA **and** COD independently, it proves the audit trail is complete for both the arrival and the delivery half of every message — the property the ~167 msg/s production system relies on for end-to-end accountability.

---

### Group D — Volumetry / load tests (the cross-cutting ~10,000 rpm / ~167 msg/s mandate)

New package `com.example.ibmmq.load`, `@Tag("load")`, excluded from default `mvn verify`, runnable via `-Pload`.

**D1 — Sustained ~10,000 rpm producer→consumer→report flow.** New `SustainedThroughputLoadIT`.
- **Scenario**: drive a **fixed offered rate of ~167 msg/s** (e.g. a `ScheduledExecutorService` at fixed delay; see open questions for harness choice) producing COA+COD messages through a real `MQContainer` for a sustained window (e.g. 5–10 min); a consumer pool consumes+commits; a report consumer drains COA/COD into the correlation store.
- **Assertions (specs, not measured numbers)**: **report-correlation completeness** — count(COA) == count(COD) == count(sent), zero lost reports; **latency percentiles** — record send→COA and send→COD latency and assert p50/p95/p99 stay under agreed thresholds (set the thresholds in `docs/testing-scenarios.md`; the test asserts against them, it does not hardcode an "observed" number); **pool behavior** — assert the `JmsPoolConnectionFactory` high-water mark stays within `maxConnections` and no borrow-timeout/leak occurs; **backpressure** — when the consumer lags, assert the producer is throttled/queue depth bounded rather than the JVM OOMing or the report queue overflowing.
- **Impact / k8s framing**: 167 msg/s is the production target; this proves a single replica's pool + threading model sustains it without unbounded latency growth or report loss.
- **Community reports**: IBM MQ performance reports / tuning (SHARECNV, read-ahead, async-put) (https://www.ibm.com/docs/en/ibm-mq/9.4?topic=mq-tuning); guide §5.8.
- **Application behavior (explained)**: under sustained load, pool exhaustion, unbounded in-flight correlation entries, or a slow report consumer manifest as growing latency tails and eventually lost/overflowed reports.
- **Solution (WHY + HOW)**: bound the pool, bound the correlation store's pending window, and apply backpressure at the producer; the load test asserts these bounds hold for the full window, which is the only honest evidence the system survives production throughput.

**D2 — Multi-instance / competing-consumers correlation correctness.** New `CompetingConsumersCorrelationIT`.
- **Scenario**: run **multiple consumer instances** (simulating k8s replicas) competing on the same business queue and the same report queue, backed by a **shared correlation store** (per brief P8, the persistent-store note — an in-memory store per replica is wrong here). Produce a load of COA+COD messages; assert every message ends `isFullyConfirmed()` exactly once across the cluster, with no lost or double-counted COA/COD regardless of which replica consumed the message or which replica received its report.
- **Assertions**: each `messageId` confirmed exactly once cluster-wide; no report orphaned because the replica that received it differs from the replica that sent the message (the report can land on **any** report-consumer replica → the store MUST be shared/persistent, not `InMemoryCorrelationStore`); correlation marking is idempotent under concurrent `withCoaReceived()`/`withCodReceived()` from different replicas.
- **Impact / k8s framing**: this is the core distributed-systems correctness property. With competing consumers, the producing replica, the consuming replica, and the report-receiving replica are generally **three different pods** — only a shared persistent correlation store closes the loop. `InMemoryCorrelationStore` silently loses correlation the moment you scale beyond one replica.
- **Community reports**: IBM MQ docs *Multiple consumers / competing consumers and shared input queues* (https://www.ibm.com/docs/en/ibm-mq/9.4?topic=applications-getting-messages-multiple-consumers); brief premise P8; `PersistentCorrelationStoreExample.java` in the repo.
- **Application behavior (explained)**: MQ load-balances messages across competing consumers; reports return to the single `JMSReplyTo` queue and are load-balanced across report consumers independently — so producer/consumer/report-receiver affinity is not guaranteed.
- **Solution (WHY + HOW)**: a shared, transactionally-updated persistent correlation store (the `PersistentCorrelationStoreExample` pattern) keyed by `messageId` makes confirmation cluster-global and idempotent. The test proves that under competing consumers the COA/COD bookkeeping is exactly-once across replicas — the property the whole k8s deployment depends on. Without it, you cannot answer "was order X delivered?" once you have more than one pod.

---

## Deferred work - TASK_4: living, project-agnostic LLM code-analysis context (prompt-doc or ai/skills skill)

> Status: DEFERRED — this section SPECIFIES the deliverable; a future session builds it.

### 0. What this is and the four levels (read first)

The deliverable is a **living, project-agnostic analytic context** that any engineer can point an LLM at to drive a **complete code analysis of any application** built on this stack — **IBM MQ + JMS 2.0 (`javax.jms`) + Micronaut/Java + COA/COD delivery reports** — and get back a structured report of, per finding: **WHERE** a problem may exist, **WHY**, its **IMPACT**, and **POSSIBLE SOLUTIONS**.

Keep four nested levels strictly separate — collapsing them is the single biggest failure mode of this task:

- **L0 — this handoff section.** A *spec*. It tells a future session what to build. It contains *skeletons and examples* of L1, never L1 itself.
- **L1 — the deliverable.** The skill (or doc) the future session writes. Carries the semantic tags, the scenario matrix, the grill-me preamble, and all inlined evidence.
- **L2 — runtime behavior of L1.** When an end-user runs L1 on *their* app: the grill-me interview → `/workflows` fan-out per scenario cell → adversarial verification of each finding → synthesis into the final report. L2 is what L1 *documents and executes*.
- **L3 — the end-user's application** being analyzed.

The grill-me interview, the `/workflows` orchestration, and the subagent templates all live at **L2** — they are things **L1 contains**, executed against **L3**. This section (L0) only specifies that L1 must contain them. Do **not** structure L0 itself as if it were the skill; show L1 as a skeleton.

### 1. DECISION (committed): build a SKILL, not a single doc

Default per the task is a single self-contained prompt doc *if it stays manageable*. It does **not** stay manageable here. Three forcing factors push past a one-shot doc:

1. The required `/workflows` orchestration (fan-out per cell + adversarial verification + synthesis) is a multi-agent **runtime flow**, not a paste-into-chat artifact.
2. Specialized **subagent templates** are skill-only by the task's own wording.
3. **Project-agnostic full-inlining** — the anti-pattern catalog + all constant names + all failure modes + the ~10k-rpm/k8s load model, applied across **4 cells × 9 check dimensions** — blows past any single doc's manageable length.

**Therefore: promote to a SKILL.** It must serve GitHub visitors, so it must **NOT** live under `.claude/`. Create it at:

```
ai/
  README.md                                  # AI tooling index (new)
  skills/
    jms-mq-delivery-report-analyzer/
      SKILL.md                               # L1 entry point (≤ 500 lines, semantic-tag body)
      references/                            # inlined evidence, each ≤ 200 lines + source attribution
        scenario-matrix.md
        anti-pattern-catalog.md
        mq-jms-constants.md
        load-model-k8s.md
        check-dimensions.md
        output-schema.md
      templates/                            # subagent templates (create-subagent conventions)
        cell-analyzer.subagent.md
        adversarial-verifier.subagent.md
        synthesizer.subagent.md
        workflows-orchestration.md           # documents the /workflows run (NOT an agent)
```

Skill name: **`jms-mq-delivery-report-analyzer`** — names the *stack*, never this repo. (Avoid any repo-specific token in the name.)

**Fallback (note, do not build):** if a future session finds the matrix genuinely collapses (e.g. the target only ever has a single producer cell and no report reading), the same content MAY be inlined into one `ai/PROMPT_jms-mq-analysis.md` doc. Collapse criterion: fewer than ~2 occupied cells and no subagent fan-out needed. Commit to the skill shape otherwise.

**Reconciling the "inline everything" tension with create-skill rules.** The task says inline all evidence; create-skill HARD_RULES say **never inline reference content in the `SKILL.md` body** and cap the body at **500 lines**. These reconcile cleanly: inline the evidence into the skill's **`references/` files** (each ≤ 200 lines, with a source-attribution header), and have `SKILL.md` pull them in by **progressive disclosure** per phase. The skill *directory* is fully self-contained — a visitor copies one directory and needs nothing from this repo. This is exactly the point where the two rule-sets appear to conflict; resolve it this way.

**Deployment for visitors:** a visiting engineer copies `ai/skills/jms-mq-delivery-report-analyzer/` into their own `.claude/skills/` (or pastes `SKILL.md` + references into an LLM chat). Document this in `ai/README.md`.

### 2. PROJECT-AGNOSTIC discipline (HARD CONSTRAINT)

L1 must make **zero** reference to this repository's implementations, classes, paths, or docs. Discipline line:

- **Allowed and required** (these are stack/library/standard names — they ARE the inlined evidence base, mine them into `references/`): `com.ibm.mq:com.ibm.mq.allclient`, `javax.jms` / JMS 2.0, `org.messaginghub:pooled-jms`, `org.messaginghub.pooled.jms.JmsPoolConnectionFactory`, `JMSContext`, `WMQConstants.*`, `com.ibm.mq.constants.CMQC` / `MQConstants`, `MQFB_COA=259`, `MQFB_COD=260`, `MQFB_EXPIRATION=258`, `MQFB_PAN=275`, `MQFB_NAN=276`, `MQRO_COPY_MSG_ID_TO_CORREL_ID`, `WMQConstants.JMS_IBM_FEEDBACK`, `WMQConstants.JMS_IBM_REPORT_*`, `WMQConstants.USER_AUTHENTICATION_MQCSP`, the `2035` / `+setall` / `SET AUTHREC ... AUTHADD(PUT, SETALL)` gotcha, `SET CHLAUTH`.
- **Forbidden in L1** (this repo's identifiers): any `com.example.ibmmq.*` class, this repo's class names (e.g. the business/report producer and consumer classes, the correlation store classes, the feedback router, the end-to-end IT), this repo's paths (`research-output/...`, `ibmmq-jms-guide/...`, the pt-BR guide filename), and any mention of "this project".

**Leak check (put in the acceptance checklist):** `grep -ri 'com.example' ai/skills/jms-mq-delivery-report-analyzer/ ; grep -rEi 'ibmmq-jms-guide|research-output|CoaCodEndToEndIT|guia-ibmmq' ai/skills/jms-mq-delivery-report-analyzer/` → **must return zero matches**.

### 3. CROSS-CUTTING standing assumption (weave into EVERY cell)

L1 must declare, as a standing analysis assumption the LLM applies to **every** matrix cell: the target runs in a **distributed Kubernetes + microservices** topology at an **average throughput of ~10,000 requests/minute (~167 msg/s)**. Every check must be evaluated under that load and topology. Concretely, the LLM must always ask, per cell: pod horizontal scale and per-pod session/context count; pool sizing (`JmsPoolConnectionFactory` `maxConnections` / `maxSessionsPerConnection`) vs. queue-manager `MAXHANDS`/channel `MAXINST`; correlation-store contention and TTL/eviction at 167 msg/s; report-queue drain rate vs. inbound rate; rolling-deploy / pod-eviction behavior (in-flight un-acked messages, un-committed transactions, reconnect storms); idempotency across pod restarts; and back-pressure when a downstream microservice stalls. Inline this as `references/load-model-k8s.md`.

### 4. SCENARIO TAXONOMY — the matrix (map ALL scenarios)

Split simultaneously along two axes: **(1) Producer vs Consumer**; **(2) Integration type: business message read/write at source/destination VS report reading (COA/COD/Exception/Expiration/PAN-NAN)**. Four cells. Note one microservice frequently occupies **multiple** cells (it consumes a business message, then produces a downstream one, then reads reports) — that is the "truly all" coverage, not a cell explosion. Enumerate cells by role, not by service.

| Cell | Role × integration | What it covers |
| --- | --- | --- |
| **C1** | Producer × business-write | App PUTs a business message to a source/destination queue; sets persistence, expiry, `JMSReplyTo`, `JMSCorrelationID`, report-request options. |
| **C2** | Producer × report-request | App requests COA/COD/Exception/Expiration/PAN/NAN via `WMQConstants.JMS_IBM_REPORT_*`, chooses id-propagation (`MQRO_COPY_MSG_ID_TO_CORREL_ID`), aims `JMSReplyTo` at a report queue. |
| **C3** | Consumer × business-read | App destructively/ browse-reads a business message; ack mode, transactions, redelivery, DLQ, poison-message handling. (Destructive read at the destination is what triggers COD upstream.) |
| **C4** | Consumer × report-read | App reads the report queue, branches on the **feedback code**, and correlates report → original message. Must address **each** report kind: **COA**, **COD**, **Exception** (carries an `MQRC_*` in Feedback — there is no `MQFB_EXCEPTION`), **Expiration** (`258`), **PAN** (`275`), **NAN** (`276`). |

### 5. CHECK DIMENSIONS — the 9 lenses applied PER CELL

For each occupied cell the LLM runs all nine, under the §3 load/topology assumption. Inline as `references/check-dimensions.md`.

1. **Anti-patterns** — the catalog in §6.
2. **Correctness** — right constants, right namespace (`javax` vs `jakarta`), report options actually set on the producer, feedback read from the canonical field, persistence/expiry intent matches behavior.
3. **Concurrency** — JMS `Session`/`JMSContext`/`MessageProducer`/`MessageConsumer` are **NOT thread-safe**; one per thread. Verify no shared session across threads/pods; verify pooling (`JmsPoolConnectionFactory`) is used instead of sharing or instead of churning raw connections; on Java 24+/25 verify Virtual-Thread pinning expectations (JEP 491 removed pinning on `synchronized`; pinning now only on native/JNI frames — flag long-held native calls under VTs).
4. **Resource leaks** — every `JMSContext`/`Connection`/`Session`/`Consumer`/`Producer` closed in `finally`/try-with-resources; no per-message connection creation; pool returned, not leaked; listener containers shut down on pod stop.
5. **Correlation integrity** — default `MQRO_COPY_MSG_ID_TO_CORREL_ID` means the original `MessageId` becomes the report's `CorrelationId`; verify the consumer correlates `report.JMSCorrelationID → original MessageId` (not the reverse, not by `JMSMessageID`); verify correlation store survives pod restart for in-flight correlations and has bounded TTL/eviction at 167 msg/s.
6. **Transaction / ack correctness** — ack mode vs intent; under syncpoint, **COA only flows after the producer commits, COD only after the consumer commits** (a backed-out consume yields **no** COD); duplicate delivery on redelivery; no ack before side effects are durable.
7. **Security** — `WMQConstants.USER_AUTHENTICATION_MQCSP` (boolean, no `WMQ_` prefix) set for MQCSP auth; TLS does **not** set the removed `com.ibm.mq.cfg.useIBMCipherMappings` (gone since MQ 9.4.0) and avoids `TLS_RSA_*` ciphers (disabled on Java 25); `SET CHLAUTH` (not `DEFINE`) governs channel auth. **2035 gotcha:** for the queue manager to generate+deliver a report it PUTs-with-context to the ReplyToQ, needing context authority (`+setall`); a low-privilege principal lacking it makes the report PUT fail `MQRC_NOT_AUTHORIZED (2035)` and the report silently lands on the **DLQ** (report queue stays empty). Prod grants `SET AUTHREC ... AUTHADD(PUT, SETALL)`.
8. **Resilience** — auto-reconnect config, reconnect-storm avoidance on pod churn, DLQ wiring + backout threshold/requeue, idempotent consumption across restarts, poison-message handling, report-queue not silently filling/expiring.
9. **Performance under load** — pool sizing vs `MAXHANDS`/`MAXINST`; batching/commit interval at 167 msg/s; report-queue drain rate vs inbound; selector cost; correlation-store hot path; back-pressure on downstream stall.

`references/scenario-matrix.md` holds the cell × dimension grid; `references/check-dimensions.md` holds the nine lenses in full.

### 6. INLINED EVIDENCE — anti-pattern catalog + constants (project-agnostic)

`references/anti-pattern-catalog.md` (each entry: anti-pattern → why wrong → impact under ~10k rpm/k8s → fix). At minimum:

- Sharing one `Session`/`JMSContext` across threads or across pods (race/corruption; intermittent under load) → one per thread, pool connections.
- Creating a `Connection`/`JMSContext` per message (handle exhaustion, `MAXHANDS`/`MAXINST` hit at 167 msg/s) → `JmsPoolConnectionFactory`.
- Reading feedback from `WMQConstants.JMS_IBM_MQMD_FEEDBACK` without `WMQ_MQMD_READ_ENABLED=true` (null/empty) → use canonical **`WMQConstants.JMS_IBM_FEEDBACK`** (always populated).
- Looking for `MQFB_EXCEPTION` (does not exist) → exception reports carry an `MQRC_*` in the Feedback field; branch on the feedback value range.
- Assuming reports are non-persistent → reports **inherit persistence from the original**; a persistent original yields a persistent COA/COD.
- Correlating by `JMSMessageID` instead of `JMSCorrelationID` (default id propagation makes original `MessageId` → report `CorrelationId`).
- Expecting a COD after a backed-out consume under syncpoint (none is generated).
- Acking/committing before downstream side effects are durable (lost-message window on pod eviction).
- Mixing `javax.jms` and `jakarta.jms` namespaces / mixing `pooled-jms` 2.x (javax) with 3.x (jakarta).
- Setting the removed `com.ibm.mq.cfg.useIBMCipherMappings`; using `TLS_RSA_*` ciphers.
- App principal lacking `+setall` → report PUT 2035 → report silently to DLQ.
- Unbounded correlation store (memory blow-up at 167 msg/s) → TTL + eviction + restart-survivable backing.

`references/mq-jms-constants.md` inlines: the feedback codes (`MQFB_COA=259`, `MQFB_COD=260`, `MQFB_EXPIRATION=258`, `MQFB_PAN=275`, `MQFB_NAN=276`), the home classes (`MQRO_*`/`MQFB_*` in `com.ibm.mq.constants.CMQC` / `MQConstants`; **not** `WMQConstants`), the JMS property names (`WMQConstants.JMS_IBM_REPORT_*` field UPPER_SNAKE / String value mixed-case e.g. `"JMS_IBM_Report_COA"`; `WMQConstants.JMS_IBM_FEEDBACK`; `WMQConstants.USER_AUTHENTICATION_MQCSP`), and report semantics (COA on arrival at destination, COD on destructive consume; syncpoint timing). Each reference file carries a source-attribution header.

### 7. GRILL-ME PREAMBLE (embed verbatim-in-spirit, runs BEFORE analysis)

L1's first `<PHASE>` is a **relentless interview** that aligns gaps before any analysis runs. Rules (transcribe into the skill):

- Ask **one question at a time**; walk **each branch** of the decision tree; resolve dependencies **one-by-one**.
- Provide a **RECOMMENDED answer** for every question.
- **Explore the user's codebase to answer a question instead of asking, whenever that is possible** — only ask the human when the answer cannot be found in code/config.
- Stop only when remaining ambiguity cannot change the analysis plan.

Seed branch questions (each with a recommended answer and the "explore first" instruction):

1. Which matrix cells does this service occupy (producer-write / report-request / consumer-read / report-read)? *Rec: infer from code — presence of PUT vs MessageListener vs report-queue reads; a single service often occupies several.*
2. `JmsPoolConnectionFactory` (pooled-jms) or raw `ConnectionFactory`? *Rec: pooled.* (Explore: dependency + factory wiring.)
3. `javax.jms` or `jakarta.jms` namespace? *Rec: detect from imports / `pooled-jms` major version.*
4. Ack strategy — transacted sessions, `CLIENT_ACKNOWLEDGE`, or `AUTO_ACKNOWLEDGE`? *Rec: transacted for at-least-once at scale.* (Explore: session creation args.)
5. Are reports requested, and which (COA/COD/Exception/Expiration/PAN/NAN)? *Rec: detect from `JMS_IBM_REPORT_*` usage.*
6. Reports read from a **dedicated** report queue or the app's general reply queue? *Rec: dedicated.* (Explore: `JMSReplyTo` target + consumer destinations.)
7. How is feedback read — `JMS_IBM_FEEDBACK` (canonical) or `JMS_IBM_MQMD_FEEDBACK`? *Rec: canonical.* (Explore: property reads.)
8. Correlation mapping direction and store (in-memory vs durable) + TTL? *Rec: durable + bounded TTL at this load.* (Explore: correlation store class.)
9. Auth/TLS — MQCSP set? cipher suite? `SET CHLAUTH` rules? Does the app principal have `+setall`? *Rec: MQCSP on, non-RSA cipher, `+setall` granted.* (Explore: connection config + any MQSC in repo.)
10. Deployment shape — pod replica count, pool sizing vs `MAXHANDS`/`MAXINST`, expected msg/s? *Rec: validate against ~167 msg/s baseline.* (Explore: k8s manifests / Helm if present.)

### 8. ENFORCEMENT + ACCEPTANCE CRITERIA inside L1 (create-skill conventions)

L1's `SKILL.md` body carries the **canonical semantic-tag vocabulary** in canonical positions (NOT as separate files). Skeleton of the L1 body to produce:

```markdown
---
name: jms-mq-delivery-report-analyzer
description: "Drives a complete LLM code analysis of any IBM MQ + JMS 2.0 + Micronaut/Java app focused on COA/COD delivery reports, reporting WHERE/WHY/IMPACT/SOLUTIONS per finding. Use when analyzing an IBM MQ + JMS messaging codebase for correctness, concurrency, correlation, transaction, security, resilience, and performance issues under distributed k8s load."
---

# JMS / IBM MQ Delivery-Report Analyzer

<TRIGGER when="analyzing any IBM MQ + JMS 2.0 + Micronaut/Java application that produces or consumes business messages and/or reads COA/COD/Exception/Expiration/PAN-NAN reports" />

<BEHAVIOUR
  avoid="running analysis before the grill-me interview resolves gaps; surfacing low-confidence findings as facts; referencing any specific source project"
  always="explore the target codebase to answer a question before asking the human; apply the k8s + ~10k-rpm assumption to every cell; cite file:line for every finding; emit WHERE/WHY/IMPACT/SOLUTIONS">
- ...
</BEHAVIOUR>

<HARD_RULES priority="hard">
- **ALWAYS** run the grill-me interview (Phase 1) to completion before any analysis fan-out.
- **EVERY** finding MUST carry WHERE (file:line), WHY, IMPACT, SOLUTIONS, severity, cell tag, confidence.
- **EVERY** cell is evaluated under the distributed k8s + microservices + ~10k rpm (~167 msg/s) assumption.
- **NEVER** read feedback only from JMS_IBM_MQMD_FEEDBACK; the canonical field is JMS_IBM_FEEDBACK.
- **NEVER** reference a specific source repository, its classes, or its paths in any output.
- **Agents CANNOT spawn agents** — orchestration is this skill's main thread or the /workflows tool.
</HARD_RULES>

<PROCESS>
  <PHASE id="1" name="grill-me-interview"> ... one question at a time, recommended answers, explore-first ... </PHASE>
  <PHASE id="2" name="cell-mapping"> ... map occupied cells from §4 ... </PHASE>
  <PHASE id="3" name="fan-out-analysis"> ... /workflows: spawn cell-analyzer per occupied cell × 9 dimensions ... </PHASE>
  <PHASE id="4" name="adversarial-verification"> ... spawn adversarial-verifier per finding ... </PHASE>
  <PHASE id="5" name="synthesis"> ... spawn synthesizer; emit the report per output schema ... </PHASE>
</PROCESS>

<VALIDATION loop="max-iterations:3">
Per phase: Phase 1 — every occupied cell has its open questions resolved or codebase-answered.
Phase 3 — every occupied cell × 9 dimensions produced findings or an explicit "clean" note.
Phase 4 — every finding either survived verification or was downgraded/dropped with reason.
Phase 5 — report matches the output schema; zero source-project leakage. Loop until all pass; max 3.
</VALIDATION>
```

**Per-phase verification targets** (explicit success conditions) are embedded in `<VALIDATION>` above. The **output-format contract** is §9.

### 9. OUTPUT-FORMAT CONTRACT (the report L2 returns)

Inline as `references/output-schema.md` and enforce in `<HARD_RULES>`. Each finding:

```
- Finding ID: <stable id>
  Cell: <C1 Producer×business-write | C2 Producer×report-request | C3 Consumer×business-read | C4 Consumer×report-read>
  Dimension: <anti-pattern|correctness|concurrency|leaks|correlation|tx-ack|security|resilience|performance>
  Severity: <blocker|high|medium|low>
  Confidence: <high|medium|low>
  WHERE: <file:line(s)>
  WHY: <root cause, with the constant/contract it violates>
  IMPACT: <consequence, framed under k8s + ~167 msg/s>
  SOLUTIONS: <1..n concrete fixes, with constant names / MQSC / property names>
```

Report wrapper: an executive summary (counts by severity and by cell), the per-cell coverage table (which of the 9 dimensions ran per occupied cell), then the findings, then a "clean dimensions" list (so coverage is auditable).

### 10. SUBAGENT TEMPLATES (skill `templates/`, create-subagent conventions)

Because agents **cannot spawn agents** (the Task tool is unavailable at runtime), the orchestration that spawns these must be the **skill's main thread** or the dynamic **`/workflows`** tool — never an agent. Each template follows create-subagent: YAML frontmatter (`name`, `description` with a "Use when…", `tools`, `model`, `maxTurns`), a one-line role identity, `<BEHAVIOUR>`, `<HARD_RULES>`, `<PROCESS>` with `<PHASE>`, `<OUTPUT>`, `<VALIDATION>`. All three are read-only (**no `Edit`/`Write`**); `Bash` only for read-only commands.

- **`cell-analyzer.subagent.md`** — `model: sonnet` (structured findings), `maxTurns: 15`. Tools: `Read, Grep, Glob, Bash` (read-only). Input: one occupied cell + the 9 dimensions + inlined evidence. Output: findings in the §9 schema. Self-verify: every dimension addressed; every finding has file:line + WHY/IMPACT/SOLUTIONS; k8s/load lens applied.
- **`adversarial-verifier.subagent.md`** — `model: sonnet`, `maxTurns: 20` (evaluator). Tools: `Read, Grep, Glob` (read-only). Input: one finding. Tries to **break** it — confirm/downgrade/drop with a reason (e.g. "guarded by try-with-resources two frames up", "the namespace is actually jakarta"). Self-verify: a verdict + evidence for every finding.
- **`synthesizer.subagent.md`** — `model: sonnet` (use `opus` only with explicit justification for very large reports), `maxTurns: 15`. Tools: `Read` (read-only). Input: all surviving findings. Output: the §9 report wrapper, deduped, sorted by severity then cell. Self-verify: schema match, zero source-project leakage, coverage table complete.

`haiku` only for a narrow read-only lookup helper if one is added; default `sonnet` for all judgment-bearing agents.

### 11. `/workflows` ORCHESTRATION (document in `templates/workflows-orchestration.md`)

Since this is investigative research, L2 uses the dynamic **`/workflows`** tool to orchestrate:

- **Fan-out** one `cell-analyzer` run per **occupied** cell (from grill-me Phase 1), each covering the 9 dimensions under the load assumption.
- **Adversarial verification:** fan-out one `adversarial-verifier` per finding (or per cell's finding batch).
- **Synthesis:** a single `synthesizer` run merges surviving findings into the §9 report.
- The `/workflows` run (or the skill main thread) is the spawner — the subagents themselves spawn nothing.

### 12. README / index wiring

- **New `ai/README.md`** — index of AI tooling: one entry for `jms-mq-delivery-report-analyzer` (what it does, the 4-cell matrix, how a visitor installs it by copying `ai/skills/jms-mq-delivery-report-analyzer/` into their `.claude/skills/` or pasting `SKILL.md`).
- **`README.md`** (repo root) — add a short **"AI code-analysis tooling"** section linking to `ai/README.md` and the skill, and add an `ai/` line to the **Repository layout** block (currently lists `ibmmq-jms-guide/`, `docs/`, `research-output/`, the brief, `CLAUDE.md` — insert `ai/` alongside `docs/`). Keep wording project-agnostic about the skill's purpose.

### 13. ACCEPTANCE CHECKLIST (deliverable is done when ALL true)

- [ ] **All matrix cells covered:** C1 Producer×business-write, C2 Producer×report-request, C3 Consumer×business-read, C4 Consumer×report-read — each with the 9 check dimensions; within C2/C4, COA + COD + Exception + Expiration + PAN + NAN each addressed.
- [ ] **Grill-me preamble present** as L1 Phase 1: one question at a time, recommended answer per question, dependency-ordered, explore-codebase-before-asking.
- [ ] **All evidence inlined** in `references/` (anti-pattern catalog, constants/feedback codes, load model, check dimensions, output schema), each ≤ 200 lines with source attribution; `SKILL.md` body ≤ 500 lines and pulls references by progressive disclosure.
- [ ] **Project-agnostic:** the §2 leak greps return zero matches; no "this project" references; only stack/library/standard names used.
- [ ] **Enforcement/validation tags present** in `SKILL.md`: `<TRIGGER>`, `<BEHAVIOUR avoid/always>`, `<HARD_RULES priority="hard">`, `<PROCESS>` with `<PHASE id name>`, `<VALIDATION loop="max-iterations:3">`; per-phase verification targets explicit; output-format contract enforced.
- [ ] **k8s + ~10k-rpm assumption** declared as a standing per-cell analysis rule and referenced by every dimension.
- [ ] **Referenced from `README.md`** (new "AI code-analysis tooling" section + `ai/` in the layout block) and from a new **`ai/README.md`** index.
- [ ] **Skill location** is `ai/skills/jms-mq-delivery-report-analyzer/` (NOT under `.claude/`).
- [ ] **Subagent templates present** in `templates/` (`cell-analyzer`, `adversarial-verifier`, `synthesizer`), each create-subagent-compliant: role + `<PROCESS>`/`<PHASE>` + `<OUTPUT>` + `<VALIDATION>`, model heuristic (sonnet default), least-privilege read-only tools (no `Edit`/`Write`), `maxTurns` pinned.
- [ ] **`/workflows` orchestration documented** in `templates/workflows-orchestration.md` (fan-out per cell → adversarial verification → synthesis), with the explicit note that agents cannot spawn agents so the spawner is the skill main thread or `/workflows`.
- [ ] **Output report** emits WHERE / WHY / IMPACT / SOLUTIONS per finding plus severity, cell tag, dimension, confidence, and an auditable coverage table.
- [ ] **Living/anti-rot:** `references/mq-jms-constants.md` and the version-sensitive anti-patterns (namespace `javax`/`jakarta`, `pooled-jms` 2.x vs 3.x, removed `useIBMCipherMappings`, `TLS_RSA_*`, JEP 491 VT-pinning) carry a "refresh when the IBM MQ client / `pooled-jms` / Micronaut / JDK versions change" note, so the standing context stays current rather than rotting.

### 14. Dependencies and open questions

- **Independent of** TASK_1/2/3/5; only consumes the validated facts (already in `CLAUDE.md` + `research-output/phase-a-fact-sheet.md`) as the inlined evidence base. The Java-25 supersession (JEP 491 VT-pinning nuance) belongs in the concurrency dimension's wording — keep it consistent with the TASK_5 section.
- **Open question:** confirm the dynamic `/workflows` tool is available in the session that builds L1; if not, the skill main thread runs the fan-out sequentially (document both paths).
- **Open question:** whether to ship a tiny `haiku` lookup helper agent for raw constant/grep lookups, or fold that into `cell-analyzer` (default: fold in, add only if `cell-analyzer` proves token-heavy).

---

_Generated via the `handoff` skill. Start a new session, read the "DO THIS FIRST" block, and continue at Phase D._
