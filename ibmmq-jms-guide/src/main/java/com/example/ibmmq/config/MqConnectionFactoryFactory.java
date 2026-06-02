package com.example.ibmmq.config;

import com.ibm.msg.client.wmq.WMQConstants;
import com.ibm.mq.jms.MQConnectionFactory;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.messaginghub.pooled.jms.JmsPoolConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.JMSException;

/**
 * Micronaut factory that produces the TWO role-based JMS connection factories used across the
 * application (ADR-0006 — role-based connection factories).
 *
 * <p>Both factories share the same base {@link MQConnectionFactory} (IBM MQ client, CLIENT mode)
 * built by {@link #buildMqConnectionFactory(MqProperties)}, but they differ in pooling and lifecycle
 * because the producer and consumer have opposite connection profiles:</p>
 *
 * <ul>
 *   <li><b>Producer</b> ({@link #PRODUCER}) — a {@link JmsPoolConnectionFactory} (pooled). The send
 *       path opens a short-lived {@code JMSContext} per send, so it benefits from pooling physical
 *       connections/sessions. {@code @Primary} so the still-unqualified {@code javax.jms.ConnectionFactory}
 *       injections in the entry points resolve here without a {@code NonUniqueBeanException}. The pool
 *       bean carries {@code preDestroy="stop"} to close its connections at context shutdown.</li>
 *   <li><b>Consumer</b> ({@link #CONSUMER}) — the raw, NON-pooled {@link MQConnectionFactory}. The
 *       consumer adapter holds long-lived {@code JMSContext}s for the pod's life (no per-op churn to
 *       pool), so pooling adds no value and would only obscure the lifecycle. {@code MQConnectionFactory}
 *       has no {@code stop()} method, so this bean has NO {@code preDestroy} — shutdown is the adapter's
 *       own {@code @PreDestroy}.</li>
 * </ul>
 *
 * <p>(The single-queue grandfathered helper JavaDoc below this class stays pt-BR per ADR-0004; new
 * documentation here is English.)</p>
 */
@Factory
public class MqConnectionFactoryFactory {

    /** Qualifier for the pooled producer {@code ConnectionFactory} bean (ADR-0006). */
    public static final String PRODUCER = "producer";

    /** Qualifier for the dedicated, non-pooled consumer {@code MQConnectionFactory} bean (ADR-0006). */
    public static final String CONSUMER = "consumer";

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

        // Sharing conversations is a CLIENT on/off toggle: WMQ_SHARE_CONV_ALLOWED accepts ONLY
        // WMQ_SHARE_CONV_ALLOWED_YES (1) / _NO (0) — NOT a count. The actual SHARECNV *number* of
        // conversations multiplexed per TCP socket is a SVRCONN channel attribute negotiated
        // server-side; the client cannot request a count through this property. Passing the raw
        // count (e.g. 10) here throws JMSFMQ1006 at bean creation and crashes every harness pod on
        // startup. Enable sharing whenever more than one conversation is wanted (default 10 > 1).
        cf.setIntProperty(WMQConstants.WMQ_SHARE_CONV_ALLOWED,
                props.getSharingConversations() > 1
                        ? WMQConstants.WMQ_SHARE_CONV_ALLOWED_YES
                        : WMQConstants.WMQ_SHARE_CONV_ALLOWED_NO);

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
     * Pooled PRODUCER factory (ADR-0006). The send path opens a short-lived {@code JMSContext} per send,
     * so a pool of physical connections/sessions is the right profile here.
     *
     * <p><b>{@code @Primary}</b> is load-bearing: the three entry points still inject the unqualified
     * {@code javax.jms.ConnectionFactory}, and with two candidate factories present this is what makes
     * those injections resolve to the producer instead of throwing {@code NonUniqueBeanException}.</p>
     *
     * <p><b>Concrete return type</b> ({@link JmsPoolConnectionFactory}, not the {@code ConnectionFactory}
     * interface) so Micronaut can see the {@code stop()} method referenced by {@code preDestroy}; the bean
     * is still injectable as {@code ConnectionFactory}.</p>
     *
     * <p><b>Sizing (AC2/AC3).</b> {@code maxConnections=2} per pod: under the project topology the
     * QMgr-side {@code MAXINST} on the SVRCONN channel must satisfy
     * {@code maxConnections × replicas ≤ MAXINST} — keep the per-pod connection count small so the
     * fleet stays within {@code MAXINST} (the replica count is a deployment concern and is NOT hard-coded
     * here). {@code maxSessionsPerConnection=10} so it stays {@code ≤ SHARECNV} (10): the SVRCONN channel
     * multiplexes at most {@code SHARECNV} conversations per TCP socket, so requesting more sessions than
     * that on one connection would exceed what the channel can carry. The previous code never set this and
     * defaulted to 500, violating the {@code ≤ SHARECNV} bound.</p>
     */
    @Singleton
    @Bean(preDestroy = "stop")
    @Named(PRODUCER)
    @Primary
    public JmsPoolConnectionFactory producerConnectionFactory(MqProperties props) throws JMSException {
        MQConnectionFactory mqCf = buildMqConnectionFactory(props);

        JmsPoolConnectionFactory pool = new JmsPoolConnectionFactory();
        // setConnectionFactory accepts the javax.jms.ConnectionFactory interface.
        pool.setConnectionFactory(mqCf);
        // Physical connections per pod — keep small so maxConnections × replicas ≤ MAXINST.
        pool.setMaxConnections(2);
        // Sessions multiplexed per connection — must be ≤ SHARECNV (10) negotiated on the SVRCONN channel.
        pool.setMaxSessionsPerConnection(10);

        LOG.info("[ADR-0006] Pooled PRODUCER ConnectionFactory created "
                + "(maxConnections={}, maxSessionsPerConnection={})", 2, 10);
        return pool;
    }

    /**
     * Dedicated, NON-pooled CONSUMER factory (ADR-0006). The consumer adapter holds long-lived
     * {@code JMSContext}s for the pod's life, so there is no per-op connection churn to pool — pooling
     * would add no value and only obscure the held-context lifecycle.
     *
     * <p>Returns the raw {@link MQConnectionFactory} directly: <b>no pool, and no</b>
     * {@code @Bean(preDestroy = "stop")} — {@code MQConnectionFactory} has no {@code stop()} method, and
     * shutdown of the held contexts is the consumer adapter's own {@code @PreDestroy}.</p>
     */
    @Singleton
    @Named(CONSUMER)
    public MQConnectionFactory consumerConnectionFactory(MqProperties props) throws JMSException {
        MQConnectionFactory mqCf = buildMqConnectionFactory(props);
        LOG.info("[ADR-0006] Dedicated non-pooled CONSUMER MQConnectionFactory created");
        return mqCf;
    }
}
