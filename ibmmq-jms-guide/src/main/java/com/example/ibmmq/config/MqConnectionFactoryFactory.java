package com.example.ibmmq.config;

import com.ibm.msg.client.wmq.WMQConstants;
import com.ibm.mq.jms.MQConnectionFactory;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import org.messaginghub.pooled.jms.JmsPoolConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.JMSException;

/**
 * Fabrica Micronaut que produz a {@link javax.jms.ConnectionFactory} JMS usada por toda a aplicacao.
 *
 * <p>Estrutura: um {@link MQConnectionFactory} (cliente IBM MQ, modo CLIENT) configurado via
 * {@code WMQConstants}, embrulhado por um {@link JmsPoolConnectionFactory} (pool de conexoes javax).
 * O pool reaproveita conexoes/sessions, essencial em ambientes com muitos {@code createContext}.</p>
 *
 * <p>O bean do pool tem {@code preDestroy="stop"} para fechar as conexoes no shutdown do contexto.</p>
 */
@Factory
public class MqConnectionFactoryFactory {

    private static final Logger LOG = LoggerFactory.getLogger(MqConnectionFactoryFactory.class);

    /**
     * Cria e configura o {@link MQConnectionFactory} base (sem pool).
     *
     * <p>Todas as chaves vem de {@code WMQConstants} (interface que herda as constantes de
     * {@code CommonConstants}/{@code JmsConstants}). Os valores foram verificados contra o bytecode
     * de {@code com.ibm.mq.allclient:9.4.5.0}.</p>
     */
    private MQConnectionFactory buildMqConnectionFactory(MqProperties props) throws JMSException {
        MQConnectionFactory cf = new MQConnectionFactory();

        // Modo CLIENT (TCP/IP via SVRCONN). WMQ_CM_CLIENT = 1.
        cf.setIntProperty(WMQConstants.WMQ_CONNECTION_MODE, WMQConstants.WMQ_CM_CLIENT);

        // Canal SVRCONN e gerenciador de filas.
        cf.setStringProperty(WMQConstants.WMQ_CHANNEL, props.getChannel());
        cf.setStringProperty(WMQConstants.WMQ_QUEUE_MANAGER, props.getQueueManager());

        // Endereco: prefira CONNAME list (necessaria para reconexao a outro QMgr). Caso contrario,
        // host+porta simples.
        if (props.hasConnectionNameList()) {
            // Formato: host(port),host(port). Tem precedencia sobre host/port.
            cf.setStringProperty(WMQConstants.WMQ_CONNECTION_NAME_LIST, props.getConnectionNameList());
        } else {
            cf.setStringProperty(WMQConstants.WMQ_HOST_NAME, props.getHost());
            cf.setIntProperty(WMQConstants.WMQ_PORT, props.getPort());
        }

        // Nome da aplicacao (visivel em DIS CONN / monitoramento). Campo APPLICATIONNAME -> chave APPNAME.
        cf.setStringProperty(WMQConstants.WMQ_APPLICATIONNAME, props.getApplicationName());

        // Numero de conversas compartilhadas por socket (SHARECNV). Reduz sockets em alta concorrencia.
        cf.setIntProperty(WMQConstants.WMQ_SHARE_CONV_ALLOWED, props.getSharingConversations());

        // ---- Autenticacao MQCSP (user/senha) ----
        if (props.hasCredentials()) {
            // USER_AUTHENTICATION_MQCSP e boolean e NAO tem prefixo WMQ_ (herdado de JmsConstants).
            // true = enviar credenciais via MQCSP (flow de autenticacao moderno).
            cf.setBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP, true);
            cf.setStringProperty(WMQConstants.USERID, props.getUser());
            cf.setStringProperty(WMQConstants.PASSWORD, props.getPassword());
        }

        // ---- Reconexao automatica do cliente ----
        // Exige TRANSPORT=CLIENT + CONNAMELIST (ou CCDT). DISABLED desliga; valor ANY reconecta a
        // qualquer QMgr da lista.
        int reconnectOption = props.isReconnectEnabled()
                ? WMQConstants.WMQ_CLIENT_RECONNECT      // = MQCNO_RECONNECT (qualquer QMgr)
                : WMQConstants.WMQ_CLIENT_RECONNECT_DISABLED;
        cf.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS, reconnectOption);
        if (props.isReconnectEnabled()) {
            // Timeout em segundos para desistir da reconexao (chave String que recebe int).
            cf.setIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_TIMEOUT, props.getReconnectTimeoutSeconds());
        }

        // ---- TLS ----
        // Definir o CipherSuite e o que HABILITA TLS no CF. Evite ciphers TLS_RSA_* (desabilitados
        // a partir do Java 25). Keystore/truststore via -Djavax.net.ssl.* (JSSE default).
        if (props.isTlsEnabled() && props.getSslCipherSuite() != null && !props.getSslCipherSuite().isBlank()) {
            cf.setSSLCipherSuite(props.getSslCipherSuite());
            LOG.info("TLS habilitado com CipherSuite={}", props.getSslCipherSuite());
        }

        LOG.info("MQConnectionFactory configurado: qmgr={}, channel={}, conname={}, mqcsp={}, reconnect={}",
                props.getQueueManager(), props.getChannel(),
                props.hasConnectionNameList() ? props.getConnectionNameList()
                        : props.getHost() + "(" + props.getPort() + ")",
                props.hasCredentials(), props.isReconnectEnabled());

        return cf;
    }

    /**
     * Produz a {@link javax.jms.ConnectionFactory} efetivamente injetada — o pool envolvendo o CF do MQ.
     *
     * <p>{@code @Bean(preDestroy = "stop")}: ao destruir o contexto, o Micronaut chama
     * {@link JmsPoolConnectionFactory#stop()}, fechando as conexoes do pool.</p>
     */
    @Singleton
    @Bean(preDestroy = "stop")
    public JmsPoolConnectionFactory connectionFactory(MqProperties props) throws JMSException {
        // Tipo de retorno concreto (nao a interface ConnectionFactory) para que o Micronaut enxergue
        // o metodo stop() referenciado em preDestroy. O bean continua injetavel como ConnectionFactory.
        MQConnectionFactory mqCf = buildMqConnectionFactory(props);

        JmsPoolConnectionFactory pool = new JmsPoolConnectionFactory();
        // setConnectionFactory aceita Object (a interface javax.jms.ConnectionFactory).
        pool.setConnectionFactory(mqCf);
        // Limita o numero de conexoes fisicas; sessions sao multiplexadas por conexao.
        pool.setMaxConnections(8);

        LOG.info("JmsPoolConnectionFactory criado (maxConnections={})", 8);
        return pool;
    }
}
