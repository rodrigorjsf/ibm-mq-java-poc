# Prompt de Pesquisa (Refinado) — Integração Java 25 / Micronaut 4 + IBM MQ via JMS 2.0, com foco em COA/COD

> Versão refinada após sessão de "grilling". Todas as premissas abaixo estão **travadas** —
> a pesquisa NÃO deve reabrir essas decisões, apenas executá-las com profundidade e verdade técnica.

---

## 0. Persona e Objetivo

Atue como um **Arquiteto de Soluções Java especialista em Mensageria Corporativa e IBM MQ**.
Gere um **guia técnico de nível de produção**, do básico ao avançado, sobre integrar uma aplicação
**Java 25 / Micronaut 4.9.9** ao **IBM MQ** usando **JMS 2.0**, com ênfase especial em
**relatórios de entrega COA/COD** (referência exaustiva).

**Público-alvo:** Engenheiro de Software que **não domina IBM MQ**, mas precisa implementar e operar
isso em ambiente **real, crítico e de alta performance**. Priorize clareza, verdade técnica e padrões de mercado.

**Idioma do resultado:** **português brasileiro** (ver P16). **Entregáveis:** (1) documento **Markdown** em PT-BR;
(2) **página HTML moderna, standalone e amigável** que sirva como documentação principal e introduza o conteúdo
de forma **progressiva e incremental** (ver P17/P20); (3) **projeto Micronaut (Maven) runnable** com código compilável.
**A execução deve ser eficiente em uso de contexto**, delegando blocos pesados a subagents especializados (ver P18).

---

## 1. PREMISSAS TÉCNICAS TRAVADAS (não desviar)

