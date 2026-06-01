# Handoff: IBM MQ COA/COD — Phase D closed + full program planned & tracked

**Created:** 2026-05-31 21:22
**Branch:** main (NOTE: a git repo now — has GitHub remote + gh authed; the older handoff's "not a git repo" is stale)
**Session focus:** finished Phase D (HTML), then a `/grill-with-docs` session that expanded the scope into a large program, all captured as a PRD + 22 GitHub issues. **Nothing was committed this session** — the entire working tree is uncommitted.

---

## Summary

Phase D (the single-file standalone HTML deliverable) is **DONE and verified** — including a full-width layout fix and 4 colored/animated Mermaid diagrams with the Mermaid runtime inlined (`docs/index.html`, ~3.45 MB, standalone). A grilling session then resolved a **major scope expansion** (Q1–Q10): English-first docs with a bilingual pt-BR/en-US HTML, a references bibliography, a test-scenario catalogue, narrated execution logging + a demo runner + a runbook, and a **local k3s distributed harness** (not real AWS EKS). All of it is consolidated in **PRD issue #1** and broken into **GitHub issues #2–#22** (7 closed/done, 14 open `ready-for-agent`). The program has **not started executing** — next session begins implementation at issue **#9**.

---

## Work Completed

### Changes Made
- [x] **Phase D base HTML** — built `docs/build-html.py` (stdlib + build-time `python-markdown` in `.venv-docs/`) → deterministic `docs/index.html` with nav/search/copy/callouts/syntax-highlight/WCAG-AA + a **mechanical parity gate**. Verified PARITY: PASS, zero external deps. (via Workflow: build → 3 adversarial reviewers → fix)
- [x] **Width fix** — `.main-inner{max-width:none;margin:0;padding:40px 48px 96px}` (removed the narrow centered 78ch cap); content now fills the area beside the 300px sidebar. (via Workflow)
- [x] **Mermaid** — 4 guide ASCII diagrams → colored, light/dark-proof `\`\`\`mermaid` (animated flow edges, v11.3+); `build-html.py` teaches `<pre class="mermaid">` + inlines vendored Mermaid 11.15.0 runtime (`docs/vendor/`, gitignored); refined external-dep scan; parity now counts mermaid (source=4 output=4). (via Workflow)
- [x] **`/init-claude` (scoped)** — created `ibmmq-jms-guide/src/test/CLAUDE.md` (TDD-first, @MicronautTest+real values, Mockito sparingly, AssertJ, scope separation unit/smoke/IT/e2e, @Nested/@ParameterizedTest/@DisplayName). Added `assertj-core:3.27.3` (test) to `pom.xml` — verified `test-compile` green.
- [x] **CLAUDE.md hardening** — Diagrams-as-Mermaid rule (colors on every shape, light/dark-proof palette, v11.3+ edge animation + GIF fallback, inline-runtime HTML consequence) + a new **"Documentation & environment policy"** standing block (language/references/test-docs/logging/runbook/k3s).
- [x] **`/grill-with-docs`** — Q1–Q10 resolved (see Decisions). Synthesized: `CONTEXT.md` (glossary), `docs/adr/0002-language-policy-bilingual-html.md`, `docs/adr/0003-local-k3s-distributed-harness.md`, `docs/project-status.md`.
- [x] **`/to-prd`** → published **PRD issue #1** (`ready-for-agent`).
- [x] **`/to-issues`** → published **#2–#22** (backfill #2–#7 closed; slice-1 #8 closed; slices #9–#22 open `ready-for-agent`, dependency-ordered, parent #1).
- [x] Memory: added `tdd-for-test-implementations` (user directive: always `/tdd` for tests).

### Key Decisions (the grill, Q1–Q10)
| Decision | Rationale | Alternatives |
| --- | --- | --- |
| Q1 i18n: **2 md sources + parity gate** (EN canonical `docs/guide-*.md` + pt-BR under `docs/i18n/`) | Mechanical sync; EN GitHub-readable | one md / two first-class guides |
| Q2 toggle: **browser-detect + pt-BR fallback + localStorage** | friendly, serverless | fixed pt-BR / fixed en-US |
| Q3 refs: **`docs/references.md` canonical → generated into HTML + README** | single source, zero divergence | inline / README-only |
| Q4 tests doc: **`docs/testing-scenarios.md`, per-scenario, tiered** | unifies with old TASK_3 | per-method / Javadoc-index |
| Q5 logs: **narrated INFO + MDC** | learn + aggregation-ready | JSON-only / dual-profile |
| Q6 demo: **gated Micronaut runner vs docker-compose broker + runbook** | reuses beans + console persists | shell script / docs-only |
| Q7 CONTEXT.md: **pure glossary + separate `project-status.md`** | glossary stays stable | one broad file |
| Q8 translate: **EVERYTHING to EN** (incl. brief + handoffs; originals in git) | full consistency | living-only / guide-only |
| Q9 distributed: **local k3s + microservices** (floci=local emulator, optional) | real multi-pod, $0; floci≠EKS/no-MQ | real EKS / keep assumed |
| Q10 order: **docs → observability → k3s → TASK_2/3/4 → Phase E** | lock deliverable first; i18n precedes Phase E | engineering-first / depth-first |

---

## Files Affected

### Created (untracked — uncommitted)
- `CONTEXT.md` — domain glossary (EN, ~30 terms).
- `docs/adr/0002-language-policy-bilingual-html.md`, `docs/adr/0003-local-k3s-distributed-harness.md`.
- `docs/project-status.md` — done + ordered roadmap.
- `docs/build-html.py` — deterministic HTML generator (markdown→bilingual-ready single-file; parity gate; mermaid render).
- `docs/index.html` — the deliverable (~3.45 MB; inlined Mermaid runtime; **currently pt-BR only** — bilingual is issue #13).
- `ibmmq-jms-guide/src/test/CLAUDE.md` — test conventions.
- `docs/__pycache__/` — **should be gitignored** (python bytecode; add it).

### Modified (uncommitted)
- `CLAUDE.md` — Mermaid rule + Documentation/environment policy standing block.
- `docs/guia-ibmmq-jms-micronaut.md` — 4 `\`\`\`text`→`\`\`\`mermaid`; still the pt-BR guide (becomes the pt-BR i18n source in #9/#13).
- `ibmmq-jms-guide/pom.xml` — `assertj.version` + `assertj-core` test dep.
- `.gitignore` — added `.venv-docs/`, `docs/vendor/`.
- `docs/index-evidence.png` — staged (user's width screenshot).
- (Pre-existing uncommitted from prior sessions: `README.md`, `ibmmq-jms-guide/README.md`, `research-prompt-*.md`, old handoff — not touched this session.)

### Read (reference)
- Prior handoff `docs/handoffs/HANDOFF_IBMMQ_JMS_MICRONAUT_05_31.md` (TASK_2/3/4 appendix specs — still the authoritative detail for issues #19/#20/#21/#12).
- `research-prompt-*.md` (P17/P18/P19/P20/§3.1), `research-output/phase-a-fact-sheet.md` (refs source for #14).

---

## Technical Context

### Architecture / build pipeline
- **HTML build:** `.venv-docs/bin/python docs/build-html.py` reads `docs/guia-*.md` → emits `docs/index.html`. Build needs `python-markdown` (in `.venv-docs/`, gitignored). The OUTPUT is standalone (no external deps). Regeneration is exact (obligation: never hand-patch `index.html`; always regenerate).
- **Mermaid:** runtime vendored to `docs/vendor/mermaid.min.js` (gitignored, fetched-once), inlined into the HTML; `<pre class="mermaid">` + `mermaid.initialize`. Sequence diagrams can't use classDef/animation (Mermaid limit) → used themeVariables + `box` tints.
- **Bilingual model (issue #13, not built yet):** generator must read EN + pt-BR sources, embed both, toggle (browser-detect/localStorage), and a **structural parity gate EN↔pt-BR↔HTML** failing the build on mismatch.
- **App:** Micronaut for DI/@Factory/lifecycle only; manual JMS (`JMSContext`); `JmsPoolConnectionFactory` wraps `MQConnectionFactory` (CLIENT mode); report consumer reads `WMQConstants.JMS_IBM_FEEDBACK`, correlates report.CorrelationID → original MessageId.

### Dependencies added
- `org.assertj:assertj-core:3.27.3` (test). Build-time `python-markdown` (venv) + vendored Mermaid 11.15.0 (gitignored).

---

## Things to Know

### Gotchas
- **Nothing committed this session.** 16 working-tree changes. Commit only when the user asks (global rule). On `main` — branch first if committing.
- **`docs/__pycache__/` is untracked** → add to `.gitignore`.
- **Build needs the venv:** `.venv-docs/bin/python` (gitignored). A fresh clone must `python3 -m venv .venv-docs && .venv-docs/bin/pip install markdown` and (for mermaid) re-fetch the runtime.
- **floci ≠ AWS EKS** — it is a LocalStack-class local emulator (k3s, no IBM MQ). ADR-0003. The distributed harness is local k3s.
- **Mermaid render not browser-verified** (no chromium in sandbox); source valid + runtime self-contained → renders on open.
- **IT gotchas carried forward** (still true): report-PUT needs `+setall` else 2035→DLQ (IT connects as `admin`); reports inherit persistence; `MQRO_*`/`MQFB_*` in `CMQC`/`MQConstants` not `WMQConstants`; run with `--enable-native-access=ALL-UNNAMED`; disable sandbox for the IT (Docker socket).

### Known issues / debt
- `docs/index.html` is **pt-BR only** today (bilingual is issue #13).
- The two i18n sources don't exist yet (issue #9 creates them).

---

## Current State

### Working
- Phase D: `docs/index.html` generated, PARITY: PASS, standalone, full-width, 4 mermaid diagrams inlined.
- `ibmmq-jms-guide`: compiles; 15 unit tests + COA/COD IT green (from prior sessions; not re-run this session).
- Tracker fully set up: PRD #1 + 22 issues with dependencies.

### Tests
- [x] Unit (15) + COA/COD IT — green as of prior sessions (NOT re-run this session).
- [ ] No new tests written this session (Phase D/docs work). `assertj` now available for future tests.

---

## Next Steps

### Immediate (Start Here) — execute the program per Q10
1. **Issue #9 — English-first guide translation.** Translate `docs/guia-*.md` → canonical EN `docs/guide-*.md`; restructure the pt-BR text as the HTML i18n source under `docs/i18n/`. This is the critical-path head (#9 → #13 → {#14, #15}).
2. **Then #13** (bilingual HTML + toggle + parity gate) — the biggest generator change; regenerate `index.html`.
3. **In parallel (independent AFK, no blockers):** #10 (translate remaining docs), #11 (narrated MDC logging), #12 (analyzer skill).

### Subsequent (dependency order)
- #16 demo runner ← #11; #17 runbook ← #16; #18 k3s harness [HITL] ← #11; #19 MQMD recovery ← #18; #20 matrix+VT ← #19,#15; #21 load ← #18,#20; **#22 final acceptance [HITL]** ← all.
- TASK_2/3/4 detail lives in the **prior handoff's APPENDIX** (`HANDOFF_IBMMQ_JMS_MICRONAUT_05_31.md`) — authoritative for #19/#20/#21/#12 (R1–R6 reconciliations, bytecode-verified `JMS_IBM_MQMD_*` keys to re-verify, etc.).

### Blocked On
- None environmental. All tests follow `/tdd` + `ibmmq-jms-guide/src/test/CLAUDE.md`. Any guide edit ⟹ regenerate `index.html` (never hand-patch).

---

## Related Resources

### Documentation
- **PRD:** https://github.com/rodrigorjsf/ibm-mq-java-poc/issues/1
- **Open work:** issues #9–#22 (label `ready-for-agent`); closed/done: #2–#8.
- `CONTEXT.md`, `docs/project-status.md`, `docs/adr/0001..0003`.

### Commands to Run
```bash
source /home/rodrigo/.local/ibmmq-env.sh                 # JAVA_HOME Corretto 25 + Maven
cd /home/rodrigo/IBM-MQ
.venv-docs/bin/python docs/build-html.py                 # regenerate docs/index.html (PARITY gate)
gh issue list --state open --label ready-for-agent       # the queue
mvn -f ibmmq-jms-guide/pom.xml test                      # unit (fast)
# IT (Docker; run via Bash with dangerouslyDisableSandbox:true):
mvn -f ibmmq-jms-guide/pom.xml clean verify
```

### Search Queries
- `grep -nE '^### Apêndice|^### [0-9]' docs/guia-ibmmq-jms-micronaut.md` — guide structure to mirror/translate.
- `grep -n 'class="mermaid"\|globalThis\["mermaid"\]' docs/index.html` — mermaid render wiring.
- prior handoff APPENDIX `## Deferred work - TASK_2/3/4` — the implementation specs for #19/#20/#21/#12.

---

## Open Questions
- [ ] Start executing now (issue #9) or pause? (User was asked at session end; unanswered — they ran `/handoff`.)
- [ ] Commit cadence — nothing committed yet; user controls when (branch off `main` first).
- [ ] #11 (MQMD recovery) AFK vs HITL given the report-own-vs-original (R2) producer-default decision.

---

## Session Notes
- Heavy use of the **Workflow** tool (Phase D base, width, mermaid) — build → adversarial verify → fix, with mechanical parity gates doing fidelity (LLMs judged only a11y/functionality/pt-BR). Continue that pattern.
- The advisor gate was satisfied once early (covers the session); the grill served as the adversarial pass for the scope expansion.
- Communication with the user is **pt-BR**; durable artifacts **English** (now incl. all docs except the bilingual HTML — ADR-0002).
- The local TaskCreate list (#1–#10) mirrors the GitHub issues; the **GitHub issues are now canonical**.

---

_Generated at a checkpoint. Start a new session, read this + PRD #1 + the prior handoff's APPENDIX, and begin at issue #9._
