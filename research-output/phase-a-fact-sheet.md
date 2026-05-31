# Phase A - IBM MQ / JMS 2.0 Fact Sheet (validado contra docs IBM + Maven Central)

> Gerado pelo Workflow wf13ak0b5 (5 agentes; constantes extraidas do bytecode do jar autentico, sha1 conferido).

## ⚠️ CONFLITOS / REFUTADOS / INCERTOS

Esta seção lista todo fato com veredito `refuted` ou `uncertain`, mais correções de enquadramento em fatos `confirmed` cujas `notes` contradizem a premissa do brief.

### Refutados (REFUTED)

| Fato | Premissa errada | Valor correto | Source |
|---|---|---|---|
| `micronaut-core-4.9.9-exists` | **BUILD-BREAKING.** O BOM `io.micronaut.platform:micronaut-platform:4.9.9` no `pom.xml` não resolve. | O BOM `io.micronaut.platform:micronaut-platform` para 4.9.x termina em **4.9.4** (depois pula para 4.10.x); um `pom.xml` fixando 4.9.9 **falha**. Apenas `io.micronaut:micronaut-core` 4.9.9 existe (versionamento divergente). Use o BOM em **4.9.4** (ou 4.10.x). | https://repo1.maven.org/maven2/io/micronaut/platform/micronaut-platform/4.9.4/micronaut-platform-4.9.4.pom |
| `report-persistence-inherits` | "Reports COA/COD são não-persistentes por padrão" / report pode ser não-persistente mesmo com original persistente. | **FALSO.** O report **herda** a persistência do original ("Persistence: Copied from the original message descriptor"). Original persistente → report persistente por padrão. Vale para COA/COD/exception/expiration/PAN/NAN. | https://setgetweb.com/p/MQ92/ref.dev/q097680_.htm |
| `WMQ_USER_AUTHENTICATION_MQCSP` | Campo `WMQConstants.WMQ_USER_AUTHENTICATION_MQCSP`. | **Não existe.** O campo correto é `WMQConstants.USER_AUTHENTICATION_MQCSP` (sem prefixo `WMQ_`), valor `"XMSC_USER_AUTHENTICATION_MQCSP"`, propriedade **boolean**: `cf.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true)`. Herdado de `JmsConstants`. | https://repo1.maven.org/maven2/com/ibm/mq/com.ibm.mq.allclient/9.4.5.0/com.ibm.mq.allclient-9.4.5.0.jar |
| `WMQ_SSL_CERT_STORES` | Campo `WMQConstants.WMQ_SSL_CERT_STORES`. | **Não existe campo com esse nome literal em 9.4.5.0.** Existem `WMQ_SSL_CERT_STORES_COL` (`"XMSC_WMQ_SSL_CERT_STORES_COL"`, Collection) e `WMQ_SSL_CERT_STORES_STR` (`"XMSC_WMQ_SSL_CERT_STORES_STR"`, String/URL LDAP único). | https://repo1.maven.org/maven2/com/ibm/mq/com.ibm.mq.allclient/9.4.5.0/com.ibm.mq.allclient-9.4.5.0.jar |
| `useIBMCipherMappings` | Definir `com.ibm.mq.cfg.useIBMCipherMappings` para escolher naming IBM vs Oracle. | **REMOVIDA do produto a partir de IBM MQ 9.4.0.** Um guia 9.4 **não deve** instruir defini-la. De 9.4.0 em diante o Cipher pode ser informado como CipherSpec **ou** CipherSuite e é tratado automaticamente. | https://public.dhe.ibm.com/software/integration/wmq/docs/V9.4/PDFs/mq94.develop.pdf |
| `java25-listed-for-94x` | "Java 25 NÃO está listado/certificado para MQ 9.4.5." | **Refutado.** A doc 9.4.x tem seção dedicada a **Java 25** com orientação operacional (TLS_RSA_* CipherSpecs desabilitadas a partir do Java 25; avisos de native-access para `com.ibm.mq.allclient.jar`). Ação prática ao rodar em Corretto 25: `--enable-native-access=ALL-UNNAMED` e evitar TLS_RSA_*. | https://www.ibm.com/docs/en/ibm-mq/9.4.x?topic=applications-developing-jmsjakarta-messaging-java |
| `securitymanager-jdk24-breakage` | "allclient depende do SecurityManager de modo que quebra no JDK 24/25 (JEP 486)." | **Refutado.** Rodar sob Java Security Manager é **opcional** (exige policy file; samples/scripts não o habilitam). JEP 486 não quebra o cliente. Os reais problemas em JDK 24/25 são: (a) native-access em `System.loadLibrary` → `--enable-native-access=ALL-UNNAMED`; (b) TLS_RSA_* desabilitadas no Java 25. Nenhum é relativo a SecurityManager. | https://www.ibm.com/docs/en/ibm-mq/9.4.x?topic=icmcjm-running-mq-classes-jms-applications-under-java-security-manager |

