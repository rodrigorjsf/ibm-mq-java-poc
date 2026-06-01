# References — consolidated bibliography

Canonical catalogue of **every source consulted** to build and evolve this repository. Each
entry names the source, the concept / term / discovery it grounded, and its link. This file is
the single source of truth: the standalone HTML doc's References section is **generated** from
it by `docs/build-html.py` (no divergent copies), and the root `README.md` summarises and links
it.

Sources were harvested from the bytecode-verified research in
[`research-output/phase-a-fact-sheet.md`](../research-output/phase-a-fact-sheet.md) and
[`research-output/coa-cod-validation-findings.md`](../research-output/coa-cod-validation-findings.md),
then deduplicated: recurring links (the `com.ibm.mq.allclient` jar, the `setgetweb` report
pages) are listed once with the union of the concepts they grounded.

## Primary artifacts (bytecode / POM, verified)

| Source | Grounds (concept / term / discovery) | Link |
|---|---|---|
| `com.ibm.mq.allclient` 9.4.5.0 jar (bytecode, sha1 `26c8f5cd163847990d270acf2f4f0a7f773c3bbd`) | Authoritative values for the report property fields (`JMS_IBM_REPORT_*` → mixed-case String values), the `MQRO_*` / `MQFB_*` integer constants (declared in `com.ibm.mq.constants.CMQC`, **not** `WMQConstants`), the connection properties (`WMQ_*` → `XMSC_*`), and the auth/TLS constants. Refuted `WMQ_USER_AUTHENTICATION_MQCSP` (correct field is `USER_AUTHENTICATION_MQCSP`) and `WMQ_SSL_CERT_STORES` (only `_COL` / `_STR` exist); confirmed the `WMQ_APPLICATIONNAME` vs `XMSC_WMQ_APPNAME` casing divergence. | <https://repo1.maven.org/maven2/com/ibm/mq/com.ibm.mq.allclient/9.4.5.0/com.ibm.mq.allclient-9.4.5.0.jar> |
| `com.ibm.mq.allclient` Maven metadata | Artifact existence / version line: 9.4.5.0 jar size (8,477,900 bytes), publish date (2026-01-30), `<latest>`/`<release>` = 9.4.5.1; `javax.jms` / JMS 2.0 client (POM depends on `javax.jms:javax.jms-api:2.0.1`). | <https://repo1.maven.org/maven2/com/ibm/mq/com.ibm.mq.allclient/maven-metadata.xml> |
| `com.ibm.mq.jakarta.client` 9.4.5.0 jar | The `jakarta.jms` (Jakarta Messaging 3.0) sibling client — bytecode references only `jakarta/jms/`; line begins at 9.3.0.0. Grounds the javax-vs-jakarta client split decision. | <https://repo1.maven.org/maven2/com/ibm/mq/com.ibm.mq.jakarta.client/9.4.5.0/com.ibm.mq.jakarta.client-9.4.5.0.jar> |
| `io.micronaut.platform:micronaut-platform` 4.9.4 POM | **Build-breaking discovery:** the Micronaut platform BOM for the 4.9.x line stops at **4.9.4** (then jumps to 4.10.x) — a `pom.xml` pinning 4.9.9 does **not** resolve. Only `io.micronaut:micronaut-core` 4.9.9 exists (divergent versioning). | <https://repo1.maven.org/maven2/io/micronaut/platform/micronaut-platform/4.9.4/micronaut-platform-4.9.4.pom> |
| `io.micronaut.maven:micronaut-maven-plugin` Maven metadata | Plugin coordinate (`groupId` is `io.micronaut.maven`, not `io.micronaut`); latest 5.0.0, but the 4.9.x project uses the 4.x line (latest **4.11.6**). 5.0.0 aligns with Micronaut 5 / Java 25. | <https://repo1.maven.org/maven2/io/micronaut/maven/micronaut-maven-plugin/maven-metadata.xml> |
| `io.micronaut.jms:micronaut-jms-core` 4.3.0 jar | The Micronaut JMS module is **jakarta-only** (bytecode 4.3.0 references only `jakarta/jms`), so it is correctly excluded from this javax project; the real coordinate is `micronaut-jms-core` (+ `micronaut-jms-bom`), there is no bare `micronaut-jms` artifact. | <https://repo1.maven.org/maven2/io/micronaut/jms/micronaut-jms-core/4.3.0/micronaut-jms-core-4.3.0.jar> |
| `org.messaginghub:pooled-jms` `JmsPoolConnectionFactory` source (1.2.8) | Framing correction: `pooled-jms` 1.x **and** 2.x are both `javax.jms` (latest javax is **2.0.9**, the recommended line for a new javax project); 3.x is `jakarta.jms`. FQ class `org.messaginghub.pooled.jms.JmsPoolConnectionFactory`. | <https://raw.githubusercontent.com/messaginghub/pooled-jms/1.2.8/pooled-jms/src/main/java/org/messaginghub/pooled/jms/JmsPoolConnectionFactory.java> |

