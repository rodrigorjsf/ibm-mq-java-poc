package com.example.ibmmq.messaging;

import com.example.ibmmq.config.MqConnectionFactoryFactory;
import com.ibm.msg.client.wmq.WMQConstants;
import com.ibm.mq.constants.MQConstants;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.messaginghub.pooled.jms.JmsPoolConnectionFactory;

import javax.jms.DeliveryMode;
import javax.jms.JMSContext;
import javax.jms.JMSProducer;
import javax.jms.Queue;
import javax.jms.TextMessage;

/**
 * Production {@link SendPort} adapter over the <b>pooled producer factory</b> (ADR-0006). This is the only
 * place on the send side that touches {@code javax.jms}: it opens a short-lived {@code JMSContext} per send
 * over the pooled {@link JmsPoolConnectionFactory}, builds the {@code TextMessage}, sets {@code JMSReplyTo}
 * and the {@code JMS_IBM_REPORT_*} options, resolves the {@code queue:///} destinations, sends, and returns
 * the assigned {@code messageId}.
 *
 * <p>All of this used to live inside {@code BusinessMessageProducer}; ADR-0008 moves it behind the seam so
 * the producer calls {@link SendPort#send} with a decoded {@link OutboundMessage} and never sees a
 * {@code javax.jms.Message}.</p>
 *
 * <p><b>Default bean.</b> Gated on {@code messaging.adapter != fake} so it is the production default and is
 * absent when the in-memory fake is selected (broker-free unit tests set {@code messaging.adapter=fake}).</p>
 */
@Singleton
@Requires(property = "messaging.adapter", notEquals = "fake")
public class PooledJmsSendAdapter implements SendPort {

    private final JmsPoolConnectionFactory connectionFactory;

    public PooledJmsSendAdapter(
            @Named(MqConnectionFactoryFactory.PRODUCER) JmsPoolConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public String send(OutboundMessage message) {
        // try-with-resources: the JMSContext (and the underlying pooled connection/session) is returned to
        // the pool at the end. AUTO_ACKNOWLEDGE: the send is confirmed immediately.
        try (JMSContext context = connectionFactory.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {

            Queue replyToQueue = context.createQueue("queue:///" + message.replyToQueue());

            TextMessage jmsMessage = context.createTextMessage(message.payload());
            jmsMessage.setJMSReplyTo(replyToQueue);

            // Report options: the Java field is UPPER_SNAKE (JMS_IBM_REPORT_COA) and the value is the MQRO_* int.
            if (message.requestCoa()) {
                jmsMessage.setIntProperty(WMQConstants.JMS_IBM_REPORT_COA, MQConstants.MQRO_COA);
            }
            if (message.requestCod()) {
                jmsMessage.setIntProperty(WMQConstants.JMS_IBM_REPORT_COD, MQConstants.MQRO_COD);
            }

            JMSProducer producer = context.createProducer();
            producer.setDeliveryMode(toJmsDeliveryMode(message.persistence()));

            // The destination business queue, resolved via the queue:/// URI form (adapter-owned per ADR-0008).
            Queue businessQueue = context.createQueue("queue:///" + message.destinationQueue());
            producer.send(businessQueue, jmsMessage);

            // The JMSMessageID is assigned only after the send. Default MQRO_COPY_MSG_ID_TO_CORREL_ID makes
            // this id the report's CorrelationId.
            return jmsMessage.getJMSMessageID();
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao enviar mensagem de negocio: " + message.businessKey(), e);
        }
    }

    private static int toJmsDeliveryMode(DeliveryPersistence persistence) {
        return persistence == DeliveryPersistence.NON_PERSISTENT
                ? DeliveryMode.NON_PERSISTENT
                : DeliveryMode.PERSISTENT;
    }
}