### Incertos (UNCERTAIN)

| Fato | Status | Detalhe | Source |
|---|---|---|---|
| `CHLAUTH-block-by-default` | Mecanismo confirmado, sem citação primária verbatim | Comportamento de bloqueio de admin remoto por padrão é fortemente suportado (CHLAUTH(ENABLED) + regras default `USERSRC(NOACCESS)`/`BLOCKUSER('*MQADMIN')`), mas a frase "blocked by default" não pôde ser extraída de página IBM (JS-rendered). Para liberar admin remoto: regra `USERMAP`/`ADDRESSMAP` com `USERSRC(MAP) MCAUSER(...)`, ou `ALTER QMGR CHLAUTH(DISABLED)` (não recomendado em produção). | https://www.ibm.com/support/pages/mq-rc-2035-mqrcnotauthorized-or-amq4036-or-jmswmq2013-when-using-client-connection-mq-administrator-chlauth-channel-authentication-records |
| `java21-from-940-exact-wording` | Frase exata não localizada | Java 21 **é** suportado em 9.4.x, mas o phrasing "From IBM MQ 9.4.0" não é verbatim e é parcialmente contraditado: o Semeru 21 empacotado para Multiplatforms (não-z/OS) chegou em **9.4.4**; em z/OS é suportado **desde 9.3.0**. Recomendação: dizer "9.4.x suporta Java 21 (Semeru 21 empacotado em Multiplatforms a partir de 9.4.4; z/OS desde 9.3.0)". | https://community.ibm.com/community/user/blogs/johnny-murphy/2024/12/11/mq-zos-j21 |
| `jdk-certified-matrix-8-11-17-21` | Enumeração não confirmável | A lista exaustiva de JDKs certificados por versão é deferida ao relatório SPCR (JS-rendered, não obtível programaticamente). Confirmado por fonte primária apenas: JRE empacotado de 9.4.5.0 = Semeru 21; doc referencia Java 21 e Java 25. **Não** afirmar "8/11/17/21 certificados" como fato duro sem a matriz SPCR. | https://www.ibm.com/docs/en/ibm-mq/9.4.x?topic=java-prerequisites-mq-classes |

### Correção de enquadramento (veredito `confirmed`, mas a premissa do brief está errada)

| Fato | Enquadramento do brief | Correção | Source |
|---|---|---|---|
| `MQRO-MQFB-declaring-class` | "valores MQRO_* do WMQConstants" | As constantes inteiras **MQRO_*** e **MQFB_*** são declaradas em `com.ibm.mq.constants.CMQC` (agregadas por `MQConstants`), **não** em `WMQConstants` (que declara só 1 campo, `sccsid`). Referencie como `MQConstants.MQRO_COA`. Em JMS, esses inteiros são passados nas propriedades `JMS_IBM_Report_*`. | https://repo1.maven.org/maven2/com/ibm/mq/com.ibm.mq.allclient/9.4.5.0/com.ibm.mq.allclient-9.4.5.0.jar |
| `pooledjms-namespace-by-version` | Fixar `pooled-jms` 1.x para javax | "1.x = javax.jms" está correto, **mas** 2.x também é javax.jms (verificado 2.0.9). Para um projeto javax **novo**, o projeto recomenda a linha **2.x** (mais recente 2.0.9), não 1.x. Fixar 1.x deixa você numa série mais antiga. 3.x = jakarta.jms. | https://raw.githubusercontent.com/messaginghub/pooled-jms/1.2.8/pooled-jms/src/main/java/org/messaginghub/pooled/jms/JmsPoolConnectionFactory.java |
| `WMQ_APPLICATIONNAME` | (atenção ao casing) | Nome do campo é `WMQ_APPLICATIONNAME` (uma palavra), mas o valor da propriedade é `"XMSC_WMQ_APPNAME"` (APPNAME) — o nome do campo Java e a chave de propriedade divergem na grafia. | https://repo1.maven.org/maven2/com/ibm/mq/com.ibm.mq.allclient/9.4.5.0/com.ibm.mq.allclient-9.4.5.0.jar |

---

## Cluster 1 — Maven & Micronaut