## IBM MQ container & registry

| Source | Grounds (concept / term / discovery) | Link |
|---|---|---|
| `mq-container` README | The `icr.io/ibm-messaging/mq` image is **MQ Advanced for Developers**; requires `LICENSE=accept` (or `view`) or the container exits. | <https://raw.githubusercontent.com/ibm-messaging/mq-container/master/README.md> |
| `mq-container` developer-config docs | Dev-default objects (`DEV.QUEUE.1/2/3`, `DEV.DEAD.LETTER.QUEUE`, `DEV.BASE.TOPIC`), channels (`DEV.ADMIN.SVRCONN` admin-only, `DEV.APP.SVRCONN` app-only), users (`admin`, `app` in group `mqclient`); `MQ_APP_PASSWORD` / `MQ_ADMIN_PASSWORD` deprecated from v9.4.0.0 → use the `mqAdminPassword` / `mqAppPassword` secrets (min 8 chars); `MQ_DEV=false` suppresses dev-object creation. | <https://raw.githubusercontent.com/ibm-messaging/mq-container/master/docs/developer-config.md> |
| `mq-container` usage docs | Base env vars (`LICENSE`, `MQ_QMGR_NAME`); MQSC auto-config via `*.mqsc` files in `/etc/mqm` (run **once** at QMgr creation); default listener port **1414** and web console on **9443** (`/ibmmq/console`). | <https://raw.githubusercontent.com/ibm-messaging/mq-container/master/docs/usage.md> |
| `icr.io` MQ image tag list | Image tag format `9.4.<fixpack>-r<N>` (+ arch suffixes / multi-arch / `latest`); relevant tags `9.4.5.0-r1`, `9.4.5.0-r2`, `9.4.5.1-r1` — there is **no** bare `9.4.5.0` tag. | <https://icr.io/v2/ibm-messaging/mq/tags/list> |
| `com.ibm.mq:mq-java-testcontainer` Maven metadata | The official IBM Testcontainers module (class `com.ibm.mq.testcontainers.MQContainer`); there is **no** module under `org.testcontainers` for MQ. Core Testcontainers latest = 2.0.5. | <https://repo1.maven.org/maven2/com/ibm/mq/mq-java-testcontainer/maven-metadata.xml> |

## COA / COD report & feedback semantics

