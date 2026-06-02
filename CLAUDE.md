# IBM MQ + JMS 2.0 + Micronaut 4 — Project Guide

Production-grade reference for integrating **Java 25 / Micronaut 4** with **IBM MQ** via **JMS 2.0 (`javax.jms`)**, focused on **COA/COD delivery reports**. Audience: engineers who do not yet know IBM MQ but must operate it in a real, high-concurrency, critical environment.

## ⭐ Standing analysis mandate (NON-NEGOTIABLE — no bypass)

Every analysis, design, implementation, and test on this stack **MUST assume real-world applicability in a distributed environment: Kubernetes + microservices, average throughput ~10,000 rpm (~167 msg/s)**. Frame correlation, connection pooling, Virtual Threads, transactions, scaling, and failure modes under that topology and load — never as a single-instance toy.

## ⭐ Reference-documentation & documentation-currency rule (MANDATORY — standing user directive)

Any document, fact sheet, validated API reference, MQSC snippet, or research finding that is needed to make **future adjustments or implementations** on this stack — and that would otherwise force **re-researching the same topics** — MUST be persisted **into this repository** as durable reference documentation. The goal is **zero re-research**.

**Non-negotiable (no bypass):** keep **all** project documentation continuously current. The moment a new challenge, difficulty, pitfall, or its resolution surfaces, document it in the appropriate home (below) **before the task is considered done**. Undocumented or stale findings are a defect, not a deferral — this directive admits no exception.

- **Canonical homes:** `research-output/` for validated facts (source of truth), `docs/` for guides/runbooks.
- **Before** researching any IBM MQ / JMS / Micronaut topic for this project, check `research-output/` first.
- **After** any non-trivial research, write the result there and reference it from this `CLAUDE.md`.
- Reference docs are durable artifacts → written in **English** (per global conventions); narrative deliverables for the user are pt-BR.

## ⭐ Diagrams-as-Mermaid rule (MANDATORY — standing user directive, no bypass)

