# 0012 — Jakarta Messaging 3.0 as the primary namespace (flip from `javax.jms`; `javax` retained as a documented migration source)

Status: accepted — supersedes the `javax`-primary decision in `research-prompt-ibmmq-jms-micronaut.md` P2/P3. Implementation tracked in five tracer-bullet issues (see Consequences).

## Context

The locked research brief (`research-prompt-ibmmq-jms-micronaut.md`, items **P2/P3**) deliberately pinned
`javax.jms` (JMS 2.0, `com.ibm.mq:com.ibm.mq.allclient`) as the project's **primary** namespace, with
`com.ibm.mq.jakarta.client` / `jakarta.jms` (Jakarta Messaging 3.0) as a **dedicated evolution section** carrying a
comparison table and migration impacts. That choice propagated through the whole repository: the sha1-verified fact
sheet, the canonical guide, the README, `CONTEXT.md`, and the project memory.

The goal now is to **modernize the reference guide** so it teaches Jakarta Messaging 3.0 as the *primary* path rather
than as an appendix. The driver is **pedagogical**, not an external production constraint — which is exactly why the
javax→jakarta comparison/migration content is **kept, not purged**: for the target audience (engineers who will face
this transition in real enterprises), the migration path is high-value content, and deleting it would make the guide
*worse* at its stated job.

Technically the flip is **not** a find-and-replace. It swaps the client artifact
(`com.ibm.mq.allclient` → `com.ibm.mq.jakarta.client`), the connection pool (`pooled-jms` 2.x → 3.x, the only
`jakarta.jms` line), and the imports (`javax.jms.*` → `jakarta.jms.*`), and it **very likely** repackages
`WMQConstants` / `JmsConstants` under `com.ibm.msg.client.jakarta.*` — a fact to be **verified against the jakarta jar
bytecode, not assumed**. The integer constants (`MQRO_*`, `MQFB_*` in `CMQC`/`MQConstants`) and every COA/COD semantic
are MQ-protocol-level and namespace-independent.

## Decision

1. **Code → jakarta-only.** Depend on `com.ibm.mq:com.ibm.mq.jakarta.client:9.4.5.0` and
   `org.messaginghub:pooled-jms:3.2.2`; migrate all imports to `jakarta.jms.*`. The client version is **held at
   9.4.5.0** for parity with the broker image `icr.io/ibm-messaging/mq:9.4.5.0-r2` and the sha1-verified fact sheet —
   this is a **namespace-only change, not a version bump**. The broker image and the Testcontainers `MQContainer`
   (server-side, namespace-independent) are unchanged.

2. **Docs → jakarta primary, javax retained as a migration source.** Rewrite the guide, root README, the module README
   (`ibmmq-jms-guide/README.md`), runbook, `CLAUDE.md`, `CONTEXT.md`, `testing-scenarios.md`, and `references.md` to
   present Jakarta Messaging 3.0 as the primary path, **but keep a dedicated "Migrating from `javax.jms` (JMS 2.0)"
   section** (comparison table, import/package deltas, pool 2.x→3.x, `WMQConstants` repackage notes). The literal
   "purge all `javax`" is **rejected**. `CLAUDE.md` is a **living** doc (not a frozen historical record); its header and
   its "Validated facts" block — which summarize the fact sheet's pinned coordinates — go stale on the flip and must be
   refreshed in lockstep with the fact sheet, since `CLAUDE.md` is itself the host of the documentation-currency rule.

3. **Doc-class handling.** Frozen historical records — the research brief, `docs/handoffs/`, and prior ADRs — are
   **superseded, never rewritten**. The fact sheet (`research-output/phase-a-fact-sheet.md`) is **re-verified against
   the `com.ibm.mq.jakarta.client:9.4.5.0` bytecode** and gains a jakarta column; the javax facts are **kept** as
   verifiable migration reference (they are still true). ADR-0008's incidental `javax.jms.*` **type tokens** become
   `jakarta.jms.*` with a dated pointer note — its seam decision is unchanged — done atomically with the code slice.

