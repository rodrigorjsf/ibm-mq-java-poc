package com.example.ibmmq.consumer;

import com.example.ibmmq.config.MqProperties;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import javax.jms.JMSConsumer;
import javax.jms.ConnectionFactory;
import javax.jms.JMSContext;
import javax.jms.Message;
import javax.jms.Queue;
import javax.jms.TextMessage;

/**
 * Consome (GET destrutivo) da fila de negocio. <b>E este consumo que dispara o relatorio COD.</b>
 *
 * <p><b>COD-on-consume:</b> o relatorio COD e gerado pelo gerenciador de filas no momento em que a
 * mensagem e recuperada destrutivamente. Nao ha API JMS para "pedir" o COD no consumo — ele decorre
 * automaticamente das opcoes de report ja gravadas no MQMD pela mensagem original.</p>
 *
 * <p><b>Syncpoint / timing:</b> usamos {@code SESSION_TRANSACTED}. O COD e gerado dentro da unidade
 * de trabalho (UoW) do consumidor e <em>so fica disponivel apos o commit</em>. Se a UoW sofrer
 * rollback (backout), o COD nao e enviado e a mensagem volta para a fila — coerente com "entregue de
 * verdade". Por isso confirmamos com {@code context.commit()} apos processar com sucesso.</p>
 */
@Singleton
public class BusinessMessageConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(BusinessMessageConsumer.class);

    private final ConnectionFactory connectionFactory;
    private final MqProperties props;

    public BusinessMessageConsumer(ConnectionFactory connectionFactory, MqProperties props) {
        this.connectionFactory = connectionFactory;
        this.props = props;
    }

    /**
     * Recebe uma mensagem da fila de negocio (com timeout), processa e comita.
     *
     * @param timeoutMillis tempo maximo de espera por uma mensagem (ms).
     * @return o corpo da mensagem consumida, ou {@code null} se o timeout expirar sem mensagem.
     */
    public String receiveOne(long timeoutMillis) {
        // Contexto transacionado: o COD so fica visivel na fila de relatorios apos o commit.
        try (JMSContext context = connectionFactory.createContext(JMSContext.SESSION_TRANSACTED)) {

            Queue businessQueue = context.createQueue("queue:///" + props.getBusinessQueue());
            JMSConsumer consumer = context.createConsumer(businessQueue);

            // GET destrutivo — remove a mensagem da fila e (dado MQRO_COD na origem) agenda o COD.
            Message message = consumer.receive(timeoutMillis);
            if (message == null) {
                LOG.debug("Nenhuma mensagem na fila de negocio dentro do timeout ({} ms)", timeoutMillis);
                // Nada a comitar; o try-with-resources fecha o contexto (rollback implicito vazio).
                return null;
            }

            String body = (message instanceof TextMessage textMessage)
                    ? textMessage.getText()
                    : "(payload nao-texto)";

            String messageId = message.getJMSMessageID();
            // MDC: messageId = id consumido; correlationId = MESMO valor. O default IBM MQ
            // MQRO_COPY_MSG_ID_TO_CORREL_ID fara este id virar o CorrelationId do COD que este
            // consumo (apos commit) dispara — assim o mesmo id rastreia consumo e relatorio.
            MDC.put("messageId", messageId);
            MDC.put("correlationId", messageId);
            try {
                LOG.info("[stage=CONSUME] Mensagem de negocio consumida (GET destrutivo): messageId={}, body={}",
                        messageId, body);

                // ... processamento de negocio aqui ...

                // Commit: confirma o consumo e libera o COD para a fila de relatorios.
                // Em caso de excecao acima, o catch faz rollback (a mensagem volta; COD nao e gerado).
                context.commit();

                LOG.info("[stage=COMMIT] Consumo confirmado (commit): COD liberado para a fila de relatorios, messageId={}",
                        messageId);

                return body;
            } finally {
                // Limpa o MDC antes de devolver a thread ao pool (ver nota do produtor; evita vazamento
                // de ids entre mensagens sob alta concorrencia / ~10k rpm).
                MDC.remove("messageId");
                MDC.remove("correlationId");
            }
        } catch (Exception e) {
            // getText()/getJMSMessageID() lancam JMSException (checada). Em SESSION_TRANSACTED, ao
            // fechar o contexto sem commit ocorre rollback automatico (a mensagem volta; COD nao e gerado).
            LOG.error("Falha ao consumir/processar mensagem de negocio — rollback aplicado", e);
            throw new IllegalStateException("Falha ao consumir mensagem de negocio", e);
        }
    }
}
