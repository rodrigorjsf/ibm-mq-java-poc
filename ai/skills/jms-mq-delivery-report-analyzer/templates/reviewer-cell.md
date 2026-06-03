# Reviewer-cell subagent template (parameterized)

One reusable template. Each of the four matrix cells instantiates it by filling the
placeholders below; the body, lens, and output contract are identical across cells (DRY).
Spawn one subagent per cell.

## Instantiation parameters

| Placeholder | Fill with |
| --- | --- |
| `{{CELL_ID}}` | `A`, `B`, `C`, or `D` |
| `{{PERSONA}}` | the cell's reviewer persona (e.g. "Correlation & Reconciliation reviewer") |
| `{{DIMENSIONS}}` | the numbered dimensions this cell owns (from `check-dimensions.md`) |
| `{{PHASE0_ANSWERS}}` | the resolved Phase-0 answers from `grill-me-preamble.md` |
| `{{TARGET_SCOPE}}` | the files / modules / config this cell should read |

## Spawn prompt (fill the placeholders, then send verbatim)

> You are the **{{PERSONA}}** (cell {{CELL_ID}}) reviewing an IBM MQ + JMS 2.0 /
> Jakarta Messaging 3.0 application that uses COA/COD delivery reports. You own exactly
> these check dimensions: **{{DIMENSIONS}}**. Review only those; another cell owns the
> rest. The target's namespace (javax vs jakarta) and JDK version were captured in
> Phase-0 Branch 0 and are included in {{PHASE0_ANSWERS}} — apply them to
> namespace-sensitive checks (pooled-jms version, WMQConstants package, JEP-491 boundary).
>
> **Standing assumption (apply to every finding):** the target runs distributed —
> Kubernetes + microservices, ~10,000 requests/minute (~167 messages/second) sustained,
> with consumers deployed as N competing-consumer replicas on shared queues. Rolling
> deploys and pod evictions are routine. Never reason as if there is a single JVM. The
> full baseline and throughput arithmetic are in `../references/distributed-topology.md`.
>
> **Ground truth (do not re-derive):** the validated MQRO_* / MQFB_* / JMS_IBM_Report_*
> values and report semantics are in `../references/constants.md`. The per-dimension
> what-to-look-for and distributed failure modes are in `../references/check-dimensions.md`.
> GOOD/BAD patterns are in `../references/good-bad-practices.md`. Use these as authoritative.
>
> **Phase-0 context (already gathered):** {{PHASE0_ANSWERS}}
>
> **Scope to read:** {{TARGET_SCOPE}}. Read the code and config before judging; cite
> file and symbol for every claim. If the code contradicts a Phase-0 answer, trust the code.
>
> **Output contract — emit each finding with exactly these four fields:**
>
> - **WHERE** — file + symbol (and queue/channel object when relevant). For any example
>   object you introduce, use neutral placeholders (`APP.REPORT.QUEUE`, `APP.REQUEST.QUEUE`,
>   `QUEUE.NAME`) — never a project-specific name.
> - **WHY** — the mechanism that makes it wrong/risky, grounded in the validated constants.
> - **IMPACT** — the consequence **at ~167 msg/s across N replicas**, quantified
>   (orphaned correlations per minute, reports silently dead-lettered, pinned carrier
>   threads, pool/queue exhaustion) — not "this is bad".
> - **SOLUTIONS** — one or more ordered, concrete remediations, cheapest-correct first,
>   each with its distributed trade-off named.
>
> If a dimension is clean, say so explicitly with the evidence that cleared it (a
> reviewed-and-clean dimension is a finding too). Return only findings for your assigned
> dimensions.

## Output shape each cell returns

```
## Cell {{CELL_ID}} — {{PERSONA}}

### Finding {{CELL_ID}}1 — <short title> (dimension <n>)
- WHERE: <file:symbol / config key / queue object>
- WHY:   <mechanism, grounded in a validated constant>
- IMPACT: <quantified at ~167 msg/s × N replicas>
- SOLUTIONS: <ordered remediations, cheapest-correct first>

### Dimension <n> — clean
- Evidence: <what was reviewed and why it passes>
```

The Phase-2 merge step consumes these blocks from all four cells, de-duplicates
overlapping lines, cross-links related findings, and ranks by IMPACT.
