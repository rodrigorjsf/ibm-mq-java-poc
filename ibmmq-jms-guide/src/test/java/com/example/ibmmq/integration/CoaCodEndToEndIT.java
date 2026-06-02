package com.example.ibmmq.integration;

import com.example.ibmmq.consumer.BusinessMessageConsumer;
import com.example.ibmmq.consumer.ReportMessageConsumer;
import com.example.ibmmq.model.DeliveryEvent;
import com.example.ibmmq.producer.BusinessMessageProducer;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.testcontainers.MQContainer;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Teste de integracao ponta-a-ponta (requer Docker) do fluxo COA+COD contra um IBM MQ real, dirigindo os
 * BEANS DE PRODUCAO atraves do seam de mensageria (ADR-0008) sobre as factories pooled-JMS (ADR-0006).
 *
 * <p><b>Broker:</b> Testcontainers 2.x via o modulo oficial IBM {@link MQContainer}
 * (imagem {@code icr.io/ibm-messaging/mq}). Lifecycle manual ({@code @BeforeAll}/{@code @AfterAll}).</p>
 *
 * <p><b>Prova das fatias 1-3 (epic #25).</b> Em vez de duplicar JMS cru com um {@code MQConnectionFactory}
 * montado a mao, este IT sobe um {@link ApplicationContext} Micronaut e pede os tres entry points de
 * producao — {@link BusinessMessageProducer}, {@link BusinessMessageConsumer} e
 * {@link ReportMessageConsumer} — que delegam toda a construcao {@code javax.jms} ao adapter pooled-JMS.
 * Como {@code messaging.adapter} NAO e definido aqui, os {@code @Requires(notEquals = "fake")} resolvem
 * para os adapters de PRODUCAO ({@code PooledJmsSendAdapter}/{@code PooledJmsReceiveAdapter}) — definir
 * {@code messaging.adapter=fake} exercitaria o broker em memoria e nunca tocaria o container (verde-mas-
 * sem-sentido), entao deliberadamente deixamos a chave de fora.</p>
 *
 * <p><b>Por que conectar como {@code admin} (e nao {@code app})?</b> Para o Queue Manager GERAR e
 * ENTREGAR um relatorio (COA/COD), ele faz um PUT-com-contexto na ReplyToQ. Isso exige autoridade de
 * CONTEXTO (verificado em k3d vivo: {@code +passid}/{@code PASSALL}), que o usuario {@code app} de baixo
 * privilegio do dev image NAO possui — o relatorio falharia com {@code MQRC_NOT_AUTHORIZED (2035)} e iria
 * para a DLQ (por isso a fila de relatorios fica vazia ao conectar como {@code app}). O usuario
 * {@code admin} (canal {@code DEV.ADMIN.SVRCONN}) possui autoridade plena. Em PRODUCAO, conceda a
 * autoridade minima necessaria ao principal da aplicacao (ex.: {@code SET AUTHREC ... AUTHADD(PUT, PASSID,
 * PASSALL, SETID, SETALL)}) — ver a secao de Seguranca do guia e os arquivos {@code mqsc/}.</p>
 *
 * <p><b>Fluxo:</b> (1) o produtor envia uma mensagem persistente com COA+COD e {@code JMSReplyTo=DEV.QUEUE.2}
 * e devolve o MessageId; (2) o consumidor de negocio consome destrutivamente de {@code DEV.QUEUE.1} dentro
 * de uma UoW transacionada e comita (dispara o COD); (3) o consumidor de relatorios le {@code DEV.QUEUE.2}
 * e exige que cheguem AMBOS um COA (feedback {@code MQFB_COA}=259) e um COD ({@code MQFB_COD}=260), cada um
 * com {@code CorrelationId == MessageId} original (default {@code MQRO_COPY_MSG_ID_TO_CORREL_ID}).</p>
 *
 * <p><b>Lifecycle:</b> a porta do container so e conhecida APOS {@code mq.start()}, entao o
 * {@link ApplicationContext} e iniciado em {@code @BeforeEach} (lendo {@code mq.getHost()}/{@code getPort()})
 * e fechado em {@code @AfterEach} ANTES de o container parar — o adapter de recebimento segura um
 * {@code JMSContext} de vida-longa com {@code @PreDestroy} close que precisa do broker ainda no ar (o JUnit
 * 5 roda {@code @AfterEach} antes de {@code @AfterAll}, garantindo a ordem).</p>
 *
 * <p>Nomeado {@code *IT} para que o <b>failsafe</b> (e nao o surefire) o execute em {@code mvn verify}.</p>
 */
@DisplayName("COA/COD ponta-a-ponta contra IBM MQ real (beans de producao via seam pooled-JMS)")
class CoaCodEndToEndIT {

    private static final String SECRET = "passw0rd";
    private static final String QUEUE_MANAGER = "QM1";
    // Conecta pelo canal/usuario de ADMIN para ter autoridade de contexto na geracao de relatorios.
    private static final String ADMIN_CHANNEL = "DEV.ADMIN.SVRCONN";
    private static final String ADMIN_USER = "admin";

    private static MQContainer mq;

    private ApplicationContext context;
    private BusinessMessageProducer producer;
    private BusinessMessageConsumer businessConsumer;
    private ReportMessageConsumer reportConsumer;

    @BeforeAll
    static void startBroker() {
        // Tag: a imagem nao publica uma tag "9.4.5.0" pura — usar o fixpack release -r2.
        mq = new MQContainer("icr.io/ibm-messaging/mq:9.4.5.0-r2")
                .acceptLicense()
                .withQueueManager(QUEUE_MANAGER)
                .withAppPassword(SECRET)     // habilita o usuario 'app' (uso geral)
                .withAdminPassword(SECRET);  // habilita o usuario 'admin' (usado por este teste)
        mq.start();
    }

    @AfterAll
    static void stopBroker() {
        if (mq != null) {
            mq.stop();
        }
    }

    @BeforeEach
    void startContext() {
        // The container's mapped port is known ONLY after mq.start() (dynamic host port) — read it here,
        // never in a static initializer. messaging.adapter is intentionally LEFT UNSET so the production
        // pooled-JMS adapters wire (the in-memory fake is gated on messaging.adapter=fake). No datasource
        // or correlation.store keys: the InMemoryCorrelationStore wires by default and auditRepository is
        // @Nullable/absent, so report persistence is simply skipped. business-queue/report-queue default to
        // DEV.QUEUE.1/DEV.QUEUE.2 — no override needed. Admin creds (AC2) for report PUT-with-context authority.
        context = ApplicationContext.run(Map.ofEntries(
                Map.entry("ibm-mq.host", mq.getHost()),
                Map.entry("ibm-mq.port", mq.getPort()),
                Map.entry("ibm-mq.channel", ADMIN_CHANNEL),
                Map.entry("ibm-mq.queue-manager", QUEUE_MANAGER),
                Map.entry("ibm-mq.user", ADMIN_USER),
                Map.entry("ibm-mq.password", SECRET)));

        // Lazy @Singletons — pedir os beans explicitamente garante que a producao seja exercitada pelo
        // teste (e nao um fake em memoria). Estes sao os tres entry points de producao, inalterados.
        producer = context.getBean(BusinessMessageProducer.class);
        businessConsumer = context.getBean(BusinessMessageConsumer.class);
        reportConsumer = context.getBean(ReportMessageConsumer.class);
    }

    @AfterEach
    void closeContext() {
        // Fecha o contexto ANTES de o container parar: libera o JMSContext de vida-longa que o adapter de
        // recebimento segura (@PreDestroy), que precisa do broker ainda no ar.
        if (context != null) {
            context.close();
        }
    }

    @Test
    @DisplayName("Produzir com COA+COD, consumir e receber ambos os relatorios com CorrelId == MessageId")
    void coaAndCodAreDelivered() {
        // Plausibilidade do put-timestamp: o relatorio acabou de ser gerado, entao a janela e generosa
        // mas finita (clock-skew do container + duracao do teste). Capturada ANTES de ler os relatorios.
        LocalDateTime windowStartUtc = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10);

        // ---- 1) PRODUZIR com COA+COD e JMSReplyTo=DEV.QUEUE.2 (via o produtor de producao) ----
        // O SendPort (pooled producer adapter) monta o TextMessage persistente, define JMSReplyTo e as
        // opcoes de report, e devolve o JMSMessageID ("ID:" + hex). Default MQRO_COPY_MSG_ID_TO_CORREL_ID
        // faz desse id o CorrelationId dos futuros relatorios.
        String originalMessageId = producer.send("pedido-42", "{\"pedido\":42}");
        Assertions.assertNotNull(originalMessageId, "MessageId deve ser atribuido apos o send");
        Assertions.assertTrue(originalMessageId.startsWith("ID:"),
                "JMSMessageID deve ter o prefixo 'ID:': " + originalMessageId);

        // ---- 2) CONSUMIR destrutivamente de DEV.QUEUE.1 (a UoW transacionada comita) -> dispara COD ----
        // O ReceivePort executa o handler DENTRO da UoW e comita no retorno normal (liberando o COD).
        String consumed = businessConsumer.receiveOne(Duration.ofSeconds(15).toMillis());
        Assertions.assertNotNull(consumed, "A mensagem de negocio deveria ter sido consumida");

        // ---- 3) LER DEV.QUEUE.2 e exigir COA (259) E COD (260), ambos com CorrelId == MessageId ----
        // O ReceivePort aplica a URI ?mdReadEnabled=true (issue #19) na propria fila de relatorios, entao
        // os seis valores MQMD vem populados no DeliveryEvent. Nenhum javax.jms.Message chega ao teste.
        boolean coaSeen = false;
        boolean codSeen = false;

        long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
        while (System.currentTimeMillis() < deadline && !(coaSeen && codSeen)) {
            DeliveryEvent ev = reportConsumer.receiveOneReport(5_000L);
            if (ev == null) {
                continue; // timeout parcial — segue tentando ate o deadline.
            }
            int feedback = ev.feedbackCode();
            System.out.println("Relatorio recebido: feedback=" + feedback + " correlId=" + ev.correlationId());

            // Default MQRO_COPY_MSG_ID_TO_CORREL_ID: CorrelId do relatorio == MessageId original (AC3).
            // Ambos sao a string JMS "ID:" + hex (o produtor devolve getJMSMessageID(); o adapter de
            // recebimento popula correlationId() de getJMSCorrelationID()), entao a comparacao e por string.
            Assertions.assertEquals(originalMessageId, ev.correlationId(),
                    "CorrelationId do relatorio deve ser igual ao MessageId original");

            // Bonus #19: assercoes dos seis campos MQMD recuperados, lidas dos acessores do DeliveryEvent
            // (sem reimportar javax.jms). Mapeamento 1:1 com o IT anterior (live-validado) — nada mais estrito.
            assertRecoveredMqmdFields(ev, feedback, windowStartUtc, originalMessageId);

            // MQFB_COA = 259, MQFB_COD = 260 (de com.ibm.mq.constants.MQConstants).
            if (feedback == MQConstants.MQFB_COA) {
                coaSeen = true;
            } else if (feedback == MQConstants.MQFB_COD) {
                codSeen = true;
            }
        }

        Assertions.assertTrue(coaSeen, "Deveria ter chegado um relatorio COA (feedback MQFB_COA=259)");
        Assertions.assertTrue(codSeen, "Deveria ter chegado um relatorio COD (feedback MQFB_COD=260)");
    }

    /**
     * Issue #19 — asserts the six recovered MQMD values directly off the decoded {@link DeliveryEvent}
     * accessors (no {@code javax.jms} re-import). STRICT on the deterministic ones; TOLERANT on QMgr-set
     * values (which may legitimately be blank or a QMgr default). Maps the prior live-validated IT 1:1.
     *
     * <ul>
     *   <li><b>Strict:</b> {@code correlationIdBytesHex()} == original MsgId hex (default
     *       {@code MQRO_COPY_MSG_ID_TO_CORREL_ID}); {@code reportTypeChar()} == {@code 'A'}/{@code 'D'}
     *       (derived from the 259/260 feedback); {@code putTimestampUtc()} non-null and plausibly recent.</li>
     *   <li><b>Tolerant:</b> {@code applIdentityData()} recovered (read-enabled => non-null, may be blank);
     *       {@code accountingToken()} recovered (32 bytes, may be the QMgr default token).</li>
     * </ul>
     */
    private static void assertRecoveredMqmdFields(DeliveryEvent ev, int feedback, LocalDateTime windowStartUtc,
                                                  String originalMessageId) {
        // #3 correlationIdBytes == original MsgId bytes (the load-bearing cross-report link). The
        // JMSMessageID is "ID:" + hex(MsgId), and under default MQRO_COPY_MSG_ID_TO_CORREL_ID the report's
        // CorrelId bytes ARE the original MsgId bytes. Strip the "ID:" prefix and compare to the recovered
        // hex (case-insensitive: HexBytes.toHex is lowercase, the JMSMessageID hex may not be).
        Assertions.assertNotNull(ev.correlationIdBytes(),
                "correlationIdBytes (== MsgId original) deve ser recuperado");
        Assertions.assertTrue(ev.correlationIdBytes().length > 0, "correlationIdBytes nao deve ser vazio");
        String originalMsgIdHex = originalMessageId.substring(3); // strip "ID:"
        Assertions.assertTrue(originalMsgIdHex.equalsIgnoreCase(ev.correlationIdBytesHex()),
                "correlationIdBytes (hex) deve igualar os bytes do MsgId original: esperado="
                        + originalMsgIdHex + " obtido=" + ev.correlationIdBytesHex());

        // #6 reportTypeChar derivado: 'A' para COA (259), 'D' para COD (260).
        char expectedChar = feedback == MQConstants.MQFB_COA ? 'A'
                : feedback == MQConstants.MQFB_COD ? 'D' : '?';
        Assertions.assertEquals(expectedChar, ev.reportTypeChar(),
                "reportTypeChar deve ser 'A' para COA e 'D' para COD");

        // #5 putTimestampUtc nao-nulo e plausivelmente recente (a geracao do relatorio acabou de ocorrer).
        LocalDateTime putTs = ev.putTimestampUtc();
        Assertions.assertNotNull(putTs, "putTimestampUtc deve ser recuperado (mdReadEnabled=true)");
        Assertions.assertTrue(putTs.isAfter(windowStartUtc),
                "putTimestampUtc deve ser recente (apos o inicio da janela): " + putTs);
        Assertions.assertTrue(putTs.isBefore(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10)),
                "putTimestampUtc nao deve estar no futuro distante: " + putTs);

        // #4 messageIdBytes (proprio MsgId do relatorio) recuperado via JMS_IBM_MQMD_MsgId (read-enabled).
        Assertions.assertNotNull(ev.messageIdBytes(),
                "messageIdBytes (proprio MsgId do relatorio) deve ser recuperado com mdReadEnabled=true");

        // #1 applIdentityData: read-enabled => recuperavel (TOLERANTE: pode ser branco, definido pelo QMgr).
        Assertions.assertNotNull(ev.applIdentityData(),
                "applIdentityData deve ser recuperado (pode ser branco) com mdReadEnabled=true");

        // #2 accountingToken: recuperado (TOLERANTE quanto ao valor — pode ser o token default do QMgr),
        // com o comprimento MQ_ACCOUNTING_TOKEN_LENGTH = 32 bytes (MQBYTE32).
        Assertions.assertNotNull(ev.accountingToken(),
                "accountingToken deve ser recuperado com mdReadEnabled=true");
        Assertions.assertEquals(32, ev.accountingToken().length,
                "accountingToken deve ter MQ_ACCOUNTING_TOKEN_LENGTH (32 bytes)");
    }
}