| Name | Value | Note | Source |
|---|---|---|---|
| `com.ibm.mq:com.ibm.mq.allclient` 9.4.5.0 | Existe; jar 8.477.900 bytes; sha1 `26c8f5cd163847990d270acf2f4f0a7f773c3bbd`; publicado 2026-01-30 | `<latest>`/`<release>` = **9.4.5.1** (9.4.5.0 é fixpack anterior, resolve normalmente). Cliente **javax.jms** (JMS 2.0); POM depende de `javax.jms:javax.jms-api:2.0.1` | https://repo1.maven.org/maven2/com/ibm/mq/com.ibm.mq.allclient/maven-metadata.xml |
| `com.ibm.mq:com.ibm.mq.jakarta.client` | 9.4.5.0 existe (latest/release 9.4.5.1) | Cliente **jakarta.jms** (Jakarta Messaging 3.0); bytecode referencia só `jakarta/jms/`. Linha começa em 9.3.0.0 | https://repo1.maven.org/maven2/com/ibm/mq/com.ibm.mq.jakarta.client/9.4.5.0/com.ibm.mq.jakarta.client-9.4.5.0.jar |
| `org.messaginghub:pooled-jms` | 1.x = javax (latest **2.0.9** também javax); 3.x = jakarta (latest 3.2.2) | FQ class `org.messaginghub.pooled.jms.JmsPoolConnectionFactory`. Ver correção de enquadramento acima | https://raw.githubusercontent.com/messaginghub/pooled-jms/1.2.8/.../JmsPoolConnectionFactory.java |
| `io.micronaut:micronaut-core` 4.9.9 | Existe (POM HTTP 200) | Mas o **BOM** `io.micronaut.platform:micronaut-platform` 4.9.x para em **4.9.4** (ver TOP). Micronaut 4.9.0 lançado 2025-06-30 | https://repo1.maven.org/maven2/io/micronaut/platform/micronaut-platform/4.9.4/micronaut-platform-4.9.4.pom |
| Micronaut 4.x baseline Java | Mínimo Java **17**; Java **21** suportado (selecionável em Launch/CLI a partir de 4.2.0) | Java 21 (LTS) totalmente suportado na linha 4.9.x | https://micronaut.io/2023/02/16/micronaut-framework-4-0-with-java-17-baseline/ |
| `io.micronaut.maven:micronaut-maven-plugin` | Latest geral **5.0.0**; para projeto 4.9.x usar linha **4.x** (latest **4.11.6**) | groupId é `io.micronaut.maven` (não `io.micronaut`). 5.0.0 alinha com Micronaut 5 / Java 25 | https://repo1.maven.org/maven2/io/micronaut/maven/micronaut-maven-plugin/maven-metadata.xml |
| `io.micronaut.jms` módulo 4.x | **jakarta-only** (corretamente excluído de projeto javax) | Coordenada real é `io.micronaut.jms:micronaut-jms-core` (+ `micronaut-jms-bom`); **não** existe artefato `micronaut-jms` puro. Bytecode 4.3.0: só `jakarta/jms` | https://repo1.maven.org/maven2/io/micronaut/jms/micronaut-jms-core/4.3.0/micronaut-jms-core-4.3.0.jar |
| Módulo Testcontainers para MQ | **Não há** módulo sob `org.testcontainers` para MQ | Módulo oficial IBM: `com.ibm.mq:mq-java-testcontainer` (latest 2.0.4), classe `com.ibm.mq.testcontainers.MQContainer`. Alternativa: `GenericContainer` + imagem `icr.io/ibm-messaging/mq`. Core Testcontainers latest = **2.0.5** | https://repo1.maven.org/maven2/com/ibm/mq/mq-java-testcontainer/maven-metadata.xml |
| Imagem `icr.io/ibm-messaging/mq` | IBM MQ **Advanced for Developers**; `LICENSE=accept` (ou `view`) | Image ref ex. `icr.io/ibm-messaging/mq:latest`. Container encerra se LICENSE não aceita | https://raw.githubusercontent.com/ibm-messaging/mq-container/master/README.md |
| Objetos/credenciais dev default | Queues `DEV.QUEUE.1/2/3`, `DEV.DEAD.LETTER.QUEUE` (DLQ); topic `DEV.BASE.TOPIC` (`dev/`); channels `DEV.ADMIN.SVRCONN` (só admin), `DEV.APP.SVRCONN` (só app); users `admin`, `app` (grupo `mqclient`); QMgr ex. `QM1` | A partir de v9.4.0.0, `MQ_ADMIN_PASSWORD`/`MQ_APP_PASSWORD` **deprecadas** → usar secrets `mqAdminPassword`/`mqAppPassword` (mín. 8 chars). Listener 1414, console 9443 (`/ibmmq/console`) | https://raw.githubusercontent.com/ibm-messaging/mq-container/master/docs/developer-config.md |
| Tags 9.4.x da imagem | Formato `9.4.<fixpack>-r<N>` (+ `-amd64`/`-ppc64le`/`-s390x`) + multi-arch + `latest` | Relevantes: `9.4.5.0-r1`, `9.4.5.0-r2`, `9.4.5.1-r1`. Não existe tag `9.4.5.0` "pura" | https://icr.io/v2/ibm-messaging/mq/tags/list |

---

## Cluster 2 — Constantes de report & feedback codes