| #   | Premissa                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
|-----|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| P1  | Runtime: **Java 25**, framework **Micronaut 4.9.9**.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| P2  | **Biblioteca cliente: `com.ibm.mq:com.ibm.mq.allclient:9.4.5.0`** (namespace **`javax.jms` / JMS 2.0**), `<scope>compile</scope>`, rodando em Java 25. **Versão CRAVADA pelo usuário** (é a dependência real em uso). **Queue Manager (servidor): também 9.4** (par homogêneo cliente↔servidor). **Nota de modelo de release:** `9.4.5.0` pertence à linha **CD (Continuous Delivery)** — 3º dígito ≠ 0 — que entrega features novas a cada release e tem janela de suporte mais curta que a **LTS** (`9.4.0.x`). O guia deve **mencionar essa distinção** (CD vs LTS) para que o leitor saiba o trade-off (features recentes × ciclo de patch/suporte), mas **mantém `9.4.5.0` como versão oficial do projeto**. Justificativa de fundo: Java 25 só é suportado **a partir do IBM MQ 9.4.0** ("From IBM MQ 9.4.0, IBM MQ supports Java 25") — por isso 9.3 foi descartado. Declarar a versão exata no `pom.xml` e alinhar a imagem Testcontainers (`icr.io/ibm-messaging/mq` na linha 9.4) para paridade teste↔produção.                                                                                                                                   |
| P3  | **Namespace principal: `javax.jms`** com **JMS gerenciado manualmente** (criar/usar `JMSContext` à mão). **+ Seção dedicada de evolução** para **`com.ibm.mq.jakarta.client` / `jakarta.jms`** (também JMS manual), com **tabela comparativa**, impactos de migração e o que o namespace Jakarta agrega.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| P4  | **NÃO** usar o módulo declarativo `io.micronaut.jms:micronaut-jms` (linha 4.x é **jakarta-only** e abstrai o `JMSContext`). Micronaut entra apenas com **DI, configuração (`@ConfigurationProperties`/`application.yml`), ciclo de vida e injeção do `ConnectionFactory`/pool**. Explicar essa decisão e seus trade-offs (perde-se `@JMSListener` declarativo; ganha-se controle total exigido por COA/COD).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| P5  | Conexão em **CLIENT mode** (canal SVRCONN). HA via **lista de CONNAME** (multi-host) + **auto-reconnect** do cliente; demonstrar **CCDT** como alternativa. Citar evolução para **multi-instance QMgr / Native HA**.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| P6  | **Relatórios = referência exaustiva**: COA, COD, **Exception**, **Expiration**, **PAN/NAN (positive/negative action notification)**. Cobrir flags de dados (`MQRO_*_WITH_DATA`, `MQRO_*_WITH_FULL_DATA`) e de propagação de ID (default **`MQRO_COPY_MSG_ID_TO_CORREL_ID`**, além de `MQRO_PASS_MSG_ID`, `MQRO_PASS_CORREL_ID`, `MQRO_NEW_MSG_ID`). Distinguir cada report pelo **Feedback code**. Alertar a consequência operacional de `*_WITH_DATA`/`_FULL_DATA`: **duplicação do payload** na fila de report (sizing) e **exposição de PII** — usar com critério.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| P7  | **Fila de report dedicada** (ex.: `APP.REPORT.QUEUE`), apontada via `JMSReplyTo`. Um **consumidor próprio separado** lê os reports e correlaciona **`CorrelationId` do report → `MessageId` original**.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| P8  | **Store de correlação:** `ConcurrentHashMap` no exemplo principal (didático) **+ tópico dedicado** mostrando como torná-lo **persistente (DB/Redis)** para sobreviver a restart/reconexão. Explicar a limitação do mapa em memória (perde correlação ao reiniciar).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| P9  | Mensagens de negócio: **persistentes**, **`TextMessage`** (JSON/texto), fluxo **Java↔Java** (RFH2 transparente). Interop com app não-JMS (mainframe/COBOL/.NET, RFH2 vs MQMD/EBCDIC) **fora de escopo** — incluir apenas **1 parágrafo de alerta** apontando o tema.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| P10 | Performance: **pooling com `pooled-jms` (`JmsPoolConnectionFactory`)** + consumidores em **threads de plataforma** para o I/O JMS. **Seção honesta de Virtual Threads**: onde ajudam (orquestração/fan-out) × onde causam **pinning** (blocos `synchronized` internos do cliente MQ), lembrando que **`Session`/`JMSContext` não são thread-safe**. Cobrir também **async put**, **read-ahead** e **SHARECNV**. **Sharp edge a documentar:** interação **pool (`pooled-jms`) × auto-reconnect** — uma conexão do pool que sofreu reconexão automática pode ter comportamento sutil; validar invalidação/renovação no pool sob reconnect (P5).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| P11 | Transações: **ambas, com árvore de decisão**. Default = **locais** (sessão transacted, acknowledge modes, **backout + DLQ** via `BOTHRESH`/`BOQNAME`, **idempotência**). Exceção justificada = **XA/JTA** (gerenciador no Micronaut, ex.: Atomikos/Narayana; 2PC entre envio MQ e gravação em banco). Mostrar **código dos dois** e critérios de escolha.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| P12 | Segurança: **espectro progressivo** — (a) dev sem TLS → (b) **user/password** via **CONNAUTH/MQCSP** → (c) **TLS one-way** → (d) **mTLS**. Em cada nível: **MQSC** do canal, **keystore/truststore PKCS12**, **pareamento `CipherSpec` (QMgr) ↔ `CipherSuite` (Java)** e regras **CHLAUTH**. Incluir reason codes de TLS comuns.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| P13 | Testes: **híbrido** — **unit** (mocks/Mockito p/ a lógica de correlação) + **integração** com **Testcontainers** usando a **imagem oficial `icr.io/ibm-messaging/mq` (MQ Advanced for Developers)** validando o fluxo **COA/COD ponta a ponta**, com setup **MQSC** via script/env. Avisar que brokers stand-in (ActiveMQ Artemis) **não** implementam COA/COD.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| P14 | Entrega: **documento Markdown em PT-BR** + **projeto Micronaut runnable com Maven (`pom.xml`)** — classes reais e **compiláveis** de onde os snippets são extraídos; `docker-compose`/Testcontainers; arquivos **MQSC**. **Comentários de código em PT-BR**, identificadores em inglês.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| P15 | **Código real e correto** (requisito inegociável): tudo **compila e roda**; APIs **validadas contra a documentação IBM** (nomes exatos de constantes `JMS_IBM_*`/`WMQConstants`, assinaturas do `JMSContext`); **coordenadas e versões exatas** de dependência. Sem pseudocódigo.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| P16 | **Idioma do entregável:** TODO o resultado final (documento Markdown E página HTML) em **português brasileiro** — títulos, texto corrido, callouts, legendas, comentários de código e textos da UI. **Identificadores de código permanecem em inglês** (convenção); apenas os **comentários** são em PT-BR. Termos técnicos consagrados (Queue Manager, channel, syncpoint, etc.) podem ser mantidos em inglês com a explicação em PT-BR na primeira ocorrência.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| P17 | **Entregável adicional — página HTML moderna e completa:** além do Markdown, gerar **um único arquivo HTML standalone (single-file, CSS e JS embutidos)** que reflita **toda** a pesquisa e sirva como **documentação principal** da integração. Requisitos: (a) **onboarding progressivo/incremental** — do conceito ao avançado, na ordem das seções; (b) **navegação lateral fixa** com âncoras por seção e indicador de seção ativa; (c) **blocos de código com syntax highlight + botão "copiar"**; (d) **callouts visuais distintos** para `✅ Boa prática`, `❌ Má prática (o que NÃO fazer)`, `⚠️ Atenção/Sharp edge` e `ℹ️ Nota`; (e) índice/sumário navegável e busca simples se viável; (f) responsiva; (g) **acessível (WCAG AA, contraste ≥ 4.5:1)**. Deve abrir direto no navegador, **sem backend e sem dependências externas obrigatórias** (highlight via CSS próprio ou lib embutida inline).                                                                                                                                                                                                                                               |
| P18 | **Eficiência de execução (proteção da janela de contexto principal):** a janela principal atua como **orquestradora** (mantém só este brief + índice/estado), e **delega cada bloco pesado a subagents especializados via ferramenta `Agent`** (`general-purpose`/`web-researcher`/`codebase-analyst`) para: validação de APIs IBM, geração do projeto Maven, MQSC, testes Testcontainers e a página HTML. Subagents devem **retornar apenas o artefato final/arquivos**, não rascunhos brutos. **Orquestração via `Agent` + task tracking (`TaskCreate`/`TaskList`)** — mecanismo confirmado como disponível neste ambiente (a feature "workflows" **não** está instalada aqui; se vier a existir, pode substituir o encadeamento manual). Rodar **em paralelo** o que for independente (ex.: validação de fatos + scaffolding do projeto).                                                                                                                                                                                                                                                                                                                |
| P19 | **Critério de validação — exaustividade explicativa:** o documento deve ser **totalmente explanatório e didático**, **sem economizar em exemplos**. Para os pontos críticos (conexão, COA/COD, transações, concorrência, segurança), fornecer **pares de exemplos `✅ boa prática` × `❌ má prática (o que NÃO fazer)`**, com foco explícito em **ambientes distribuídos de alta concorrência (microsserviços)** — ex.: compartilhamento indevido de `Session`/`JMSContext` entre threads, ausência de pool, vazamento de conexões, ack/commit incorretos sob concorrência, perda de correlação COA/COD em múltiplas instâncias, reconexão e idempotência. Cada má prática deve explicar **por que falha** e o **sintoma observável** (ex.: reason code, leak, deadlock, throughput).                                                                                                                                                                                                                                                                                                                                                                         |
| P20 | **Diretrizes visuais da página HTML (PREVALECEM sobre o viés estético da skill `frontend-design`):** **paleta neutra e amigável aos olhos** — evitar preto puro (#000) sobre branco puro (#fff) e tonalidades muito fortes/saturadas; preferir **cinzas quentes/frios suaves** (ex.: texto ~#1f2430 sobre fundo ~#fafaf8/#f7f8fa) com **uma cor de acento sóbria** e dessaturada. **Modo claro como padrão** (modo escuro opcional, também de baixo contraste agressivo). **Tipografia consolidada para leitura longa de documentação:** fonte sem serifa legível para corpo (ex.: **Inter**, Source Sans, system-ui) e **fonte monoespaçada madura para código** (ex.: JetBrains Mono, Fira Code, Cascadia Code), com tamanho/altura de linha confortáveis (corpo ≥ 16px, line-height ≈ 1.6). Respeitar contraste **WCAG AA**. **Resolução de conflito:** a skill `frontend-design` deve ser usada **apenas como motor de qualidade de código/estrutura/microinterações sutis** — **NÃO** seguir suas recomendações de "cores dominantes/fortes", "fontes ousadas/evitar Inter" ou "estética memorável/maximalista". Conforto de leitura > impacto visual. |

---

## 2. ESTRUTURA OBRIGATÓRIA DO DOCUMENTO

### Seção 1 — Fundamentos e Conceitos ("o porquê" e "o que é")

- **Arquitetura IBM MQ:** Queue Manager (QMgr), filas (**Local, Remote, Alias, Model**), **canais SVRCONN**,
  listener/porta, MCA. Incluir **diagrama textual** do caminho de uma mensagem em CLIENT mode.
- **JMS 2.0 vs IBM MQ nativo (MQI):** por que usar a abstração JMS em Java 25; benefícios das simplificações do JMS
  2.0 (**`JMSContext`**, `JMSProducer`, `JMSConsumer`, auto-close); onde a abstração "vaza" e exige extensões IBM (
  antecipa COA/COD).
- **Inventário de objetos MQ necessários** para o guia: QMgr, canal SVRCONN, fila de negócio (local), **fila de report
  dedicada**, **DLQ**, **backout queue** (`BOQNAME`/`BOTHRESH`), listener.

### Seção 2 — Relatórios de Entrega: COA / COD (e Exception/Expiration/PAN-NAN)

- **Conceito:** o que é cada report, para que serve, **quando usar e quando NÃO** (custo: cada report é uma mensagem
  extra; impacto em throughput).
- **Mecânica de geração:** dirigida por **opções de report na mensagem original** (`Report` field /
  `JMS_IBM_Report_*`) + **`JMSReplyTo`** (ReplyToQ/ReplyToQMgr). Esclarecer que **não há "ligar COA/COD no QMgr"** — é a
  mensagem que pede; descrever o que de fato se configura no QMgr (filas, DLQ, permissões, expiry).
- **Propagação e rastreabilidade:** explicar **`MQRO_COPY_MSG_ID_TO_CORREL_ID`** (default) e variações (`PASS_MSG_ID`,
  `PASS_CORREL_ID`, `NEW_MSG_ID`); como o **`MessageId` original** vira o **`CorrelationId`** do report; flags
  `*_WITH_DATA`/`*_WITH_FULL_DATA`.
- **Tabela de Feedback codes** (MQFB_COA, MQFB_COD, MQFB_EXPIRATION, codes de Exception, PAN/NAN) e como ramificar no
  consumidor.
- **Timing × transação (correção crítica):** o **COA é gerado quando a mensagem CHEGA à fila de destino** e o **COD
  quando ela é consumida** — mas, sob **syncpoint/transação local**, a mensagem só "chega" (e portanto o COA só flui) *
  *após o `commit()`** do produtor. Explicitar essa interação com P11, pois ela muda o *timing* que o reconciler
  observa (e o que os testes de integração devem esperar).
- **Persistência dos reports (correção crítica):** a persistência da **mensagem de report é independente da mensagem
  original** — por padrão um report pode ser **não-persistente** mesmo com original persistente. Em ambiente crítico que
  usa COD como **prova de entrega**, isso significa **perder reports em restart do QMgr**. Documentar como garantir
  persistência/expiry dos reports (opções `MQRO_PASS_*`, persistência da fila de report) e amarrar com o store
  persistente do P8.
- **Diagrama do fluxo:** Producer → (COA na chegada à fila) → Consumer → (COD ao consumir) → Report Queue → Reconciler.

### Seção 3 — Configuração do Ambiente (Deep Dive de Propriedades)

- **Tabela exaustiva** das propriedades de conexão (iniciante → avançado): `transportType`/`connectionMode`, `hostName`,
  `port`, `channel`, `queueManager`, `connectionNameList` (CONNAME multi-host), `ccdtURL`, `clientReconnectOptions`/
  `clientReconnectTimeout`, `sharingConversations` (SHARECNV), `appName`, autenticação (`userName`/`password`, MQCSP),
  TLS (`sslCipherSuite`, keystore/truststore, `sslPeerName`/`sslCertStores`), `sendCheckCount`/`receiveExit`, async put,
  read-ahead, etc.
- Para **cada propriedade**: **o que faz · valor default real · quando usar · impacto em performance/resiliência**.
- Mostrar **configuração programática** (`MQConnectionFactory`/`JmsConnectionFactory` + `WMQConstants`/`XMSC`) **e** via
  **`application.yml` do Micronaut** + **CCDT**.

### Seção 4 — Implementação Prática (código real, compilável)

- **Bootstrap Micronaut:** dependências exatas no **`pom.xml`** (`com.ibm.mq:com.ibm.mq.allclient:9.4.5.0` com
  `<scope>compile</scope>`, `org.messaginghub:pooled-jms`), `@Factory` que produz `ConnectionFactory` + pool,
  `@ConfigurationProperties`.
- **Producer:** criar/usar `JMSContext` (try-with-resources); **habilitar COA/COD** via `JMS_IBM_Report_COA`/
  `JMS_IBM_Report_COD` (+ Exception/Expiration) e `JMSReplyTo`; definição explícita de headers/propriedades;
  persistência; registrar o `MessageId` no store de correlação.
- **Consumer de negócio:** consumo da fila de destino (gera COD ao consumir); acknowledge/transação.
- **Consumer de reports:** ler a fila de report, extrair **Feedback code** e **`CorrelationId`**, **correlacionar** com
  o `MessageId` original (store em memória + nota de persistência).

### Seção 5 — Testes e Resiliência (ambiente real)

- **Testes híbridos:** unit (mocks) + integração (Testcontainers `icr.io/ibm-messaging/mq`, MQSC de setup), validando
  COA/COD ponta a ponta.
- **Resiliência:** poison messages (`BOTHRESH`/`BOQNAME`/DLQ + idempotência), **auto-reconnect** (
  `clientReconnectOptions`, CONNAME list, CCDT), transações **locais vs XA** (árvore de decisão).
- **Segurança aplicada:** progressão dev → user/pass → TLS one-way → mTLS, com MQSC + PKCS12 + pareamento
  CipherSpec↔CipherSuite + CHLAUTH.
- **Virtual Threads:** análise honesta de pinning vs ganho.

### Apêndices (valor agregado)

- **Troubleshooting de reason codes** comuns: 2035 (NOT_AUTHORIZED), 2059 (Q_MGR_NOT_AVAILABLE), 2538 (
  HOST_NOT_AVAILABLE), 2085 (UNKNOWN_OBJECT_NAME), 2042 (OBJECT_IN_USE), 2393/2397 (SSL), com causa e correção.
- **Glossário** (QMgr, MCA, MQMD, RFH2, CCSID, CipherSpec/CipherSuite, MQSC, DLQ, BOQ, CONNAME, CCDT, SHARECNV, MQCSP,
  CHLAUTH).
- **Seção de evolução Jakarta** (P3): tabela comparativa `allclient`/javax × `jakarta.client`/jakarta e passos de
  migração.
- **Catálogo "Boas × Más práticas em microsserviços de alta concorrência"** (P19): coletânea dos pares ✅/❌ usados ao
  longo do guia, consolidados em um quadro de referência rápida (anti-padrões de `Session`/`JMSContext` compartilhados,
  falta de pool, leaks, ack/commit sob concorrência, correlação COA/COD multi-instância, reconexão/idempotência).

### Entregável HTML (documentação principal) — ver P17/P20

A página HTML é um **artefato de primeira classe**, não um anexo decorativo. Deve:

- **Espelhar todas as seções** (1–5 + apêndices) com **onboarding progressivo/incremental** (do "o que é" ao avançado).
- Ter **navegação lateral fixa** com âncoras e destaque da seção ativa; **sumário/índice** navegável; busca simples se
  viável.
- Renderizar **código com syntax highlight + botão copiar**; **callouts** ✅ Boa prática / ❌ Má prática / ⚠️ Atenção / ℹ️
  Nota com estilos distintos.
- Ser **responsiva, acessível (WCAG AA)** e **standalone** (**single-file**, CSS/JS embutidos; sem backend).
- Seguir **paleta neutra/amigável e tipografia de leitura** (P20). `frontend-design` entra **só como motor de qualidade
  **, sem ditar cor/fonte ousada.
- **Sugestão de execução (P18):** gerar a página via um **subagent dedicado** que recebe o conteúdo já consolidado e
  devolve o arquivo HTML pronto, preservando a janela principal.

---

## 3. DIRETRIZES DE ESTILO

1. **Todo o resultado em português brasileiro** (P16); identificadores de código em inglês, comentários em PT-BR.
2. Extremamente explicativo e didático; não omitir detalhes complexos por brevidade (P19).
3. Blocos de código Java formatados, com **comentários em PT-BR nas linhas críticas**.
4. **Zero generalismos**: se uma propriedade afeta a conexão, descrever o comportamento exato e o default real.
5. Toda afirmação técnica deve ser **verificável na documentação IBM**; citar a fonte quando útil.
6. **Não economizar em exemplos**; sempre que pertinente, mostrar o par `✅ boa prática` × `❌ má prática`.

## 3.1 CRITÉRIOS DE VALIDAÇÃO (checklist do entregável)

Antes de considerar a pesquisa concluída, o resultado deve satisfazer **todos** os itens:

- [ ] **Dois artefatos entregues:** documento **Markdown (PT-BR)** + **página HTML standalone** (P17), ambos refletindo
  a pesquisa completa.
- [ ] **100% em português brasileiro** (P16), incluindo textos da UI da página HTML.
- [ ] **Projeto Micronaut Maven runnable** com classes **compiláveis** de onde os snippets foram extraídos (P14/P15).
- [ ] Todas as **seções obrigatórias** (1–5 + apêndices) presentes e aprofundadas.
- [ ] **COA/COD/Exception/Expiration/PAN-NAN** cobertos como referência exaustiva (P6), com tabela de Feedback codes.
- [ ] Para cada tema crítico, **par de exemplos boa × má prática** focado em **microsserviços de alta concorrência** (
  P19), explicando *por que falha* e o *sintoma observável*.
- [ ] **Página HTML** com navegação lateral, syntax highlight + copiar, callouts (✅/❌/⚠️/ℹ️), responsiva e **WCAG AA** (
  P17).
- [ ] **Paleta neutra/amigável e tipografia de leitura** conforme P20 (sem #000-sobre-#fff, sem cores saturadas
  exageradas).
- [ ] APIs e constantes **validadas contra a documentação IBM** (P15); versões/coordenadas exatas declaradas.
- [ ] Execução feita de forma **eficiente em contexto** (P18): blocos pesados delegados a subagents; janela principal só
  orquestra.

## 4. FORA DE ESCOPO (evitar scope creep)

- Interop com apps não-JMS (RFH2/MQMD/EBCDIC) além do parágrafo de alerta (P9).
- Pub/Sub (tópicos), AMQP/MQTT, bridges Kafka.
- Administração avançada de cluster além da HA citada (P5).

## 4.1 ESTRATÉGIA DE EXECUÇÃO EFICIENTE (orquestração — ver P18)

A pesquisa é grande; a janela principal **não deve** acumular saída bruta. Recomenda-se:

1. **Fase A — Validação de fatos** (subagent `web-researcher`/`general-purpose`): confirmar versões exatas no Maven
   Central, nomes de constantes `JMS_IBM_*`/`WMQConstants`, comandos MQSC e imagem Testcontainers. Retorna só uma ficha
   de fatos.
2. **Fase B — Projeto Maven runnable** (subagent): gerar `pom.xml`, classes (Producer, Consumer de negócio, Consumer de
   reports, `@Factory` de pool, config), MQSC e testes. Retorna a árvore de arquivos.
3. **Fase C — Documento Markdown PT-BR** (subagent ou principal): redigir o guia referenciando os arquivos da Fase B.
4. **Fase D — Página HTML single-file** (subagent; `frontend-design` só como motor de qualidade): transformar o conteúdo
   consolidado na página (P17/P20, paleta neutra prevalece).
5. **Fase E — Validação final** (principal): rodar o checklist de **3.1**.

- Orquestrar via `Agent` + `TaskCreate`/`TaskList` (mecanismo disponível neste ambiente), **paralelizando** Fases A e B
  por serem independentes. A feature "workflows" não está instalada aqui; se passar a existir, pode encadear A→E
  automaticamente.

## 5. FATOS JÁ VERIFICADOS NESTA SESSÃO (fontes)

- Java 25 suportado **a partir do IBM MQ 9.4.0** (não no 9.3): IBM Docs — "What's changed in IBM MQ / Java 25" (
  9.4.x). → motivou descartar 9.3 e adotar **9.4 em cliente E servidor** (par homogêneo).
- **Versão de produção cravada pelo usuário: `9.4.5.0`** (linha **CD**). Fonte declarada:
  mvnrepository.com/artifact/com.ibm.mq/com.ibm.mq.allclient. Existe no Maven Central (confirmado no
  `maven-metadata.xml`); `<latest>`/`<release>` global no momento da verificação = `9.4.5.1`.
- Linha `9.4.0.x` = **LTS** (Long Term Support; 3º dígito `0`); linhas `9.4.1`…`9.4.5` = **CD** (Continuous Delivery) —
  modelo de release IBM `V.R.M.F`. O projeto usa **CD `9.4.5.0`**; o guia deve explicar a diferença de janela de suporte
  vs LTS.
- Compatibilidade cliente↔QMgr: qualquer cliente suportado conecta a qualquer QMgr suportado (IBM — "compatibility
  between MQ client and queue manager"); usamos **par homogêneo 9.4↔9.4**, eliminando ressalvas de negociação de canal.
- `com.ibm.mq.allclient` = `javax.jms`; `com.ibm.mq.jakarta.client` = `jakarta.jms` (ambos a partir do MQ 9.3.0): IBM
  Docs — "IBM MQ classes for Jakarta Messaging: an overview".
- Micronaut 4.x migrou JMS para `jakarta.jms` (módulo `micronaut-jms` 4.x é jakarta-only): Micronaut JMS 4.x guide / "
  Upgrade to Micronaut Framework 4".
- `frontend-design` instalada (`claude-plugins-official`); feature "workflows" **não** instalada neste ambiente (
  verificado em `~/.claude/plugins`).
