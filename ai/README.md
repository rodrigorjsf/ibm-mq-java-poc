# AI assets

Reusable, project-agnostic AI assets for working with IBM MQ + JMS 2.0 / Jakarta
Messaging 3.0 messaging applications. Each asset is self-contained and carries no
identifiers from any particular project, so it can be copied into another repository
as-is.

## Skills

| Skill | Purpose |
| --- | --- |
| [`jms-mq-delivery-report-analyzer`](skills/jms-mq-delivery-report-analyzer/SKILL.md) | Drives a rigorous LLM review of any IBM MQ + JMS 2.0 / Jakarta Messaging 3.0 application that uses COA/COD delivery reports, reporting each finding as WHERE / WHY / IMPACT / SOLUTIONS. Judges the target against a four-cell reviewer matrix × nine check dimensions, runs a grill-me preamble (including namespace + JDK capture) before judging, and reasons throughout under a distributed (Kubernetes + microservices, high-throughput, competing-consumers) deployment lens. |

### `jms-mq-delivery-report-analyzer` layout

```
skills/jms-mq-delivery-report-analyzer/
  SKILL.md                          frontmatter + lean body + progressive-disclosure pointers
  references/
    constants.md                    validated MQRO_* / MQFB_* / JMS_IBM_Report_* values + semantics
    check-dimensions.md             the nine check dimensions, each with its distributed failure mode
    good-bad-practices.md           de-identified GOOD vs BAD catalogue per dimension
    distributed-topology.md         the k8s / ~10k rpm / competing-consumers baseline
    grill-me-preamble.md            the full Phase-0 interrogation tree
  templates/
    reviewer-cell.md                the one parameterized subagent template the four cells instantiate
```

The skill assumes the standing distributed baseline (Kubernetes + microservices,
~10,000 requests/minute ≈ ~167 messages/second, competing consumers across N replicas)
and weaves it into every check; it never reasons as a single-instance toy.