**Esquema de nomes JMS:** campo Java = UPPER_SNAKE (ex. `JMS_IBM_REPORT_COA`); o **valor String** é a propriedade mixed-case (ex. `"JMS_IBM_Report_COA"`). Herdados de `com.ibm.msg.client.jms.JmsConstants` (alcançáveis via `WMQConstants`).

### Propriedades JMS (campo → valor String → mapeamento MQRO_*)

| Name (campo) | Valor String | Mapeia para | Source |
|---|---|---|---|
| `JMS_IBM_REPORT_COA` | `JMS_IBM_Report_COA` | `MQRO_COA` / `_WITH_DATA` / `_WITH_FULL_DATA` | allclient-9.4.5.0.jar |
| `JMS_IBM_REPORT_COD` | `JMS_IBM_Report_COD` | `MQRO_COD` / `_WITH_DATA` / `_WITH_FULL_DATA` | allclient-9.4.5.0.jar |
| `JMS_IBM_REPORT_EXCEPTION` | `JMS_IBM_Report_Exception` | `MQRO_EXCEPTION` / `_WITH_DATA` / `_WITH_FULL_DATA` | allclient-9.4.5.0.jar |
| `JMS_IBM_REPORT_EXPIRATION` | `JMS_IBM_Report_Expiration` | `MQRO_EXPIRATION` / `_WITH_DATA` / `_WITH_FULL_DATA` | allclient-9.4.5.0.jar |
| `JMS_IBM_REPORT_PAN` | `JMS_IBM_Report_PAN` | `MQRO_PAN` | allclient-9.4.5.0.jar |
| `JMS_IBM_REPORT_NAN` | `JMS_IBM_Report_NAN` | `MQRO_NAN` | allclient-9.4.5.0.jar |
| `JMS_IBM_REPORT_PASS_MSG_ID` | `JMS_IBM_Report_Pass_Msg_ID` | `MQRO_PASS_MSG_ID` | allclient-9.4.5.0.jar |
| `JMS_IBM_REPORT_PASS_CORREL_ID` | `JMS_IBM_Report_Pass_Correl_ID` | `MQRO_PASS_CORREL_ID` | allclient-9.4.5.0.jar |
| `JMS_IBM_REPORT_DISCARD_MSG` | `JMS_IBM_Report_Discard_Msg` | `MQRO_DISCARD_MSG` | allclient-9.4.5.0.jar |

> Casing verbatim: `Pass_Msg_ID`, `Pass_Correl_ID`, `Discard_Msg` (ID maiúsculo). Source jar: `.../9.4.5.0/com.ibm.mq.allclient-9.4.5.0.jar`. Mapeamentos cross-confirmados em https://www.setgetweb.com/p/MQ92/dev/q032050_.htm

### Valores inteiros MQRO_* (declarados em `CMQC`, não em `WMQConstants`)

| Constante | Valor decimal | Hex | Note |
|---|---|---|---|
| `MQRO_COA` | 256 | 0x100 | |
| `MQRO_COA_WITH_DATA` | 768 | 0x300 | |
| `MQRO_COA_WITH_FULL_DATA` | 1792 | 0x700 | |
| `MQRO_COD` | 2048 | 0x800 | |
| `MQRO_COD_WITH_DATA` | 6144 | 0x1800 | |
| `MQRO_COD_WITH_FULL_DATA` | 14336 | 0x3800 | |
| `MQRO_EXCEPTION` | 16777216 | 0x1000000 | |
| `MQRO_EXCEPTION_WITH_DATA` | 50331648 | 0x3000000 | |
| `MQRO_EXCEPTION_WITH_FULL_DATA` | 117440512 | 0x7000000 | |
| `MQRO_EXPIRATION` | 2097152 | 0x200000 | |
| `MQRO_EXPIRATION_WITH_DATA` | 6291456 | 0x600000 | |
| `MQRO_EXPIRATION_WITH_FULL_DATA` | 14680064 | 0xE00000 | |
| `MQRO_PAN` | 1 | 0x1 | Gerado pela app consumidora, não pelo QMgr |
| `MQRO_NAN` | 2 | 0x2 | Gerado pela app consumidora |
| `MQRO_COPY_MSG_ID_TO_CORREL_ID` | 0 | 0x0 | **Default**: MsgId original → CorrelId do report |
| `MQRO_PASS_MSG_ID` | 128 | 0x80 | |
| `MQRO_PASS_CORREL_ID` | 64 | 0x40 | |
| `MQRO_NEW_MSG_ID` | 0 | 0x0 | **Default**: novo MsgId para o report |
| `MQRO_DISCARD_MSG` | 134217728 | 0x8000000 | Descarta se indeliverable |
| `MQRO_DEAD_LETTER_Q` | 0 | 0x0 | **Default**: vai para DLQ se indeliverable (mutuamente exclusivo com DISCARD_MSG) |

> Todos extraídos do bytecode `CMQC` em allclient-9.4.5.0.jar. `WMQConstants.class` declara só 1 campo (`sccsid`).

