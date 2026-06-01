# Research Prompt (Refined) — Java 25 / Micronaut 4 + IBM MQ integration via JMS 2.0, focused on COA/COD

> Refined version after a "grilling" session. All premises below are **locked** —
> the research must NOT reopen these decisions, only execute them with depth and technical truth.

---

## 0. Persona and Objective

Act as a **Java Solutions Architect specializing in Enterprise Messaging and IBM MQ**.
Produce a **production-grade technical guide**, from basics to advanced, on integrating a
**Java 25 / Micronaut 4.9.9** application with **IBM MQ** using **JMS 2.0**, with special emphasis on
**COA/COD delivery reports** (exhaustive reference).

**Target audience:** A Software Engineer who **does not know IBM MQ**, but must implement and operate
it in a **real, critical, high-performance** environment. Prioritize clarity, technical truth, and industry standards.

**Output language:** **Brazilian Portuguese** (see P16). **Deliverables:** (1) a **Markdown** document in PT-BR;
(2) a **modern, standalone, user-friendly HTML page** that serves as the primary documentation and introduces the content
in a **progressive and incremental** way (see P17/P20); (3) a **runnable Micronaut (Maven) project** with compilable code.
**Execution must be context-efficient**, delegating heavy blocks to specialized subagents (see P18).

---

## 1. LOCKED TECHNICAL PREMISES (do not deviate)

