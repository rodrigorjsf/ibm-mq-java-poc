package com.example.ibmmq.integration;

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

        try (JMSContext ctx = cf.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {
            JMSConsumer reportConsumer = ctx.createConsumer(ctx.createQueue(REPORT_QUEUE));

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
}