### Feedback codes (`MQFB_*`, em `CMQC`)

| Constante | Valor | Note | Source |
|---|---|---|---|
| `MQFB_COA` | 259 | **Não** confundir com 271 (`MQFB_XMIT_Q_MSG_ERROR`) | csqzaq00122 + bytecode |
| `MQFB_COD` | 260 | | csqzaq00122 + bytecode |
| `MQFB_EXPIRATION` | 258 | | csqzaq00122 + bytecode |
| `MQFB_PAN` | 275 | | csqzaq00122 + bytecode |
| `MQFB_NAN` | 276 | | csqzaq00122 + bytecode |
| Exception (sem MQFB fixo) | reason code MQRC_* | Feedback do MQMD recebe um **MQRC_*** (ex. `MQRC_PUT_INHIBITED`, `MQRC_Q_FULL`, `MQRC_NOT_AUTHORIZED`) — não existe constante `MQFB_EXCEPTION`. Ranges: `MQFB_SYSTEM_FIRST`=1, `MQFB_SYSTEM_LAST`=65535, `MQFB_APPL_FIRST`=65536, `MQFB_APPL_LAST`=999999999 | https://setgetweb.com/p/MQ92/ref.dev/q097510_.htm |

### Semântica de report

| Tema | Valor | Source |
|---|---|---|
| Default de propagação de id | `MQRO_COPY_MSG_ID_TO_CORREL_ID` (=0) assumido: MsgId original → CorrelId do report. Para o MsgId do próprio report, default = `MQRO_NEW_MSG_ID` (=0). **CONFIRMADO** | https://setgetweb.com/p/MQ92/ref.dev/q097680_.htm |
| Timing COA | Gerado pelo QMgr dono da fila de destino quando a msg é **colocada** na fila | https://setgetweb.com/p/MQ92/ref.dev/q097680_.htm |
| Timing COD | Gerado quando a app **recupera** a msg de forma destrutiva (MQGET destrutivo). Não gerado se Format = `MQFMT_DEAD_LETTER_HEADER`; inválido para fila XCF | https://setgetweb.com/p/MQ92/ref.dev/q097680_.htm |
| Syncpoint | COA só recuperável após o **producer commitar**; COD gerado dentro da UoW do consumer, indisponível até commit — se backed out, report não é enviado | https://setgetweb.com/p/MQ92/ref.dev/q097680_.htm |
| Persistência do report | **HERDA do original** ("Copied from the original message descriptor") — ver TOP (refutado) | https://setgetweb.com/p/MQ92/ref.dev/q097680_.htm |

---

## Cluster 3 — Propriedades de conexão & segurança

### Propriedades de conexão (campo → valor String)

| Name (campo) | Valor String | Note | Source |
|---|---|---|---|
| `WMQ_CONNECTION_MODE` | `XMSC_WMQ_CONNECTION_MODE` | `setIntProperty`; em `CommonConstants` | allclient-9.4.5.0.jar |
| `WMQ_CM_CLIENT` (int) | **1** | `WMQ_CM_BINDINGS` = 0 | allclient-9.4.5.0.jar |
| `WMQ_HOST_NAME` | `XMSC_WMQ_HOST_NAME` | | allclient-9.4.5.0.jar |
| `WMQ_PORT` | `XMSC_WMQ_PORT` | `setIntProperty` (ex. 1414) | allclient-9.4.5.0.jar |
| `WMQ_CHANNEL` | `XMSC_WMQ_CHANNEL` | | allclient-9.4.5.0.jar |
| `WMQ_QUEUE_MANAGER` | `XMSC_WMQ_QUEUE_MANAGER` | | allclient-9.4.5.0.jar |
| `WMQ_CONNECTION_NAME_LIST` | `XMSC_WMQ_CONNECTION_NAME_LIST` | Admin: `CONNECTIONNAMELIST` (CRHOSTS); formato `host(port),host(port)` | allclient-9.4.5.0.jar |
| `WMQ_CCDTURL` | `XMSC_WMQ_CCDTURL` | Casing: `CCDTURL` (sem underscore antes de URL); suporta CCDT HTTPS | allclient-9.4.5.0.jar |
| `WMQ_CLIENT_RECONNECT_OPTIONS` | `XMSC_WMQ_CLIENT_RECONNECT_OPTIONS` | Admin `CLIENTRECONNECTOPTIONS` (CROPT); exige `TRANSPORT=CLIENT` + CONNAMELIST/CCDT | allclient-9.4.5.0.jar |
| `WMQ_CLIENT_RECONNECT` (int) | **16777216** (0x01000000) | = MQI `MQCNO_RECONNECT` / admin `ANY` | allclient-9.4.5.0.jar |
| `WMQ_CLIENT_RECONNECT_Q_MGR` (int) | **67108864** (0x04000000) | = `MQCNO_RECONNECT_Q_MGR` / admin `QMGR`; campo usa `Q_MGR` | allclient-9.4.5.0.jar |
| `WMQ_CLIENT_RECONNECT_AS_DEF` (int) | **0** | = `MQCNO_RECONNECT_AS_DEF` / `ASDEF`. Também `WMQ_CLIENT_RECONNECT_DISABLED`=33554432 | allclient-9.4.5.0.jar |
| `WMQ_CLIENT_RECONNECT_TIMEOUT` | `XMSC_WMQ_CLIENT_RECONNECT_TIMEOUT` | É **chave String** (recebe int em segundos), não constante int. Default documentado da propriedade = 1800s (≠ valor do constant) | allclient-9.4.5.0.jar |
| `WMQ_SHARE_CONV_ALLOWED` | `XMSC_WMQ_SHARE_CONV_ALLOWED` | | allclient-9.4.5.0.jar |
| `WMQ_APPLICATIONNAME` | `XMSC_WMQ_APPNAME` | **Atenção**: campo `APPLICATIONNAME` vs valor `APPNAME` (ver TOP) | allclient-9.4.5.0.jar |

