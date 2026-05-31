# ibmmq-jms-guide — Relatorios de entrega COA/COD com Micronaut 4 + IBM MQ (JMS 2.0)

Projeto de exemplo, pronto para producao, que demonstra **relatorios de entrega COA
(Confirmation On Arrival) e COD (Confirmation On Delivery)** do IBM MQ usando o cliente
**javax.jms / JMS 2.0** (`com.ibm.mq.allclient`) sob **Micronaut 4** (apenas para DI /
`@ConfigurationProperties` / `@Factory` / ciclo de vida — o JMS e gerenciado manualmente
via `JMSContext`).

## Conceito (resumo)

- O **produtor** envia uma mensagem de negocio persistente para a fila de negocio, definindo
  `JMSReplyTo` = fila de relatorios e habilitando COA+COD via as propriedades JMS
  `JMS_IBM_Report_COA` / `JMS_IBM_Report_COD`.
- O **COA** e gerado pelo gerenciador de filas quando a mensagem *chega* na fila de destino.
- O **COD** e gerado quando a aplicacao consumidora faz um *get destrutivo* (e comita).
- O **consumidor de relatorios** le a fila de relatorios, le o codigo de feedback via
  `JMS_IBM_Feedback` (259=COA, 260=COD, 258=EXPIRATION, 275=PAN, 276=NAN; demais reason codes
  `MQRC_*` = excecao) e correlaciona `JMSCorrelationID` -> `MessageId` original (default
  `MQRO_COPY_MSG_ID_TO_CORREL_ID`).

## Pre-requisitos

- **JDK 25 (Amazon Corretto)** para build/run local. O bytecode tem como alvo o **release 21**
  (producao roda em Java 21).
- Maven instalado no caminho indicado abaixo.
- **Docker** apenas para o teste de integracao e o `docker-compose`.

Toolchain usada neste guia (caminhos absolutos):

```
JAVA_HOME=/home/rodrigo/.local/jdk25
mvn=/home/rodrigo/.local/maven-current/bin/mvn
```

## Build

```bash
cd ibmmq-jms-guide
JAVA_HOME=/home/rodrigo/.local/jdk25 /home/rodrigo/.local/maven-current/bin/mvn -DskipTests clean test-compile
```

> A primeira execucao baixa as dependencias (cliente IBM MQ ~8 MB) e pode levar alguns minutos.

## Testes unitarios (sem broker)

```bash
JAVA_HOME=/home/rodrigo/.local/jdk25 /home/rodrigo/.local/maven-current/bin/mvn test
```

Roda apenas os testes `*Test` (router de feedback + correlacao). O `surefire` esta configurado
para **excluir** `*IT.java`.

## Teste de integracao (precisa de Docker)

`CoaCodEndToEndIT` sobe um IBM MQ real via Testcontainers, produz com COA+COD, consome e exige
que cheguem **ambos** os relatorios COA (259) e COD (260), cada um com `CorrelationId == MessageId`
original. Ligado ao `failsafe` (fase `verify`):

```bash
JAVA_HOME=/home/rodrigo/.local/jdk25 /home/rodrigo/.local/maven-current/bin/mvn verify
```

## Subir um broker local (docker-compose)

```bash
docker compose up -d
docker compose logs -f mq        # aguarde "Started queue manager"
# Console web: https://localhost:9443/ibmmq/console
docker compose down              # 'down -v' descarta o volume de dados
```

Os arquivos `mqsc/10-channel-auth.mqsc` e `mqsc/20-queues.mqsc` sao montados em `/etc/mqm` e
aplicados na criacao do QMgr (setup de **producao** com objetos `APP.*`, CONNAUTH e CHLAUTH).
O **IT**, por confiabilidade, usa os objetos `DEV.*` default da imagem (usuario `app`
pre-autorizado a `DEV.**`).

## Nota sobre Corretto 25: `--enable-native-access`

O cliente IBM MQ carrega bibliotecas nativas via `System.loadLibrary`. No Java 25 isso emite um
aviso de *native-access*. Por isso o `surefire` e o `failsafe` passam:

```
--enable-native-access=ALL-UNNAMED
```

Ao **executar a aplicacao** diretamente, adicione o mesmo argumento a JVM:

```bash
JAVA_HOME=/home/rodrigo/.local/jdk25 /home/rodrigo/.local/maven-current/bin/mvn mn:run \
  -Dmn.appArgs="" -Dmn.jvmArgs="--enable-native-access=ALL-UNNAMED"
```

## Estrutura

```
config/        MqProperties (@ConfigurationProperties) + MqConnectionFactoryFactory (@Factory, pool)
producer/      BusinessMessageProducer (envia com COA+COD)
consumer/      BusinessMessageConsumer (get destrutivo -> COD) + ReportMessageConsumer (le relatorios)
correlation/   CorrelationStore + InMemory + esqueleto persistente
report/        ReportFeedbackRouter (mapeamento puro feedback -> ReportType)
model/         DeliveryEvent, PendingMessage, ReportType
mqsc/          objetos de producao (canal/auth + filas/DLQ/backout)
```