| Source | Grounds (concept / term / discovery) | Link |
|---|---|---|
| IBM MQ report-options reference (`q097680`, setgetweb mirror) | Report semantics: default id propagation `MQRO_COPY_MSG_ID_TO_CORREL_ID` (original MsgId → report CorrelId); COA timing (generated when the message is placed on the destination queue); COD timing (generated on destructive `MQGET`); syncpoint behaviour (COA recoverable only after producer commit, COD lost if the consumer backs out); and the **refuted** persistence claim — the report **inherits** the original's persistence ("Copied from the original message descriptor"), it is *not* non-persistent by default. | <https://setgetweb.com/p/MQ92/ref.dev/q097680_.htm> |
| IBM MQ feedback-codes reference (`q097510`, setgetweb mirror) | Feedback ranges (`MQFB_SYSTEM_FIRST`=1 … `MQFB_APPL_LAST`=999999999) and the discovery that an exception report carries an `MQRC_*` reason code in its Feedback field — there is **no** `MQFB_EXCEPTION` constant. | <https://setgetweb.com/p/MQ92/ref.dev/q097510_.htm> |
| IBM MQ report-property mappings (`q032050`, setgetweb mirror) | Cross-confirmation of the `JMS_IBM_Report_*` String property → `MQRO_*` option mappings. | <https://www.setgetweb.com/p/MQ92/dev/q032050_.htm> |
| IBM Knowledge Center `csqzaq00122` (feedback codes) | Cross-confirmation of the `MQFB_*` numeric values: `MQFB_EXPIRATION`=258, `MQFB_COA`=259, `MQFB_COD`=260, `MQFB_PAN`=275, `MQFB_NAN`=276 (so `MQFB_COA` is **not** to be confused with 271 = `MQFB_XMIT_Q_MSG_ERROR`). | <https://www.ibm.com/docs/en/ibm-mq/9.4.x?topic=mqmd-feedback-mqlong> |

## Connection, security & TLS

| Source | Grounds (concept / term / discovery) | Link |
|---|---|---|
| IBM MQ 9.4 Developing applications PDF (`mq94.develop.pdf`) | Keystore/truststore wiring (JSSE default vs `setSSLSocketFactory()`; TLS active only when a CipherSuite is set; `WMQ_SSL_SOCKET_FACTORY` for JWT/HTTPS in 9.4.5); CipherSpec ↔ CipherSuite mappings (TLS 1.2 Table 41, TLS 1.3 same name in both JREs); and the discovery that `com.ibm.mq.cfg.useIBMCipherMappings` was **removed** in IBM MQ 9.4.0 (the `SSL_`/`TLS_` prefixes are not interchangeable from Java 11). | <https://public.dhe.ibm.com/software/integration/wmq/docs/V9.4/PDFs/mq94.develop.pdf> |
| IBM MQ 9.4 Reference / administration PDF (`mq94.refadmin.pdf`) | `CHCKCLNT` values (`NONE`/`OPTIONAL`/`REQUIRED`/`REQDADM`) and the `AUTHINFO(IDPWOS)` → `ALTER QMGR CONNAUTH` → `REFRESH SECURITY TYPE(CONNAUTH)` flow; the discovery that the canonical verb is **`SET CHLAUTH`** (there is no `DEFINE CHLAUTH`), with its `TYPE`/`ACTION`/`USERSRC`/`MCAUSER` keywords; `CHLAUTH(ENABLED\|DISABLED)` QMgr attribute. | <https://public.dhe.ibm.com/software/integration/wmq/docs/V9.4/PDFs/mq94.refadmin.pdf> |
| IBM support: RC 2035 / CHLAUTH channel authentication | The CHLAUTH block-by-default mechanism (`CHLAUTH(ENABLED)` + default `USERSRC(NOACCESS)` / `BLOCKUSER('*MQADMIN')`) and how to map a remote admin (`USERMAP`/`ADDRESSMAP` with `USERSRC(MAP) MCAUSER(...)`). Grounds the report-PUT `MQRC_NOT_AUTHORIZED (2035)` gotcha. | <https://www.ibm.com/support/pages/mq-rc-2035-mqrcnotauthorized-or-amq4036-or-jmswmq2013-when-using-client-connection-mq-administrator-chlauth-channel-authentication-records> |

## MQSC syntax (object definition)