4. **The reusable review skill stays namespace-agnostic** (a *separate* decision, recorded here for traceability):
   `ai/skills/jms-mq-delivery-report-analyzer` is reframed "JMS 2.0 / Jakarta Messaging 3.0" and remains drop-in for
   both javax and jakarta targets, because every one of its nine check dimensions is MQ-protocol-level. It is **not**
   coupled to this repo's flip.

5. **The Virtual Threads conclusion is namespace-independent and unchanged:** platform-thread-per-consumer + Kubernetes
   replica fan-out is the validated pattern; JEP-491 (JDK 24) removes `synchronized` pinning but the MQ client's
   **native frames still pin**, so JMS blocking I/O does not belong on virtual threads. The VT evidence IT is migrated
   to jakarta and **re-run** to confirm the pinning boundary still demonstrates against the jakarta client.

## Alternatives considered

- **Full `javax` purge (the literal request).** Rejected: it deletes the comparison/migration content that is the
  reference guide's core educational value (brief P3). The pedagogical driver argues *for* keeping it, not against.
- **Flip + bump to 9.4.5.1.** Rejected: coupling a version bump to the namespace change makes any regression
  impossible to attribute and breaks parity with the sha1-verified fact sheet and the broker image.
- **Jakarta-only skill.** Rejected: the COA/COD review is namespace-independent, so coupling the reusable asset to this
  repo's choice loses reuse for the still-common javax/JMS-2.0 enterprise shops. The skill becomes agnostic instead.
- **Rewrite the frozen brief/handoffs/ADRs to erase the javax-first phase.** Rejected: it falsifies the decision history
  and contradicts the repository's immutable-ADR policy. Supersession preserves traceability.

## Consequences

- **Five tracer-bullet implementation slices (issues):**
  1. Verify the jakarta jar package layout (`WMQConstants`/`JmsConstants`) **then** migrate code — `pom.xml` deps,
     `javax.jms.*` → `jakarta.jms.*` across main + test, ADR-0008 token update, **and the module README
     (`ibmmq-jms-guide/README.md`)** stack/build references (kept atomic with the deps it documents); green via
     `mvn verify` **and** live k3d. This slice de-risks all the others.
  2. Guide rewrite (jakarta primary + migration section) → `docs/i18n/` pt-BR mirror parity → `docs/build-html.py`
     rebuild (parity gate green).
  3. Fact sheet re-verified against the jakarta jar (+ jakarta column, javax kept), **`CLAUDE.md` header +
     "Validated facts" block refreshed in lockstep**, and `references.md` jakarta jar link.
  4. README restructured into a complete documentation hub.
  5. Namespace-agnostic skill update (Phase 0 namespace+JDK questions, VT sharpening, MQMD-recovery + async-listener
     practices folded in).
- **Verify-don't-assume:** the exact jakarta package of `WMQConstants`/`JmsConstants` must be read from the
  `com.ibm.mq.jakarta.client:9.4.5.0` bytecode before any code or doc uses it.
- **Bilingual / HTML:** flipping the guide forces the `docs/i18n/` mirror to flip too (structural parity gate) and a
  `docs/build-html.py` rebuild before the docs slice is done.
- **Edge-case scope (skill vs repo).** The agnostic skill folds in only the **namespace-independent, generic**
  discoveries (VT native-pinning, MQMD field recovery, async-listener≠pooled). Repo-specific edge cases —
  audit-schema-on-first-write (ADR-0010), the Micronaut startup-validation findings (ADR-0011), and the consume-path
  MDC-loss — are intentionally **not** in the reusable skill (it carries no project identifiers); their home is the
  guide / `research-output/` / ADRs per the currency rule. "All edge cases" is satisfied repo-side; the skill simply
  isn't their address.
- **Reversibility:** low for the code, but the historical record of the javax-first phase is deliberately preserved
  (frozen brief/handoffs + the retained migration section), so the decision is traceable in both directions.