**Every diagram, in any Markdown documentation in this repo, MUST be expressed as a Mermaid diagram** (a fenced ` ```mermaid ` block) — **always**, without exception. This covers all diagram kinds: flow/sequence/architecture/state/class/ER/decision-tree/Gantt, etc.

- **Forbidden** as diagram representations in Markdown: ASCII/box-drawing art, ` ```text `/` ```bash ` "diagrams", and raster/vector image embeds used to depict a diagram that Mermaid can express. (Real screenshots/photos are not diagrams and are exempt.)
- **Applies to existing and new docs.** When touching any doc that carries a non-Mermaid diagram, convert it to Mermaid as part of that change (per the documentation-currency rule above — leaving it is a defect, not a deferral).
- **Rationale:** GitHub renders ` ```mermaid ` natively; Mermaid is diffable, reviewable, maintainable, and accessible — ASCII art is none of these.
- **Color every shape — no theme defaults.** Every node/shape (and subgraphs where meaningful) MUST have explicit colors set via `classDef` + `class` (or `style` / `A:::class`): a `fill`, a `stroke`, and a text `color`. No node may fall back to Mermaid's default theme fill. Customize edges with `linkStyle` when a non-default accent is wanted.
- **Light/dark-proof palette (same principle as the doc HTML — P20).** Colors must be eye-friendly and desaturated (no pure `#000`/`#fff`, no loud saturation) AND legible on **both** a light page (`~#f7f8fa`) and a dark page (e.g. GitHub dark `~#0d1117`). The technique that guarantees this: give each node a soft desaturated **light-to-mid-tone `fill` + dark `color`** (a colored "sticky-note" box) — because the text then contrasts the FILL (constant), not the page background, the node reads identically in light and dark; use mid-tone `stroke`s visible on both. Keep the accent family aligned to the HTML doc palette (muted slate-teal). Example:
```
classDef good fill:#d7e9d2,stroke:#5a8f63,color:#1f2430;
classDef warn fill:#f4e6c4,stroke:#b08a3e,color:#1f2430;
classDef info fill:#cfe0ef,stroke:#4a6fa5,color:#1f2430;
```
- **Animate connections when it aids comprehension** (message/flow direction), never as decoration. Mermaid **v11.3.0+** edge animation: assign an edge id and enable it — `A e1@--> B` then `e1@{ animate: true }` (shorthands `e1@fast` / `e1@slow`; or class-based `classDef flow stroke:#4a6fa5,animation:fast` + `class e1 flow`). Use sparingly. If the render target cannot play CSS/SVG animation, fall back to the **GIF strategy**. Refs: native animation https://mermaid.js.org/syntax/flowchart.html#turning-an-animation-on (mirror: https://mermaid.ai/open-source/syntax/flowchart.html#turning-an-animation-on) · GIF strategy https://yairm210.medium.com/animating-mermaid-graphs-as-gifs-2ec8f3b24fbc
- **HTML deliverable consequence:** the single-file HTML build (`docs/build-html.py` → `docs/index.html`) MUST render Mermaid blocks for the standalone output **without introducing a required external dependency** — **prefer inlining the Mermaid runtime** (vendored/fetched-once, never reproduced from memory) so edge animations play offline; if instead pre-rendering to inline SVG at build time, animated edges may not play → use the GIF fallback for those. The output must still open offline via `file://` (P17), and the diagram colors above must hold on the HTML's light background.

## ⭐ Documentation & environment policy (standing — grilled 2026-05-31, no bypass)

Detail lives in the linked ADRs/docs; these are the binding directives.

- **Language (ADR-0002):** ALL repo documentation is **English** (existing pt-BR docs get translated). The ONLY exception is `docs/index.html`, which is **bilingual pt-BR/en-US** via an in-page toggle (browser-detect default, pt-BR fallback, `localStorage`), the two languages **always content-synced** — enforced by `docs/build-html.py`'s structural **parity gate** over two sources (`docs/guide-*.md` EN canonical + `docs/i18n/` pt-BR). User *chat* stays pt-BR (global rule); durable artifacts English. **In-code comment language is ADR-0004** (not ADR-0002): new Java comments/JavaDoc/log messages are English going forward, pre-existing pt-BR grandfathered, JUnit `@DisplayName` kept pt-BR (sanctioned exception).
- **References / bibliography:** every source consulted to build or evolve this repo is recorded in the canonical **`docs/references.md`** (reference + the discovery/concept/term it grounded + link); the README summarizes+links it and `build-html.py` generates the HTML references section from it. Zero divergent copies.
- **Test-scenario docs:** every implemented test scenario is documented in the canonical **`docs/testing-scenarios.md`** (per-scenario, tiered template), cross-linked from guide + HTML. New tests follow `/tdd` and `ibmmq-jms-guide/src/test/CLAUDE.md`.
- **Execution logging:** the main implementation emits **narrated INFO step logs** (stage-tagged; `messageId`/`correlationId` via MDC) for each message/report step, with an annotated walkthrough in `docs/runbook.md`.
- **Runbook:** `docs/runbook.md` documents dependencies, configuration, runnable example flows + how to validate them, and an IBM MQ **web-console** walkthrough (`https://localhost:9443/ibmmq/console` — view QM/queues, publish, browse messages).
- **Distributed harness (ADR-0003):** the distributed environment is realized as a **local k3s** harness (IBM MQ + Java publisher/consumer microservices, consumer at N replicas = competing consumers); `floci` is a *local AWS emulator* (NOT real EKS, no IBM MQ), used only for optional AWS peripherals. Real EKS, if ever, is a separate documented reference architecture.
- **Glossary & roadmap:** `CONTEXT.md` is the domain glossary (terms only); `docs/project-status.md` tracks what's built + the ordered next steps.

## Validated facts — source of truth: `research-output/phase-a-fact-sheet.md`

Stack coordinates, MQ report/feedback constant names, MQCSP/TLS/`SET CHLAUTH` notes, and the dev container image + defaults are **sha1-verified** in the fact sheet — **check it first to avoid re-research.** Glance-level: client `com.ibm.mq.allclient:9.4.5.0` / `pooled-jms 2.0.9` / `micronaut-platform 4.9.4` (`4.9.9` does **not** exist); `MQRO_*`/`MQFB_*` live in `CMQC`/`MQConstants`, **not** `WMQConstants`; `MQFB_COA=259`, `MQFB_COD=260`, read via `WMQConstants.JMS_IBM_FEEDBACK`; container `icr.io/ibm-messaging/mq:9.4.5.0-r2` (no bare `9.4.5.0` tag).

- **Report semantics:** default id propagation `MQRO_COPY_MSG_ID_TO_CORREL_ID` makes the original MessageId the report's CorrelationId. Reports **inherit persistence from the original** (the brief's "non-persistent by default" assumption was refuted).
- **Pool tuning & topology:** `maxConnections`/`maxSessionsPerConnection` semantics, ~10k-rpm sizing (`maxConnections × replicas ≤ MAXINST`; `maxSessionsPerConnection ≤ SHARECNV`), the silent-hang default (`blockIfSessionPoolIsFull=true`/timeout `-1`), and the **role-based producer-vs-consumer factory** decision: `research-output/pooled-jms-factory-tuning.md` + `docs/adr/0006-role-based-connection-factories.md` (guide §4.1; code rewrite tracked in #25).
- **MQMD field recovery (#19):** recover six report MQMD values from the report's OWN descriptor (no producer change, no `WITH_FULL_DATA`); enable via the `queue:///<q>?mdReadEnabled=true` URI form; `byte[]` props via `getObjectProperty` (hex); GMT `PutDate`+`PutTime` → UTC with explicit `ZoneOffset.UTC`. Canonical home: `research-output/phase-f-mqmd-field-recovery.md`.

## Distributed-harness gotcha — report-PUT authority (2035)

Build/test toolchain & environment quirks (JDK 25 / Maven / mise, native-access, Testcontainers stack, the Docker-socket HTTP 400 fix, the `commons-codec` pin, sandbox) now live in **`ibmmq-jms-guide/CLAUDE.md`**.

- **Report-PUT authority gotcha (2035) — CORRECTED on live k3s:** for the QMgr to generate+deliver a COA/COD report it does a PUT-with-context onto the ReplyToQ. The authority it actually requires is **`+passid` (pass identity context)** — **not** `+setall` as previously assumed here. Verified live on a k3d cluster: with `app` granted only `+put +setall`, the report PUT still failed `AMQ8077W: ... requested permissions are unauthorized: passid` → `MQRC_NOT_AUTHORIZED (2035)` → every report silently dead-letters (report queue stays empty, COA/COD never recorded). The QMgr *passes* the original message's context into the report, so grant the full context set: `SET AUTHREC ... AUTHADD(PUT, PASSID, PASSALL, SETID, SETALL)` (see `deploy/k3s/30-mq-config.yaml`; `dspmqaut -m QM1 -n DEV.QUEUE.2 -t q -p app` confirms the effective authority). The Testcontainers IT never caught this because it connects as **`admin`/`mqm`** (`DEV.ADMIN.SVRCONN`), which already holds all context authorities. With the corrected grant the end-to-end COA(259)+COD(260) flow and cluster-wide exactly-once reconciliation were validated live (CorrelId == original MessageId).

## Applied Learning

- Agents fail silently on wrong paths. Always verify hardcoded paths.
- Before a new project artifact: check if the existing one can be extended or merged.
- orchestrate slice issues don't auto-close (umbrella branch); PostToolUse hook auto-closes on slice-merge.

## Entry points & layout

- **Distributed k3s harness (ADR-0003)** — root `Makefile`: `make up` (provision the whole k3d harness), `make verify` (AC1/AC2 evidence: competing consumers, dead-letter depth, reconciliation), `make doctor`, `make console` (MQ web console), `make down`. Manifests in `deploy/k3s/` (+ its README). Single-broker dev env: `make compose-up`.
- **`ibmmq-jms-guide/`** — runnable Micronaut module (`mvn test` unit / `mvn verify` IT); module specifics in `ibmmq-jms-guide/CLAUDE.md`.
- **`research-output/`** — validated fact sheets + raw research JSON (source of truth); **`research-prompt-ibmmq-jms-micronaut.md`** — the locked research brief.
- Primary deliverables: the Markdown guide + standalone HTML doc (pipeline-generated).