| Source | Grounds (concept / term / discovery) | Link |
|---|---|---|
| IBM MQ 9.4 `DEFINE queues` reference | Poison-message handling: `BOTHRESH(integer)` + `BOQNAME(queue-name)` are valid only on local/model queues; there is **no** "report queue" object type — reports flow via the ReplyToQ + report options. | <https://www.ibm.com/docs/en/ibm-mq/9.4.x?topic=reference-define-queues> |
| IBM MQ 9.4 `DEFINE CHANNEL` reference | `CHLTYPE(SVRCONN)` and the full set of channel types (`SDR`, `RCVR`, `SVR`, `RQSTR`, `CLNTCONN`, `CLUSSDR`, `CLUSRCVR`, `AMQP`, `MQTT`). | <https://www.ibm.com/docs/en/ibm-mq/9.4.x?topic=reference-define-channel-define-new-channel> |
| IBM MQ 9.4 `DEFINE AUTHINFO` reference | `AUTHTYPE(IDPWOS)` and the 9.4 authinfo types (`CRLLDAP`, `OCSP`, `IDPWOS`, `IDPWLDAP`). | <https://www.ibm.com/docs/en/ibm-mq/9.4.x?topic=reference-define-authinfo-define-authentication-information-object> |
| IBM MQ 9.4 `ALTER QMGR` reference | `CONNAUTH(string)` + `DEADQ(string)` settings; after a CONNAUTH change run `REFRESH SECURITY TYPE(CONNAUTH)`; DEADQ must be a local queue. | <https://www.ibm.com/docs/en/ibm-mq/9.4.x?topic=reference-alter-qmgr-alter-queue-manager-settings> |
| IBM MQ 9.4 `SET CHLAUTH` reference | The `SET CHLAUTH` record types (`BLOCKUSER`, `BLOCKADDR`, `SSLPEERMAP`, `ADDRESSMAP`, `USERMAP`, `QMGRMAP`) and their pairing keywords. | <https://www.ibm.com/docs/en/ibm-mq/9.4.x?topic=reference-set-chlauth-create-modify-channel-authentication-record> |

## JDK support & runtime

| Source | Grounds (concept / term / discovery) | Link |
|---|---|---|
| IBM MQ 9.4 — Java application dev via Maven | Confirms `com.ibm.mq.allclient.jar` is the `javax.jms` / JMS 2.0 client and the Maven-repository consumption model. | <https://www.ibm.com/docs/en/ibm-mq/9.4.x?topic=applications-java-application-development-using-maven-repository> |
| IBM MQ 9.4 — Developing JMS/Jakarta Messaging Java apps | The dedicated **Java 25** operational guidance (refuting "Java 25 not listed for 9.4.5"): `TLS_RSA_*` CipherSpecs disabled from Java 25, native-access warnings for `com.ibm.mq.allclient.jar` → run with `--enable-native-access=ALL-UNNAMED`. | <https://www.ibm.com/docs/en/ibm-mq/9.4.x?topic=applications-developing-jmsjakarta-messaging-java> |
| IBM MQ 9.4 — Running under the Java Security Manager | Refutes the "allclient breaks on JDK 24/25 via SecurityManager / JEP 486" claim — running under the Security Manager is **optional** (samples/scripts do not enable it). | <https://www.ibm.com/docs/en/ibm-mq/9.4.x?topic=icmcjm-running-mq-classes-jms-applications-under-java-security-manager> |
| IBM MQ 9.4 — Prerequisites for MQ classes for Java | Documents that the exhaustive certified-JDK matrix is deferred to the SPCR report (JS-rendered, not programmatically obtainable) — so "8/11/17/21 certified" is **not** asserted as hard fact. | <https://www.ibm.com/docs/en/ibm-mq/9.4.x?topic=java-prerequisites-mq-classes> |
| IBM support: JRE & GSKit levels bundled with IBM MQ | The bundled JRE of 9.4.5.0 = IBM Semeru 21.0.10; Semeru 21 packaged for Multiplatforms from 9.4.4 (z/OS since 9.3.0). Grounds the Java 21-support framing. | <https://www.ibm.com/support/pages/levels-jre-and-gskit-bundled-ibm-mq> |
| IBM Community blog — MQ on z/OS with Java 21 | Source for the precise Java 21 timing wording (Semeru 21 on z/OS since 9.3.0; Multiplatforms from 9.4.4) — corrects the brief's "From IBM MQ 9.4.0" phrasing. | <https://community.ibm.com/community/user/blogs/johnny-murphy/2024/12/11/mq-zos-j21> |
| Micronaut 4.0 announcement (Java 17 baseline) | Micronaut 4.x baseline is Java 17, with Java 21 (LTS) fully supported on the 4.9.x line (selectable in Launch/CLI from 4.2.0). | <https://micronaut.io/2023/02/16/micronaut-framework-4-0-with-java-17-baseline/> |
