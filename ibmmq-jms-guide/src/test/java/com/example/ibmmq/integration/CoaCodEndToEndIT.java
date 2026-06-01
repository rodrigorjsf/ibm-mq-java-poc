package com.example.ibmmq.integration;

import com.example.ibmmq.model.ReportType;
import com.example.ibmmq.report.ReportDescriptor;
import com.ibm.msg.client.wmq.WMQConstants;
import com.ibm.mq.constants.MQConstants;
import com.ibm.mq.jms.MQConnectionFactory;
import com.ibm.mq.testcontainers.MQContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.jms.DeliveryMode;
import javax.jms.JMSConsumer;
import javax.jms.JMSContext;
import javax.jms.JMSProducer;
import javax.jms.Message;
import javax.jms.Queue;
import javax.jms.TextMessage;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Teste de integracao ponta-a-ponta (requer Docker) do fluxo COA+COD contra um IBM MQ real.
 *
 * <p><b>Broker:</b> Testcontainers 2.x via o modulo oficial IBM {@link MQContainer}
 * (imagem {@code icr.io/ibm-messaging/mq}). Lifecycle manual ({@code @BeforeAll}/{@code @AfterAll}).</p>
 *
 * <p><b>Por que conectar como {@code admin} (e nao {@code app})?</b> Para o Queue Manager GERAR e
 * ENTREGAR um relatorio (COA/COD), ele faz um PUT-com-contexto na ReplyToQ. Isso exige autoridade de
 * CONTEXTO ({@code +setall}), que o usuario {@code app} de baixo privilegio do dev image NAO possui —
 * o relatorio falharia com {@code MQRC_NOT_AUTHORIZED (2035)} e iria para a DLQ (por isso a fila de
 * relatorios fica vazia ao conectar como {@code app}). O usuario {@code admin} possui autoridade plena.
 * Em PRODUCAO, conceda a autoridade minima necessaria ao principal da aplicacao
 * (ex.: {@code SET AUTHREC PROFILE(APP.REPORT.QUEUE) OBJTYPE(QUEUE) GROUP('appgrp') AUTHADD(PUT, SETALL)})
 * — ver a secao de Seguranca do guia e os arquivos {@code mqsc/}.</p>
 *
 * <p><b>Fluxo:</b> (1) produz uma mensagem persistente com COA+COD e {@code JMSReplyTo=DEV.QUEUE.2};
 * (2) consome destrutivamente de {@code DEV.QUEUE.1} e comita (dispara o COD); (3) le {@code DEV.QUEUE.2}
 * e exige que cheguem AMBOS um COA (feedback {@code MQFB_COA}=259) e um COD ({@code MQFB_COD}=260),
 * cada um com {@code CorrelationId == MessageId} original (default {@code MQRO_COPY_MSG_ID_TO_CORREL_ID}).</p>
 *
 * <p>Nomeado {@code *IT} para que o <b>failsafe</b> (e nao o surefire) o execute em {@code mvn verify}.</p>
 */
@DisplayName("COA/COD ponta-a-ponta contra IBM MQ real")
class CoaCodEndToEndIT {

    private static final String SECRET = "passw0rd";
    private static final String QUEUE_MANAGER = "QM1";
    // Conecta pelo canal/usuario de ADMIN para ter autoridade de contexto na geracao de relatorios.
    private static final String ADMIN_CHANNEL = "DEV.ADMIN.SVRCONN";
    private static final String ADMIN_USER = "admin";

    private static final String BUSINESS_QUEUE = "queue:///DEV.QUEUE.1";
    private static final String REPORT_QUEUE = "queue:///DEV.QUEUE.2";

    private static MQContainer mq;

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

