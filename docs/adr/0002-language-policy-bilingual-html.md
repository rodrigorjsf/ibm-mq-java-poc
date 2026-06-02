# 0002 — All repository documentation in English; index.html is bilingual (pt-BR / en-US)

Status: accepted (supersedes brief premise P16 "todo o resultado em português brasileiro")

All durable documentation in this repository is written in **English**, including pre-existing pt-BR docs, which are translated. The sole exception is the primary HTML deliverable `docs/index.html`, which ships **both** pt-BR and en-US, switchable via an in-page toggle (browser-detected default with pt-BR fallback, persisted in `localStorage`). The two languages must stay **content-synchronized at all times**.

This supersedes the locked brief's P16. Rationale: align with the global durable-artifacts-in-English convention and serve an international audience, while preserving the original pt-BR reading experience in the flagship HTML.

Scope note: this ADR governs **documentation**. The language of **in-code** text (Java comments, JavaDoc, log messages) and the `@DisplayName` exception are governed by **ADR-0004**.

## Consequences
- The guide has two markdown sources — `docs/guide-*.md` (English, the canonical doc) and a pt-BR source under `docs/i18n/` (the HTML's pt-BR view only). `docs/build-html.py` generates the single bilingual file from both, embedding both languages (still standalone, no external deps).
- Sync is enforced mechanically by a build-time **parity gate**: the EN and pt-BR sources (and the generated HTML) must share identical structure — same sections, callouts, code blocks, and Mermaid diagrams (labels localized per language). A structural mismatch fails the build.
- The frozen historical brief (`research-prompt-*.md`) and dated handoffs are translated too, for full consistency; the original pt-BR text remains recoverable via git history.