### Autenticação / TLS

| Name | Valor | Note | Source |
|---|---|---|---|
| `USER_AUTHENTICATION_MQCSP` | `XMSC_USER_AUTHENTICATION_MQCSP` | **Sem prefixo `WMQ_`** (ver TOP); boolean; `setBooleanProperty(..., true)`. Int subjacente = 128. Herdado de `JmsConstants` | allclient-9.4.5.0.jar |
| `USERID` | `XMSC_USERID` | Herdado de `JmsConstants` | allclient-9.4.5.0.jar |
| `PASSWORD` | (`XMSC_PASSWORD` — inferido alta-confiança, não lido como ConstantValue) | Nome do campo confirmado (jar + mq-jms-spring) | allclient-9.4.5.0.jar |
| `WMQ_SSL_CIPHER_SUITE` | `XMSC_WMQ_SSL_CIPHER_SUITE` | Definir SSLCIPHERSUITE é o que **habilita TLS** no CF. Há `WMQ_SSL_CIPHER_SPEC` para CipherSpec | allclient-9.4.5.0.jar |
| `WMQ_SSL_PEER_NAME` | `XMSC_WMQ_SSL_PEER_NAME` | SSLPEER DN matching; ignorado se SSLCIPHERSUITE não definido | allclient-9.4.5.0.jar |
| `WMQ_SSL_CERT_STORES_COL` / `_STR` | `XMSC_WMQ_SSL_CERT_STORES_COL` / `_STR` | **Não existe `WMQ_SSL_CERT_STORES` puro** (ver TOP); `_STR`=URL LDAP único, `_COL`=Collection | allclient-9.4.5.0.jar |
| keystore/truststore | (1) JSSE default via `javax.net.ssl.keyStore`/`keyStorePassword` (+ trustStore); (2) custom via `MQConnectionFactory.setSSLSocketFactory()` | TLS só ativo com CipherSuite definido. Após `setSSLSocketFactory()` o CF não pode ir para JNDI. Em 9.4.5: `WMQ_SSL_SOCKET_FACTORY` (`XMSC_WMQ_SSL_SOCKET_FACTORY`) para JWT/HTTPS | https://public.dhe.ibm.com/software/integration/wmq/docs/V9.4/PDFs/mq94.develop.pdf |
| CipherSpec↔CipherSuite TLS 1.2 | CipherSpec `ECDHE_RSA_AES_128_GCM_SHA256` ↔ CipherSuite `SSL_ECDHE_RSA_WITH_AES_128_GCM_SHA256` (IBM JRE) / `TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256` (Oracle JRE) | TLS 1.2; FIPS 140-2/140-3. 256-bit: `ECDHE_RSA_AES_256_GCM_SHA384` | mq94.develop.pdf (Table 41) |
| CipherSpec↔CipherSuite TLS 1.3 | Mesmo nome nos dois JREs: `TLS_AES_128_GCM_SHA256`, `TLS_AES_256_GCM_SHA384` | Habilitados por padrão em TLS 1.3; `TLS_CHACHA20_POLY1305_SHA256` suportado mas não default. Specs genéricas: `ANY_TLS13`, `ANY_TLS12`, `ANY_TLS12_OR_HIGHER`, `ANY_TLS13_OR_HIGHER` | mq94.develop.pdf (Table 41) |
| `useIBMCipherMappings` | **REMOVIDA em 9.4.0** (ver TOP) | A partir do Java 11 os prefixos `SSL_`/`TLS_` não são intercambiáveis | mq94.develop.pdf |

### MQSC de segurança

