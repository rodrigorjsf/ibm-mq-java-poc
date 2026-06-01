# Project Status & Roadmap

A living snapshot of what exists and what is next. The domain glossary is in [`CONTEXT.md`](../CONTEXT.md); architecture decisions are in [`docs/adr/`](./adr/).

## Done
- **Fact validation (Phase A)** — IBM MQ / JMS / Micronaut facts validated against the authentic `com.ibm.mq.allclient:9.4.5.0` bytecode + IBM docs (`research-output/`).
- **Runnable project (Phase B)** — `ibmmq-jms-guide/` Micronaut app: producer, business consumer, report consumer, correlation store, pool factory; 15 unit tests; COA/COD end-to-end IT (Testcontainers + IBM `MQContainer`) verified green.
- **Guide (Phase C)** — the long-form guide (Sections 1–5 + appendices).
- **Standalone HTML (Phase D)** — single-file `docs/index.html`: fixed nav, search, copy buttons, callouts, syntax highlight, WCAG AA, full-width layout; 4 colored/animated Mermaid diagrams with the runtime inlined.
- **Java 25 runtime** — ADR-0001.
- **Test conventions** — `ibmmq-jms-guide/src/test/CLAUDE.md` (TDD-first, `@MicronautTest`, AssertJ, scope separation).

## Next (ordered — decided 2026-05-31)
1. **Documentation & deliverable layer** — translate all docs to English; make `docs/index.html` bilingual (pt-BR / en-US toggle, parity-gated); `docs/references.md` (bibliography → README + HTML); `docs/testing-scenarios.md` (every implemented test scenario); `CONTEXT.md` glossary; this file. (ADR-0002)
2. **Observability & runbook** — narrated MDC step logs through the message / report flow; a gated demo runner; `docs/runbook.md` (dependencies, configuration, runnable example flows + validation, IBM MQ web-console walkthrough).
3. **Distributed harness** — local k3s + IBM MQ + publisher / consumer microservices (N replicas = competing consumers); optional floci peripherals. (ADR-0003)
4. **TASK_2** — COA/COD MQMD field recovery (research + impl + tests).
5. **TASK_3** — integration scenario matrix + Virtual Threads + load / volumetry (runs on the k3s harness).
6. **TASK_4** — project-agnostic LLM analysis skill (`ai/skills/`).
7. **Final validation (Phase E)** — the updated acceptance checklist (bilingual HTML, English docs, references, test scenarios, runbook, execution logs).