    private MQConnectionFactory buildConnectionFactory() throws Exception {
        MQConnectionFactory cf = new MQConnectionFactory();
        cf.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);
        cf.setStringProperty(WMQConstants.WMQ_HOST_NAME, mq.getHost());
        cf.setIntProperty(WMQConstants.WMQ_PORT, mq.getPort());
        cf.setStringProperty(WMQConstants.WMQ_CHANNEL, ADMIN_CHANNEL);
        cf.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, QUEUE_MANAGER);
        cf.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
        cf.setStringProperty(WMQConstants.USERID, ADMIN_USER);
        cf.setStringProperty(WMQConstants.PASSWORD, SECRET);
        return cf;
    }

    @Test
    @DisplayName("Produzir com COA+COD, consumir e receber ambos os relatorios com CorrelId == MessageId")
    void coaAndCodAreDelivered() throws Exception {
        MQConnectionFactory cf = buildConnectionFactory();

        String originalMessageId;

        // ---- 1) PRODUZIR com COA+COD e JMSReplyTo=DEV.QUEUE.2 ----
        try (JMSContext ctx = cf.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
            Queue businessQueue = ctx.createQueue(BUSINESS_QUEUE);
            Queue reportQueue = ctx.createQueue(REPORT_QUEUE);

            TextMessage msg = ctx.createTextMessage("{\"pedido\":42}");
            msg.setJMSReplyTo(reportQueue);
            // Opcoes de report no MQMD Report (verificado: chega como 2304 = COA 256 + COD 2048).
            msg.setIntProperty(WMQConstants.JMS_IBM_REPORT_COA, MQConstants.MQRO_COA);
            msg.setIntProperty(WMQConstants.JMS_IBM_REPORT_COD, MQConstants.MQRO_COD);

            JMSProducer producer = ctx.createProducer();
            producer.setDeliveryMode(DeliveryMode.PERSISTENT);
            producer.send(businessQueue, msg);

            originalMessageId = msg.getJMSMessageID();
            Assertions.assertNotNull(originalMessageId, "MessageId deve ser atribuido apos o send");
        }

        // ---- 2) CONSUMIR destrutivamente de DEV.QUEUE.1 (comita) -> dispara COD ----
        try (JMSContext ctx = cf.createContext(JMSContext.SESSION_TRANSACTED)) {
            JMSConsumer consumer = ctx.createConsumer(ctx.createQueue(BUSINESS_QUEUE));
            Message consumed = consumer.receive(Duration.ofSeconds(15).toMillis());
            Assertions.assertNotNull(consumed, "A mensagem de negocio deveria ter sido consumida");
            ctx.commit(); // libera o COD para a fila de relatorios
        }

        // ---- 3) LER DEV.QUEUE.2 e exigir COA (259) E COD (260), ambos com CorrelId == MessageId ----
        boolean coaSeen = false;
        boolean codSeen = false;

        // Plausibilidade do put-timestamp: o relatorio acabou de ser gerado, entao a janela e generosa
        // mas finita (clock-skew do container + duracao do teste). Capturada ANTES de ler os relatorios.
        LocalDateTime windowStartUtc = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10);

        try (JMSContext ctx = cf.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
            // Issue #19: habilita a leitura do MQMD na PROPRIA fila de relatorios deste IT (a URI
            // ?mdReadEnabled=true), para que as propriedades JMS_IBM_MQMD_* venham populadas — sem isso os
            // seis campos voltariam nulos. O consumer de producao habilita o mesmo na sua propria URI; este
            // IT le DEV.QUEUE.2 com seu proprio JMSContext, entao precisa habilitar aqui tambem.
            JMSConsumer reportConsumer =
                    ctx.createConsumer(ctx.createQueue(REPORT_QUEUE + "?mdReadEnabled=true"));

            long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
            while (System.currentTimeMillis() < deadline && !(coaSeen && codSeen)) {
                Message report = reportConsumer.receive(5_000L);
                if (report == null) {
                    continue;
                }
                int feedback = report.getIntProperty(WMQConstants.JMS_IBM_FEEDBACK);
                String correlId = report.getJMSCorrelationID();
                System.out.println("Relatorio recebido: feedback=" + feedback + " correlId=" + correlId);

                // Default MQRO_COPY_MSG_ID_TO_CORREL_ID: CorrelId do relatorio == MessageId original.
                Assertions.assertEquals(originalMessageId, correlId,
                        "CorrelationId do relatorio deve ser igual ao MessageId original");

                ReportType type = feedback == MQConstants.MQFB_COA ? ReportType.COA
                        : feedback == MQConstants.MQFB_COD ? ReportType.COD : ReportType.UNKNOWN;
                assertRecoveredMqmdFields(report, type, windowStartUtc, originalMessageId);

                if (feedback == MQConstants.MQFB_COA) {
                    coaSeen = true;
                } else if (feedback == MQConstants.MQFB_COD) {
                    codSeen = true;
                }
            }
        }

        Assertions.assertTrue(coaSeen, "Deveria ter chegado um relatorio COA (feedback MQFB_COA=259)");
        Assertions.assertTrue(codSeen, "Deveria ter chegado um relatorio COD (feedback MQFB_COD=260)");
    }

    /**
     * Issue #19 — asserts the six recovered MQMD values on a received report. STRICT on the deterministic
     * ones; TOLERANT on QMgr-set values (which may legitimately be blank or a QMgr default).
     *
     * <ul>
     *   <li><b>Strict:</b> {@code correlationIdBytes} == original MsgId bytes (default
     *       {@code MQRO_COPY_MSG_ID_TO_CORREL_ID}); {@code reportTypeChar} == {@code 'A'}/{@code 'D'};
     *       {@code putTimestampUtc} non-null and plausibly recent.</li>
     *   <li><b>Tolerant:</b> {@code applIdentityData} recovered (read-enabled => non-null, may be blank);
     *       {@code accountingToken} recovered (24 bytes, may be the QMgr default token).</li>
     * </ul>
     */
    private static void assertRecoveredMqmdFields(Message report, ReportType type, LocalDateTime windowStartUtc,
                                                  String originalMessageId) throws Exception {
        ReportDescriptor descriptor = ReportDescriptor.from(report, type);

        // #3 correlationIdBytes == original MsgId bytes (the load-bearing cross-report link). The
        // JMSMessageID is "ID:" + hex(MsgId), and under default MQRO_COPY_MSG_ID_TO_CORREL_ID the report's
        // CorrelId bytes ARE the original MsgId bytes. Assert the byte[] form explicitly (AC2 strict):
        // strip the "ID:" prefix from the original JMSMessageID and compare to the recovered hex.
        byte[] correlIdBytes = descriptor.correlationIdBytes();
        Assertions.assertNotNull(correlIdBytes, "correlationIdBytes (== MsgId original) deve ser recuperado");
        Assertions.assertTrue(correlIdBytes.length > 0, "correlationIdBytes nao deve ser vazio");
        Assertions.assertTrue(originalMessageId.startsWith("ID:"),
                "JMSMessageID deve ter o prefixo 'ID:': " + originalMessageId);
        String originalMsgIdHex = originalMessageId.substring(3);
        Assertions.assertTrue(originalMsgIdHex.equalsIgnoreCase(descriptor.correlationIdBytesHex()),
                "correlationIdBytes (hex) deve igualar os bytes do MsgId original: esperado="
                        + originalMsgIdHex + " obtido=" + descriptor.correlationIdBytesHex());

        // #6 reportTypeChar derivado.
        char expectedChar = type == ReportType.COA ? 'A' : type == ReportType.COD ? 'D' : '?';
        Assertions.assertEquals(expectedChar, descriptor.reportTypeChar(),
                "reportTypeChar deve ser 'A' para COA e 'D' para COD");

        // #5 putTimestampUtc nao-nulo e plausivelmente recente (a geracao do relatorio acabou de ocorrer).
        LocalDateTime putTs = descriptor.putTimestampUtc();
        Assertions.assertNotNull(putTs, "putTimestampUtc deve ser recuperado (mdReadEnabled=true)");
        Assertions.assertTrue(putTs.isAfter(windowStartUtc),
                "putTimestampUtc deve ser recente (apos o inicio da janela): " + putTs);
        Assertions.assertTrue(putTs.isBefore(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(10)),
                "putTimestampUtc nao deve estar no futuro distante: " + putTs);

        // #4 messageIdBytes (proprio MsgId do relatorio) recuperado via JMS_IBM_MQMD_MsgId (read-enabled).
        Assertions.assertNotNull(descriptor.messageIdBytes(),
                "messageIdBytes (proprio MsgId do relatorio) deve ser recuperado com mdReadEnabled=true");

        // #1 applIdentityData: read-enabled => recuperavel (TOLERANTE: pode ser branco, definido pelo QMgr).
        Assertions.assertNotNull(descriptor.applIdentityData(),
                "applIdentityData deve ser recuperado (pode ser branco) com mdReadEnabled=true");

        // #2 accountingToken: recuperado (TOLERANTE quanto ao valor — pode ser o token default do QMgr),
        // com o comprimento MQ_ACCOUNTING_TOKEN_LENGTH = 32 bytes (MQBYTE32 — confirmado no bytecode CMQC e
        // empiricamente pelo broker). NAO 24 (esse e MQ_CORREL_ID_LENGTH/MQ_MSG_ID_LENGTH).
        byte[] accountingToken = descriptor.accountingToken();
        Assertions.assertNotNull(accountingToken,
                "accountingToken deve ser recuperado com mdReadEnabled=true");
        Assertions.assertEquals(32, accountingToken.length,
                "accountingToken deve ter MQ_ACCOUNTING_TOKEN_LENGTH (32 bytes)");
    }
}