| Item | Valor | Note | Source |
|---|---|---|---|
| `CHCKCLNT` (em AUTHINFO IDPWOS/IDPWLDAP) | `NONE` / `OPTIONAL` / `REQUIRED` / `REQDADM` | Requerer user/pass: `AUTHINFO ... AUTHTYPE(IDPWOS) CHCKCLNT(REQUIRED) ADOPTCTX(YES)` → `ALTER QMGR CONNAUTH(...)` → `REFRESH SECURITY TYPE(CONNAUTH)`. Regra CHLAUTH pode sobrepor (`ASQMGR` defere ao QMgr). `REQDADM` inválido em z/OS | mq94.refadmin.pdf |
| `SET CHLAUTH(...)` | `TYPE(BLOCKUSER \| BLOCKADDR \| SSLPEERMAP \| ADDRESSMAP \| USERMAP \| QMGRMAP)` `ACTION(ADD\|REPLACE\|REMOVE\|REMOVEALL)` | Verbo canônico é **`SET CHLAUTH`** (não há `DEFINE CHLAUTH`). `USERSRC(MAP\|NOACCESS\|CHANNEL)`, `MCAUSER(...)`. Bloquear: `TYPE(BLOCKUSER) USERLIST(*MQADMIN)`. Mapear: `TYPE(USERMAP) CLNTUSER(...) USERSRC(MAP) MCAUSER(...)`. USERMAP/ADDRESSMAP só SVRCONN; BLOCKADDR opera no listener | mq94.refadmin.pdf |
| `CHLAUTH(ENABLED\|DISABLED)` (atributo QMgr) | Liga/desliga aplicação das regras | Ver `CHLAUTH-block-by-default` no TOP (incerto) | mq94.refadmin.pdf |

---

## Cluster 4 — JDK support, MQSC & container

### JDK / runtime

| Item | Valor | Note | Source |
|---|---|---|---|
| `allclient` namespace | javax.jms / JMS 2.0 | POM depende de `javax.jms:javax.jms-api:2.0.1`; doc IBM rotula `com.ibm.mq.allclient.jar` = "JMS 2.0" | https://www.ibm.com/docs/en/ibm-mq/9.4.x?topic=applications-java-application-development-using-maven-repository |
| Java 21 em 9.4.x | **Suportado** | JRE empacotado de 9.4.5.0 (Win/Linux/AIX) = IBM Semeru 21.0.10. Semeru 21 em Multiplatforms a partir de **9.4.4**; z/OS desde 9.3.0 (ver TOP `java21-from-940-exact-wording`) | https://www.ibm.com/support/pages/levels-jre-and-gskit-bundled-ibm-mq |
| Matriz 8/11/17/21 certificada | **Não confirmável** (SPCR JS-rendered) — ver TOP | https://www.ibm.com/docs/en/ibm-mq/9.4.x?topic=java-prerequisites-mq-classes |
| Java 25 em 9.4.x | **Documentado/in-scope** (refuta "não listado") — ver TOP | https://www.ibm.com/docs/en/ibm-mq/9.4.x?topic=applications-developing-jmsjakarta-messaging-java |
| SecurityManager / JEP 486 | **Não quebra** o cliente (opcional) — ver TOP | https://www.ibm.com/docs/en/ibm-mq/9.4.x?topic=icmcjm-running-mq-classes-jms-applications-under-java-security-manager |

### MQSC (sintaxe exata)

| Comando/keyword | Valor | Note | Source |
|---|---|---|---|
| `DEFINE QLOCAL` poison-msg | `BOTHRESH(integer)` (0–999.999.999) + `BOQNAME(queue-name)` | Válidos só em local/model queues. Não há tipo de objeto "report queue" — reports vão por ReplyToQ/report options | https://www.ibm.com/docs/en/ibm-mq/9.4.x?topic=reference-define-queues |
| `DEFINE CHANNEL(...)` | `CHLTYPE(SVRCONN)` | Outros CHLTYPE: `SDR`, `RCVR`, `SVR`, `RQSTR`, `CLNTCONN`, `CLUSSDR`, `CLUSRCVR`, `AMQP`, `MQTT` | https://www.ibm.com/docs/en/ibm-mq/9.4.x?topic=reference-define-channel-define-new-channel |
| `DEFINE AUTHINFO(...)` | `AUTHTYPE(IDPWOS)` | Valores 9.4: `CRLLDAP`, `OCSP`, `IDPWOS` (via SO), `IDPWLDAP` (via LDAP) | https://www.ibm.com/docs/en/ibm-mq/9.4.x?topic=reference-define-authinfo-define-authentication-information-object |
| `ALTER QMGR` | `CONNAUTH(string)` + `DEADQ(string)` | Após mudar CONNAUTH: `REFRESH SECURITY TYPE(CONNAUTH)`. DEADQ deve ser fila local | https://www.ibm.com/docs/en/ibm-mq/9.4.x?topic=reference-alter-qmgr-alter-queue-manager-settings |
| `SET CHLAUTH` types | `BLOCKUSER`+`USERLIST`, `ADDRESSMAP`+`ADDRESS`, `USERMAP`+`CLNTUSER` | Set completo: `BLOCKUSER`, `BLOCKADDR`, `SSLPEERMAP`, `ADDRESSMAP`, `USERMAP`, `QMGRMAP` | https://www.ibm.com/docs/en/ibm-mq/9.4.x?topic=reference-set-chlauth-create-modify-channel-authentication-record |

