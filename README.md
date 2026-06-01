# ibm-mq-java-poc

Production-grade proof of concept for integrating **Java 25 / Micronaut 4** with **IBM MQ** over **JMS 2.0 (`javax.jms`)**, with deep focus on **COA / COD delivery reports** (Confirmation On Arrival / Confirmation On Delivery).

The repository ships three things: a **runnable Maven project**, an **exhaustive technical guide** (canonical English; the original Brazilian-Portuguese text is retained under `docs/i18n/` as the standalone HTML's pt-BR source), and the **validated research** the implementation is built on.

## Stack

| Component | Version |
| --- | --- |
| Java (target) | 25 (`maven.compiler.release=25`, LTS) |
| Framework | Micronaut 4.9.4 (`io.micronaut.platform:micronaut-platform`) |
| IBM MQ client | `com.ibm.mq:com.ibm.mq.allclient:9.4.5.0` (`javax.jms` / JMS 2.0) |
| JMS connection pool | `org.messaginghub:pooled-jms:2.0.9` |
| Integration test | Testcontainers 2.0.5 + IBM `com.ibm.mq:mq-java-testcontainer:2.0.3` (`MQContainer`) |
| Broker image | `icr.io/ibm-messaging/mq:9.4.5.0-r2` (MQ Advanced for Developers) |

Micronaut is used only for dependency injection / configuration / lifecycle; JMS is managed by hand (`JMSContext`) to retain full control over COA/COD.

## Repository layout

```
ibmmq-jms-guide/                       runnable Micronaut + IBM MQ Maven project
  src/main/java/com/example/ibmmq/      @Factory pool, producer, business + report consumers,
                                        correlation store, feedback router
  src/test/java/.../integration/        CoaCodEndToEndIT — end-to-end COA/COD against a real broker
  mqsc/                                 production-style MQSC (channel/auth, queues, DLQ, backout)
  docker-compose.yml
docs/
  guide-ibmmq-jms-micronaut.md          canonical technical guide (English, ~9.4k words)
  i18n/guia-ibmmq-jms-micronaut.md      pt-BR source feeding the standalone HTML
  handoffs/                             session handoff notes
research-output/                        bytecode-verified fact sheet + raw research
research-prompt-ibmmq-jms-micronaut.md  the original brief
ai/
  skills/jms-mq-delivery-report-analyzer/  project-agnostic COA/COD review skill (matrix × dimensions)
CLAUDE.md                               project guide: validated facts, env quirks, reference-doc rule
```

## Quick start

Requires JDK 25+ (Amazon Corretto 25; `maven.compiler.release=25`), Maven 3.9+, and Docker (for the integration test).

```bash
cd ibmmq-jms-guide

# Unit tests (no broker needed)
mvn test

# Full end-to-end: spins up IBM MQ via Testcontainers and verifies COA + COD
mvn verify
```

On JDK 24+/25 the MQ client needs `--enable-native-access=ALL-UNNAMED` (already wired into the Surefire/Failsafe `argLine`).

## What the COA/COD demo proves

A producer sends a persistent `TextMessage` requesting **COA** (confirm on arrival) and **COD** (confirm on delivery), with `JMSReplyTo` pointing at a dedicated report queue. A business consumer destructively reads the message (triggering COD); a report consumer reads the report queue, branches on the **feedback code** (`MQFB_COA = 259`, `MQFB_COD = 260`), and correlates `report.JMSCorrelationID → original MessageId` (default `MQRO_COPY_MSG_ID_TO_CORREL_ID`). The integration test asserts both reports arrive with matching correlation.

## Gotchas captured (full detail in the guide §5 and `CLAUDE.md`)

- **Report-PUT authority (2035):** a low-privilege user lacks context authority (`+setall`), so the queue manager's COA/COD report PUT fails with `MQRC_NOT_AUTHORIZED (2035)` and the report silently lands on the DLQ. Grant `SET AUTHREC ... AUTHADD(PUT, SETALL)`.
- **Report persistence** is inherited from the original message — it is *not* non-persistent by default.
- **Testcontainers + modern Docker:** use Testcontainers 2.x (older docker-java fails against Docker engine 29.x); pin `commons-codec:1.16.1` for the zerodep transport.

## References

Every source consulted to build and evolve this repository is catalogued in the canonical bibliography [`docs/references.md`](docs/references.md) — each entry names the source, the concept/term/discovery it grounded, and its link, grouped by topic (primary jar/POM artifacts, container & registry, COA/COD report semantics, connection & security, MQSC, and JDK support). It is the single source of truth: the standalone HTML doc's References section is **generated** from it by `docs/build-html.py`, so the two never diverge.

Key primary sources: the bytecode-verified [`com.ibm.mq.allclient:9.4.5.0` jar](https://repo1.maven.org/maven2/com/ibm/mq/com.ibm.mq.allclient/9.4.5.0/com.ibm.mq.allclient-9.4.5.0.jar) (report/feedback constants), the [IBM MQ report-options reference](https://setgetweb.com/p/MQ92/ref.dev/q097680_.htm) (COA/COD semantics + persistence), and the [`micronaut-platform:4.9.4` POM](https://repo1.maven.org/maven2/io/micronaut/platform/micronaut-platform/4.9.4/micronaut-platform-4.9.4.pom) (the build-breaking 4.9.9 discovery).

## AI assets

[`ai/`](ai/README.md) holds reusable, project-agnostic AI assets. The first is the
[`jms-mq-delivery-report-analyzer`](ai/skills/jms-mq-delivery-report-analyzer/SKILL.md)
skill — it drives a rigorous LLM review of *any* IBM MQ + JMS 2.0 COA/COD application
(four-cell reviewer matrix × nine check dimensions, a grill-me preamble, and the
distributed ~10k-rpm lens), reporting each finding as WHERE / WHY / IMPACT / SOLUTIONS.
It carries no repo-specific identifiers and drops cleanly into any messaging codebase.

## Documentation language

The in-depth guide under `docs/` is written in **Brazilian Portuguese** by design. Code identifiers are English; code comments are pt-BR.

## License

[MIT](LICENSE) © 2026 Rodrigo Jorge de Santana França