| #   | Premise                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
|-----|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| P1  | Runtime: **Java 25**, framework **Micronaut 4.9.9**.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| P2  | **Client library: `com.ibm.mq:com.ibm.mq.allclient:9.4.5.0`** (namespace **`javax.jms` / JMS 2.0**), `<scope>compile</scope>`, running on Java 25. **Version PINNED by the user** (it is the real dependency in use). **Queue Manager (server): also 9.4** (homogeneous client↔server pair). **Release-model note:** `9.4.5.0` belongs to the **CD (Continuous Delivery)** line — 3rd digit ≠ 0 — which delivers new features every release and has a shorter support window than the **LTS** (`9.4.0.x`). The guide must **mention this distinction** (CD vs LTS) so the reader knows the trade-off (recent features × patch/support cycle), but **keeps `9.4.5.0` as the project's official version**. Background rationale: Java 25 is only supported **from IBM MQ 9.4.0** ("From IBM MQ 9.4.0, IBM MQ supports Java 25") — which is why 9.3 was discarded. Declare the exact version in `pom.xml` and align the Testcontainers image (`icr.io/ibm-messaging/mq` on the 9.4 line) for test↔production parity.                                                                                                                                   |
| P3  | **Main namespace: `javax.jms`** with **manually-managed JMS** (create/use `JMSContext` by hand). **+ A dedicated evolution section** for **`com.ibm.mq.jakarta.client` / `jakarta.jms`** (also manual JMS), with a **comparison table**, migration impacts, and what the Jakarta namespace adds.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| P4  | Do **NOT** use the declarative module `io.micronaut.jms:micronaut-jms` (the 4.x line is **jakarta-only** and abstracts away the `JMSContext`). Micronaut comes in only for **DI, configuration (`@ConfigurationProperties`/`application.yml`), lifecycle, and injection of the `ConnectionFactory`/pool**. Explain this decision and its trade-offs (you lose the declarative `@JMSListener`; you gain the full control required by COA/COD).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| P5  | Connection in **CLIENT mode** (SVRCONN channel). HA via a **CONNAME list** (multi-host) + client **auto-reconnect**; demonstrate **CCDT** as an alternative. Mention evolution to **multi-instance QMgr / Native HA**.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| P6  | **Reports = exhaustive reference**: COA, COD, **Exception**, **Expiration**, **PAN/NAN (positive/negative action notification)**. Cover the data flags (`MQRO_*_WITH_DATA`, `MQRO_*_WITH_FULL_DATA`) and the ID-propagation flags (default **`MQRO_COPY_MSG_ID_TO_CORREL_ID`**, plus `MQRO_PASS_MSG_ID`, `MQRO_PASS_CORREL_ID`, `MQRO_NEW_MSG_ID`). Distinguish each report by its **Feedback code**. Warn about the operational consequence of `*_WITH_DATA`/`_FULL_DATA`: **payload duplication** on the report queue (sizing) and **PII exposure** — use with judgment.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| P7  | **Dedicated report queue** (e.g. `APP.REPORT.QUEUE`), pointed at via `JMSReplyTo`. A **separate dedicated consumer** reads the reports and correlates the report's **`CorrelationId`** → original **`MessageId`**.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| P8  | **Correlation store:** `ConcurrentHashMap` in the main example (didactic) **+ a dedicated topic** showing how to make it **persistent (DB/Redis)** so it survives restart/reconnect. Explain the limitation of the in-memory map (it loses correlation on restart).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| P9  | Business messages: **persistent**, **`TextMessage`** (JSON/text), **Java↔Java** flow (transparent RFH2). Interop with non-JMS apps (mainframe/COBOL/.NET, RFH2 vs MQMD/EBCDIC) is **out of scope** — include only **1 warning paragraph** flagging the topic.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| P10 | Performance: **pooling with `pooled-jms` (`JmsPoolConnectionFactory`)** + consumers on **platform threads** for the JMS I/O. **An honest Virtual Threads section**: where they help (orchestration/fan-out) × where they cause **pinning** (the MQ client's internal `synchronized` blocks), recalling that **`Session`/`JMSContext` are not thread-safe**. Also cover **async put**, **read-ahead**, and **SHARECNV**. **Sharp edge to document:** the **pool (`pooled-jms`) × auto-reconnect** interaction — a pooled connection that underwent automatic reconnection may have subtle behavior; validate pool invalidation/renewal under reconnect (P5).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| P11 | Transactions: **both, with a decision tree**. Default = **local** (transacted session, acknowledge modes, **backout + DLQ** via `BOTHRESH`/`BOQNAME`, **idempotency**). Justified exception = **XA/JTA** (manager in Micronaut, e.g. Atomikos/Narayana; 2PC between the MQ send and a database write). Show **code for both** and the selection criteria.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| P12 | Security: **a progressive spectrum** — (a) dev without TLS → (b) **user/password** via **CONNAUTH/MQCSP** → (c) **one-way TLS** → (d) **mTLS**. At each level: the channel's **MQSC**, **PKCS12 keystore/truststore**, the **`CipherSpec` (QMgr) ↔ `CipherSuite` (Java)** pairing, and **CHLAUTH** rules. Include common TLS reason codes.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| P13 | Testing: **hybrid** — **unit** (mocks/Mockito for the correlation logic) + **integration** with **Testcontainers** using the **official `icr.io/ibm-messaging/mq` image (MQ Advanced for Developers)** validating the **end-to-end COA/COD** flow, with **MQSC** setup via script/env. Warn that stand-in brokers (ActiveMQ Artemis) do **not** implement COA/COD.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| P14 | Delivery: a **Markdown document in PT-BR** + a **runnable Micronaut project with Maven (`pom.xml`)** — real, **compilable** classes from which the snippets are extracted; `docker-compose`/Testcontainers; **MQSC** files. **Code comments in PT-BR**, identifiers in English.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| P15 | **Real and correct code** (non-negotiable requirement): everything **compiles and runs**; APIs **validated against IBM documentation** (exact names of the `JMS_IBM_*`/`WMQConstants` constants, `JMSContext` signatures); **exact dependency coordinates and versions**. No pseudocode.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| P16 | **Deliverable language:** ALL of the final output (the Markdown document AND the HTML page) in **Brazilian Portuguese** — titles, body text, callouts, captions, code comments, and UI text. **Code identifiers stay in English** (convention); only the **comments** are in PT-BR. Established technical terms (Queue Manager, channel, syncpoint, etc.) may be kept in English with the PT-BR explanation on first occurrence.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| P17 | **Additional deliverable — a modern, complete HTML page:** beyond the Markdown, produce **a single standalone HTML file (single-file, embedded CSS and JS)** that reflects **all** the research and serves as the integration's **primary documentation**. Requirements: (a) **progressive/incremental onboarding** — from concept to advanced, in section order; (b) **fixed side navigation** with per-section anchors and an active-section indicator; (c) **code blocks with syntax highlight + a "copy" button**; (d) **distinct visual callouts** for `✅ Good practice`, `❌ Bad practice (what NOT to do)`, `⚠️ Caution/Sharp edge`, and `ℹ️ Note`; (e) a navigable index/table of contents and a simple search if feasible; (f) responsive; (g) **accessible (WCAG AA, contrast ≥ 4.5:1)**. It must open directly in the browser, **with no backend and no mandatory external dependencies** (highlight via custom CSS or an inlined library).                                                                                                                                                                                               |
| P18 | **Execution efficiency (protecting the main context window):** the main window acts as an **orchestrator** (keeps only this brief + an index/state), and **delegates each heavy block to specialized subagents via the `Agent` tool** (`general-purpose`/`web-researcher`/`codebase-analyst`) for: validating IBM APIs, generating the Maven project, MQSC, Testcontainers tests, and the HTML page. Subagents must **return only the final artifact/files**, not raw drafts. **Orchestration via `Agent` + task tracking (`TaskCreate`/`TaskList`)** — a mechanism confirmed available in this environment (the "workflows" feature is **not** installed here; if it ever exists, it can replace the manual chaining). Run **in parallel** whatever is independent (e.g. fact validation + project scaffolding).                                                                                                                                                                                                                                                                                                                                |
| P19 | **Validation criterion — explanatory exhaustiveness:** the document must be **fully explanatory and didactic**, **without skimping on examples**. For the critical points (connection, COA/COD, transactions, concurrency, security), provide **pairs of `✅ good practice` × `❌ bad practice (what NOT to do)` examples**, with explicit focus on **distributed high-concurrency environments (microservices)** — e.g. improper sharing of a `Session`/`JMSContext` across threads, lack of a pool, connection leaks, incorrect ack/commit under concurrency, loss of COA/COD correlation across multiple instances, reconnection, and idempotency. Each bad practice must explain **why it fails** and the **observable symptom** (e.g. reason code, leak, deadlock, throughput).                                                                                                                                                                                                                                                                                                                                                                          |
| P20 | **HTML page visual guidelines (THESE PREVAIL over the `frontend-design` skill's aesthetic bias):** **a neutral, eye-friendly palette** — avoid pure black (#000) on pure white (#fff) and overly strong/saturated tones; prefer **soft warm/cool grays** (e.g. text ~#1f2430 on a ~#fafaf8/#f7f8fa background) with **a single sober, desaturated accent color**. **Light mode as default** (dark mode optional, also low aggressive-contrast). **Consolidated typography for long documentation reading:** a legible sans-serif font for the body (e.g. **Inter**, Source Sans, system-ui) and **a mature monospaced font for code** (e.g. JetBrains Mono, Fira Code, Cascadia Code), with comfortable size/line-height (body ≥ 16px, line-height ≈ 1.6). Respect **WCAG AA** contrast. **Conflict resolution:** the `frontend-design` skill must be used **only as a code-quality/structure/subtle-microinteraction engine** — do **NOT** follow its "dominant/strong colors", "bold fonts/avoid Inter", or "memorable/maximalist aesthetic" recommendations. Reading comfort > visual impact. |

---

## 2. MANDATORY DOCUMENT STRUCTURE

### Section 1 — Fundamentals and Concepts ("the why" and "the what")

- **IBM MQ architecture:** Queue Manager (QMgr), queues (**Local, Remote, Alias, Model**), **SVRCONN channels**,
  listener/port, MCA. Include a **textual diagram** of a message's path in CLIENT mode.
- **JMS 2.0 vs native IBM MQ (MQI):** why use the JMS abstraction in Java 25; the benefits of the JMS 2.0
  simplifications (**`JMSContext`**, `JMSProducer`, `JMSConsumer`, auto-close); where the abstraction "leaks" and requires IBM extensions (
  anticipating COA/COD).
- **Inventory of MQ objects needed** for the guide: QMgr, SVRCONN channel, business (local) queue, **dedicated report
  queue**, **DLQ**, **backout queue** (`BOQNAME`/`BOTHRESH`), listener.

### Section 2 — Delivery Reports: COA / COD (and Exception/Expiration/PAN-NAN)

- **Concept:** what each report is, what it is for, **when to use and when NOT to** (cost: each report is an
  extra message; throughput impact).
- **Generation mechanics:** driven by **report options on the original message** (`Report` field /
  `JMS_IBM_Report_*`) + **`JMSReplyTo`** (ReplyToQ/ReplyToQMgr). Clarify that there is **no "turn COA/COD on at the QMgr"** — it is the
  message that requests it; describe what is actually configured at the QMgr (queues, DLQ, permissions, expiry).
- **Propagation and traceability:** explain **`MQRO_COPY_MSG_ID_TO_CORREL_ID`** (default) and the variations (`PASS_MSG_ID`,
  `PASS_CORREL_ID`, `NEW_MSG_ID`); how the **original `MessageId`** becomes the report's **`CorrelationId`**; the
  `*_WITH_DATA`/`*_WITH_FULL_DATA` flags.
- **Feedback-codes table** (MQFB_COA, MQFB_COD, MQFB_EXPIRATION, Exception codes, PAN/NAN) and how to branch in the
  consumer.
- **Timing × transaction (critical correction):** the **COA is generated when the message ARRIVES at the destination queue** and the **COD
  when it is consumed** — but, under **syncpoint/local transaction**, the message only "arrives" (and therefore the COA only flows)
  **after the producer's `commit()`**. Make this interaction with P11 explicit, because it changes the *timing* the reconciler
  observes (and what the integration tests must expect).
- **Report persistence (critical correction):** the persistence of the **report message is independent of the original
  message** — by default a report can be **non-persistent** even with a persistent original. In a critical environment that
  uses COD as **proof of delivery**, this means **losing reports on a QMgr restart**. Document how to guarantee
  report persistence/expiry (the `MQRO_PASS_*` options, report-queue persistence) and tie it to the persistent store
  of P8.
- **Flow diagram:** Producer → (COA on arrival at the queue) → Consumer → (COD on consumption) → Report Queue → Reconciler.

### Section 3 — Environment Configuration (Properties Deep Dive)

- **Exhaustive table** of connection properties (beginner → advanced): `transportType`/`connectionMode`, `hostName`,
  `port`, `channel`, `queueManager`, `connectionNameList` (multi-host CONNAME), `ccdtURL`, `clientReconnectOptions`/
  `clientReconnectTimeout`, `sharingConversations` (SHARECNV), `appName`, authentication (`userName`/`password`, MQCSP),
  TLS (`sslCipherSuite`, keystore/truststore, `sslPeerName`/`sslCertStores`), `sendCheckCount`/`receiveExit`, async put,
  read-ahead, etc.
- For **each property**: **what it does · the real default value · when to use · impact on performance/resilience**.
- Show **programmatic configuration** (`MQConnectionFactory`/`JmsConnectionFactory` + `WMQConstants`/`XMSC`) **and** via
  the **Micronaut `application.yml`** + **CCDT**.

### Section 4 — Practical Implementation (real, compilable code)

- **Micronaut bootstrap:** exact dependencies in the **`pom.xml`** (`com.ibm.mq:com.ibm.mq.allclient:9.4.5.0` with
  `<scope>compile</scope>`, `org.messaginghub:pooled-jms`), a `@Factory` that produces the `ConnectionFactory` + pool,
  `@ConfigurationProperties`.
- **Producer:** create/use `JMSContext` (try-with-resources); **enable COA/COD** via `JMS_IBM_Report_COA`/
  `JMS_IBM_Report_COD` (+ Exception/Expiration) and `JMSReplyTo`; explicit definition of headers/properties;
  persistence; record the `MessageId` in the correlation store.
- **Business consumer:** consuming the destination queue (generates COD on consumption); acknowledge/transaction.
- **Report consumer:** read the report queue, extract the **Feedback code** and **`CorrelationId`**, **correlate** with
  the original `MessageId` (in-memory store + a persistence note).

### Section 5 — Testing and Resilience (real environment)

- **Hybrid tests:** unit (mocks) + integration (Testcontainers `icr.io/ibm-messaging/mq`, setup MQSC), validating
  COA/COD end to end.
- **Resilience:** poison messages (`BOTHRESH`/`BOQNAME`/DLQ + idempotency), **auto-reconnect** (
  `clientReconnectOptions`, CONNAME list, CCDT), **local vs XA** transactions (decision tree).
- **Applied security:** progression dev → user/pass → one-way TLS → mTLS, with MQSC + PKCS12 + the
  CipherSpec↔CipherSuite pairing + CHLAUTH.
- **Virtual Threads:** an honest analysis of pinning vs gain.

### Appendices (added value)

- **Troubleshooting of common reason codes:** 2035 (NOT_AUTHORIZED), 2059 (Q_MGR_NOT_AVAILABLE), 2538 (
  HOST_NOT_AVAILABLE), 2085 (UNKNOWN_OBJECT_NAME), 2042 (OBJECT_IN_USE), 2393/2397 (SSL), with cause and fix.
- **Glossary** (QMgr, MCA, MQMD, RFH2, CCSID, CipherSpec/CipherSuite, MQSC, DLQ, BOQ, CONNAME, CCDT, SHARECNV, MQCSP,
  CHLAUTH).
- **Jakarta evolution section** (P3): a comparison table `allclient`/javax × `jakarta.client`/jakarta and the
  migration steps.
- **"Good × Bad practices in high-concurrency microservices" catalog** (P19): a collection of the ✅/❌ pairs used
  throughout the guide, consolidated into a quick-reference table (anti-patterns of shared `Session`/`JMSContext`,
  lack of a pool, leaks, ack/commit under concurrency, multi-instance COA/COD correlation, reconnection/idempotency).

### HTML deliverable (primary documentation) — see P17/P20

The HTML page is a **first-class artifact**, not a decorative attachment. It must:

- **Mirror all sections** (1–5 + appendices) with **progressive/incremental onboarding** (from "what it is" to advanced).
- Have **fixed side navigation** with anchors and active-section highlighting; a navigable **table of contents/index**; a simple search if
  feasible.
- Render **code with syntax highlight + a copy button**; **callouts** ✅ Good practice / ❌ Bad practice / ⚠️ Caution / ℹ️
  Note with distinct styles.
- Be **responsive, accessible (WCAG AA)**, and **standalone** (**single-file**, embedded CSS/JS; no backend).
- Follow a **neutral/friendly palette and reading typography** (P20). `frontend-design` comes in **only as a quality
  engine**, without dictating bold colors/fonts.
- **Execution suggestion (P18):** generate the page via a **dedicated subagent** that receives the already-consolidated content
  and returns the finished HTML file, preserving the main window.

---

## 3. STYLE GUIDELINES

1. **All output in Brazilian Portuguese** (P16); code identifiers in English, comments in PT-BR.
2. Extremely explanatory and didactic; do not omit complex details for brevity (P19).
3. Java code blocks formatted, with **comments in PT-BR on the critical lines**.
4. **Zero generalities**: if a property affects the connection, describe the exact behavior and the real default.
5. Every technical claim must be **verifiable in the IBM documentation**; cite the source when useful.
6. **Do not skimp on examples**; whenever relevant, show the `✅ good practice` × `❌ bad practice` pair.

## 3.1 VALIDATION CRITERIA (deliverable checklist)

Before considering the research complete, the result must satisfy **all** items:

- [ ] **Two artifacts delivered:** a **Markdown (PT-BR)** document + a **standalone HTML page** (P17), both reflecting
  the complete research.
- [ ] **100% in Brazilian Portuguese** (P16), including the HTML page's UI text.
- [ ] **Runnable Micronaut Maven project** with **compilable** classes from which the snippets were extracted (P14/P15).
- [ ] All the **mandatory sections** (1–5 + appendices) present and in depth.
- [ ] **COA/COD/Exception/Expiration/PAN-NAN** covered as an exhaustive reference (P6), with a Feedback-codes table.
- [ ] For each critical topic, a **good × bad practice example pair** focused on **high-concurrency microservices** (
  P19), explaining *why it fails* and the *observable symptom*.
- [ ] **HTML page** with side navigation, syntax highlight + copy, callouts (✅/❌/⚠️/ℹ️), responsive and **WCAG AA** (
  P17).
- [ ] **Neutral/friendly palette and reading typography** per P20 (no #000-on-#fff, no overly saturated colors).
- [ ] APIs and constants **validated against the IBM documentation** (P15); exact versions/coordinates declared.
- [ ] Execution done in a **context-efficient** way (P18): heavy blocks delegated to subagents; the main window only
  orchestrates.

## 4. OUT OF SCOPE (avoid scope creep)

- Interop with non-JMS apps (RFH2/MQMD/EBCDIC) beyond the warning paragraph (P9).
- Pub/Sub (topics), AMQP/MQTT, Kafka bridges.
- Advanced cluster administration beyond the HA mentioned (P5).

## 4.1 EFFICIENT EXECUTION STRATEGY (orchestration — see P18)

The research is large; the main window **must not** accumulate raw output. Recommended:

1. **Phase A — Fact validation** (subagent `web-researcher`/`general-purpose`): confirm exact versions in Maven
   Central, the names of the `JMS_IBM_*`/`WMQConstants` constants, MQSC commands, and the Testcontainers image. Returns only a fact
   sheet.
2. **Phase B — Runnable Maven project** (subagent): generate the `pom.xml`, classes (Producer, business Consumer, report
   Consumer, the pool `@Factory`, config), MQSC, and tests. Returns the file tree.
3. **Phase C — PT-BR Markdown document** (subagent or main): write the guide referencing the Phase B files.
4. **Phase D — Single-file HTML page** (subagent; `frontend-design` only as a quality engine): turn the consolidated content
   into the page (P17/P20, neutral palette prevails).
5. **Phase E — Final validation** (main): run the **§3.1** checklist.

- Orchestrate via `Agent` + `TaskCreate`/`TaskList` (the mechanism available in this environment), **parallelizing** Phases A and B
  since they are independent. The "workflows" feature is not installed here; if it comes to exist, it can chain A→E
  automatically.

## 5. FACTS ALREADY VERIFIED THIS SESSION (sources)

- Java 25 supported **from IBM MQ 9.4.0** (not on 9.3): IBM Docs — "What's changed in IBM MQ / Java 25" (
  9.4.x). → motivated discarding 9.3 and adopting **9.4 on client AND server** (homogeneous pair).
- **Production version pinned by the user: `9.4.5.0`** (the **CD** line). Declared source:
  mvnrepository.com/artifact/com.ibm.mq/com.ibm.mq.allclient. It exists in Maven Central (confirmed in the
  `maven-metadata.xml`); the global `<latest>`/`<release>` at verification time = `9.4.5.1`.
- The `9.4.0.x` line = **LTS** (Long Term Support; 3rd digit `0`); the `9.4.1`…`9.4.5` lines = **CD** (Continuous Delivery) —
  the IBM `V.R.M.F` release model. The project uses **CD `9.4.5.0`**; the guide must explain the difference in support window
  vs LTS.
- Client↔QMgr compatibility: any supported client connects to any supported QMgr (IBM — "compatibility
  between MQ client and queue manager"); we use a **homogeneous 9.4↔9.4 pair**, eliminating channel-negotiation caveats.
- `com.ibm.mq.allclient` = `javax.jms`; `com.ibm.mq.jakarta.client` = `jakarta.jms` (both from MQ 9.3.0): IBM
  Docs — "IBM MQ classes for Jakarta Messaging: an overview".
- Micronaut 4.x migrated JMS to `jakarta.jms` (the `micronaut-jms` 4.x module is jakarta-only): Micronaut JMS 4.x guide / "
  Upgrade to Micronaut Framework 4".
- `frontend-design` installed (`claude-plugins-official`); the "workflows" feature is **not** installed in this environment (
  verified in `~/.claude/plugins`).
