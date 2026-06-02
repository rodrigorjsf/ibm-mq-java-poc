# 0004 — In-code comment language: English going forward, existing pt-BR grandfathered

Status: accepted (refines ADR-0002 for source code; resolves a standing contradiction)

New Java **in-code text** — inline comments, JavaDoc, and log messages — is written in **English** for any code authored from **2026-06-01** onward. Pre-existing pt-BR in-code text is **grandfathered**: it is not retroactively translated (the original wording stays recoverable via git history). The codebase is therefore expected to be **mixed-language during a transition**, and that is an accepted trade-off.

The single sanctioned exception is JUnit **`@DisplayName`**, which stays **pt-BR**, matching the existing test suite (see `ibmmq-jms-guide/src/test/CLAUDE.md`). `@DisplayName` is human-facing test-report prose for a pt-BR-reading author, not durable API documentation, so it is treated like the `docs/index.html` pt-BR view rather than like JavaDoc.

## Context

ADR-0002 mandates English for all durable **documentation** but is silent on **source-code comments**. The repository's `.claude/rules/documentation-language-and-currency.md` extended that mandate to "all Java implementation code, JavaDoc, and inline comments" without a decision record, while `src/test/CLAUDE.md` simultaneously mandates **pt-BR `@DisplayName`** — a direct, unresolved contradiction. In practice the implementation is ~100% pt-BR (production beans, `CoaCodDemoRunner`, log messages, comments). Retro-translating all of it is busywork with no behavioural value; leaving the contradiction open keeps generating fresh violations on every new file. This ADR picks the forward-only line and names the one exception explicitly.

## Consequences

- New source files and newly-added comments/JavaDoc/log strings are English; reviewers may block pt-BR in **new** in-code text (except `@DisplayName`).
- Existing pt-BR comments/JavaDoc/logs are **not** a defect and are not translated on sight; touching a line for an unrelated reason does not oblige translating its comment.
- **One-off carve-out:** PR #38 is open/unmerged, so its newly-added Java (the #11 MDC instrumentation comments and the #16 `CoaCodDemoRunner` + tests) is treated as **new**, not grandfathered, and is translated to English **before merge** under tracked follow-up issue #45. This keeps the "new vs existing" boundary unambiguous: the line is "authored before this ADR and already merged" = existing.
- `.claude/rules/documentation-language-and-currency.md` and `src/test/CLAUDE.md` are updated to cite this ADR and stop contradicting each other.
- ADR-0002 is unchanged (it remains correct for documentation); it carries a one-line pointer to this ADR for in-code scope.