### Container

| Item | Valor | Note | Source |
|---|---|---|---|
| Env vars base | `LICENSE=accept` (ou `view`; `LANG` idioma) + `MQ_QMGR_NAME` (ex. QM1) | Image ref `icr.io/ibm-messaging/mq:latest`; container encerra sem LICENSE | https://raw.githubusercontent.com/ibm-messaging/mq-container/master/docs/usage.md |
| `MQ_APP_PASSWORD` / `MQ_ADMIN_PASSWORD` | Existem (mín. 8 chars; setar APP_PASSWORD protege `DEV.APP.SVRCONN`) — **deprecadas a partir de v9.4.0.0** | Produção: usar secrets `mqAdminPassword`/`mqAppPassword` | https://raw.githubusercontent.com/ibm-messaging/mq-container/master/docs/developer-config.md |
| MQSC auto-config | Arquivos `*.mqsc` em `/etc/mqm` (ex. `COPY 20-config.mqsc /etc/mqm/`) | Executado **uma vez** na criação do QMgr, não a cada start | https://raw.githubusercontent.com/ibm-messaging/mq-container/master/docs/usage.md |
| Objetos dev default | `DEV.QUEUE.1/2/3`, `DEV.DEAD.LETTER.QUEUE` (DLQ), `DEV.BASE.TOPIC` (`dev/`), `DEV.ADMIN.SVRCONN`, `DEV.APP.SVRCONN`; users `admin`, `app` (grupo `mqclient`) | `MQ_DEV=false` suprime criação. Nomes case-sensitive | https://raw.githubusercontent.com/ibm-messaging/mq-container/master/docs/developer-config.md |
| Porta listener default | **1414** (sempre criada dentro do container) | Console web em **9443** (`https://<IP>:9443/ibmmq/console`). Publicar `1414:1414` (e `9443:9443`) | https://raw.githubusercontent.com/ibm-messaging/mq-container/master/docs/usage.md |

---

## Impacto nas premissas do brief

**P2 — `allclient 9.4.5.0` / Java 21:** SEM CONTRADIÇÃO. Ambos válidos: `com.ibm.mq.allclient:9.4.5.0` é artefato real (javax.jms, JMS 2.0), e Java 21 é suportado em 9.4.x (Semeru 21 empacotado). Ressalvas: 9.4.5.1 é o fixpack mais novo (9.4.5.0 resolve normalmente); a tag de imagem correspondente é `9.4.5.0-r1/r2`, não `9.4.5.0` pura.

**P6 — Constantes de report / default COPY_MSG_ID / persistência:** PARCIALMENTE CONTRADITA.
- Default `MQRO_COPY_MSG_ID_TO_CORREL_ID`: **CONFIRMADO** (valor 0, é o assumido).
- Persistência: **REFUTADA** — reports **herdam** a persistência do original; original persistente → report persistente. A crença de que COA/COD são não-persistentes por padrão está errada.
- Enquadramento "valores MQRO_* do WMQConstants": **CORRIGIDO** — MQRO_*/MQFB_* vivem em `com.ibm.mq.constants.CMQC`/`MQConstants`, não em `WMQConstants`.

**P10 — `pooled-jms` versão javax:** NÃO CONTRADITA, mas SUBÓTIMA. "1.x = javax.jms" está correto, porém a **2.x também é javax** (latest 2.0.9) e é a linha javax mantida recomendada para projeto novo. Fixar 1.x deixa numa série mais antiga, não numa errada. (3.x = jakarta.jms.)

**Runtime Corretto 25 (certificação JDK / Security Manager):** VIÁVEL com ressalvas, e duas suposições do brief são REFUTADAS.
- "Java 25 não listado para 9.4.5": **REFUTADO** — há orientação operacional dedicada na doc 9.4.x.
- "allclient quebra no JDK 24/25 por SecurityManager (JEP 486)": **REFUTADO** — SecurityManager é opcional e não habilitado pelos samples/scripts.
- Ações reais ao rodar em Corretto 25: passar `--enable-native-access=ALL-UNNAMED` (aviso de native-access em `System.loadLibrary`) e **evitar TLS_RSA_* CipherSpecs** (desabilitadas a partir do Java 25).
- A enumeração "8/11/17/21 certificados" permanece **não confirmável** por fonte primária (relatório SPCR JS-rendered) — não afirmar como fato duro.