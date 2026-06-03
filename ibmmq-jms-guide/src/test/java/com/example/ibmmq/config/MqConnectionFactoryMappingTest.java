package com.example.ibmmq.config;

import com.ibm.msg.client.wmq.WMQConstants;
import com.ibm.mq.jms.MQConnectionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.jms.JMSException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Broker-free mapping test: proves the {@code MqProperties} → IBM MQ {@link MQConnectionFactory} mapping
 * across EVERY branch WITHOUT a broker and WITHOUT an {@code ApplicationContext} (issue #27, AC1).
 *
 * <p><b>How it stays broker-free.</b> Building an {@link MQConnectionFactory} opens no socket — the
 * connection is lazy (it happens only at {@code createContext}). We call the production factory method
 * {@link MqConnectionFactoryFactory#consumerConnectionFactory(MqProperties)} DIRECTLY on a plain
 * {@code new MqConnectionFactoryFactory()} (the factory has no constructor dependencies) and read the
 * resulting CF back through its public getters ({@code getStringProperty}/{@code getIntProperty}/
 * {@code getBooleanProperty}/{@code getSSLCipherSuite}). There is NO inspectable-plan object — the assertions
 * read the REAL configured CF.</p>
 *
 * <p>Calling the method directly (rather than resolving the CONSUMER bean from a context) also sidesteps the
 * eager {@code @Context} validation added in #27: a {@code new MqProperties()} is not container-managed, so
 * we can set any branch combination — including deliberately credential-less ones — without the boot-time
 * validation rejecting them. The consumer factory returns the raw {@link MQConnectionFactory} (no pool),
 * which is the same CF the producer pool wraps, so the mapping under test is identical for both roles.</p>
 */
@DisplayName("MqConnectionFactory — mapeamento config->CF por branch, broker-free, via getters do CF (AC1)")
class MqConnectionFactoryMappingTest {

    private final MqConnectionFactoryFactory factory = new MqConnectionFactoryFactory();

    /**
     * A minimally valid {@link MqProperties} (host/port, authenticated, no TLS, reconnect on). Each nest
     * mutates one or two fields to exercise its branch. Credential-less branches clear user+password
     * explicitly.
     */
    private static MqProperties baseProps() {
        MqProperties props = new MqProperties();
        props.setHost("mq.example.com");
        props.setPort(1414);
        props.setChannel("DEV.APP.SVRCONN");
        props.setQueueManager("QM1");
        props.setConnectionNameList("");
        props.setUser("app");
        props.setPassword("passw0rd");
        props.setApplicationName("ibmmq-jms-guide");
        props.setReconnectEnabled(true);
        props.setReconnectTimeoutSeconds(1800);
        props.setSharingConversations(10);
        props.setTlsEnabled(false);
        props.setSslCipherSuite("");
        return props;
    }

    private MQConnectionFactory build(MqProperties props) throws JMSException {
        return factory.consumerConnectionFactory(props);
    }

    @Nested
    @DisplayName("Branch de endereco: CONNAME list tem precedencia sobre host/port")
    class AddressBranch {

        @Test
        @DisplayName("connectionNameList preenchida -> WMQ_CONNECTION_NAME_LIST setado; host/port NAO definem o endereco")
        void connameListTakesPrecedence() throws JMSException {
            MqProperties props = baseProps();
            props.setConnectionNameList("hostA(1414),hostB(1414)");

            MQConnectionFactory cf = build(props);

            assertThat(cf.getStringProperty(WMQConstants.WMQ_CONNECTION_NAME_LIST))
                    .as("a non-empty connectionNameList must map to WMQ_CONNECTION_NAME_LIST")
                    .isEqualTo("hostA(1414),hostB(1414)");
        }

        @Test
        @DisplayName("connectionNameList vazia -> WMQ_HOST_NAME + WMQ_PORT setados a partir de host/port")
        void hostPortUsedWhenNoConnameList() throws JMSException {
            MqProperties props = baseProps();
            props.setConnectionNameList("");
            props.setHost("mq.example.com");
            props.setPort(1515);

            MQConnectionFactory cf = build(props);

            assertThat(cf.getStringProperty(WMQConstants.WMQ_HOST_NAME))
                    .as("with no connectionNameList the host must map to WMQ_HOST_NAME")
                    .isEqualTo("mq.example.com");
            assertThat(cf.getIntProperty(WMQConstants.WMQ_PORT))
                    .as("with no connectionNameList the port must map to WMQ_PORT")
                    .isEqualTo(1515);
        }
    }

    @Nested
    @DisplayName("Branch de MQCSP: credenciais ligam USER_AUTHENTICATION_MQCSP e setam USERID/PASSWORD")
    class MqcspBranch {

        @Test
        @DisplayName("user+password preenchidos -> USER_AUTHENTICATION_MQCSP=true, USERID/PASSWORD setados")
        void credentialsEnableMqcsp() throws JMSException {
            MqProperties props = baseProps();
            props.setUser("app");
            props.setPassword("passw0rd");

            MQConnectionFactory cf = build(props);

            assertThat(cf.getBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP))
                    .as("with credentials present, MQCSP authentication must be enabled")
                    .isTrue();
            assertThat(cf.getStringProperty(WMQConstants.USERID))
                    .as("the configured user must map to USERID")
                    .isEqualTo("app");
            assertThat(cf.getStringProperty(WMQConstants.PASSWORD))
                    .as("the configured password must map to PASSWORD")
                    .isEqualTo("passw0rd");
        }

        @Test
        @DisplayName("user em branco -> MQCSP NAO e habilitado (branch no-auth)")
        void blankUserDoesNotEnableMqcsp() throws JMSException {
            MqProperties props = baseProps();
            props.setUser("");
            props.setPassword("");

            MQConnectionFactory cf = build(props);

            // On the no-auth branch the factory never SETS the MQCSP flag. An unset boolean property may
            // either read back as its default (false) or throw — assert it is NOT enabled, tolerant of both.
            boolean mqcspEnabled;
            Throwable readError = catchThrowable(
                    () -> cf.getBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP));
            if (readError != null) {
                mqcspEnabled = false; // property was never set -> treated as not enabled
            } else {
                mqcspEnabled = cf.getBooleanProperty(WMQConstants.USER_AUTHENTICATION_MQCSP);
            }
            assertThat(mqcspEnabled)
                    .as("with a blank user, MQCSP authentication must NOT be enabled (no-auth branch)")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("Branch de reconexao: opcoes WMQ_CLIENT_RECONNECT_OPTIONS + timeout quando ligada")
    class ReconnectBranch {

        @Test
        @DisplayName("reconnect on -> WMQ_CLIENT_RECONNECT + timeout setado")
        void reconnectEnabledSetsOptionAndTimeout() throws JMSException {
            MqProperties props = baseProps();
            props.setReconnectEnabled(true);
            props.setReconnectTimeoutSeconds(900);

            MQConnectionFactory cf = build(props);

            assertThat(cf.getIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS))
                    .as("reconnect enabled must map to WMQ_CLIENT_RECONNECT")
                    .isEqualTo(WMQConstants.WMQ_CLIENT_RECONNECT);
            assertThat(cf.getIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_TIMEOUT))
                    .as("the reconnect timeout (seconds) must map to WMQ_CLIENT_RECONNECT_TIMEOUT")
                    .isEqualTo(900);
        }

        @Test
        @DisplayName("reconnect off -> WMQ_CLIENT_RECONNECT_DISABLED")
        void reconnectDisabledSetsDisabledOption() throws JMSException {
            MqProperties props = baseProps();
            props.setReconnectEnabled(false);

            MQConnectionFactory cf = build(props);

            assertThat(cf.getIntProperty(WMQConstants.WMQ_CLIENT_RECONNECT_OPTIONS))
                    .as("reconnect disabled must map to WMQ_CLIENT_RECONNECT_DISABLED")
                    .isEqualTo(WMQConstants.WMQ_CLIENT_RECONNECT_DISABLED);
        }
    }

    @Nested
    @DisplayName("Branch de TLS: sslCipherSuite setado quando ligado, ausente quando desligado")
    class TlsBranch {

        @Test
        @DisplayName("tls on + cipher moderno -> getSSLCipherSuite retorna o cipher")
        void tlsEnabledSetsCipherSuite() throws JMSException {
            MqProperties props = baseProps();
            props.setTlsEnabled(true);
            props.setSslCipherSuite("TLS_AES_256_GCM_SHA384");

            MQConnectionFactory cf = build(props);

            assertThat(cf.getSSLCipherSuite())
                    .as("with TLS on, the configured cipher must be set on the CF")
                    .isEqualTo("TLS_AES_256_GCM_SHA384");
        }

        @Test
        @DisplayName("tls off -> getSSLCipherSuite null ou em branco (TLS nao habilitado no CF)")
        void tlsDisabledLeavesCipherUnset() throws JMSException {
            MqProperties props = baseProps();
            props.setTlsEnabled(false);
            props.setSslCipherSuite("");

            MQConnectionFactory cf = build(props);

            assertThat(cf.getSSLCipherSuite())
                    .as("with TLS off, the CF must carry no cipher suite (TLS not enabled)")
                    .isNullOrEmpty();
        }
    }

    @Nested
    @DisplayName("Branch de share-conversations: YES quando >1, NO quando ==1 (bug do PR-38)")
    class ShareConvBranch {

        @Test
        @DisplayName("sharingConversations>1 -> WMQ_SHARE_CONV_ALLOWED_YES (e NAO a contagem crua)")
        void moreThanOneEnablesSharing() throws JMSException {
            MqProperties props = baseProps();
            props.setSharingConversations(10);

            MQConnectionFactory cf = build(props);

            assertThat(cf.getIntProperty(WMQConstants.WMQ_SHARE_CONV_ALLOWED))
                    .as("a count > 1 must map to the YES flag, NOT the raw count (PR-38 regression guard)")
                    .isEqualTo(WMQConstants.WMQ_SHARE_CONV_ALLOWED_YES);
        }

        @Test
        @DisplayName("sharingConversations==1 -> WMQ_SHARE_CONV_ALLOWED_NO")
        void exactlyOneDisablesSharing() throws JMSException {
            MqProperties props = baseProps();
            props.setSharingConversations(1);

            MQConnectionFactory cf = build(props);

            assertThat(cf.getIntProperty(WMQConstants.WMQ_SHARE_CONV_ALLOWED))
                    .as("a count == 1 must map to the NO flag")
                    .isEqualTo(WMQConstants.WMQ_SHARE_CONV_ALLOWED_NO);
        }
    }

    @Test
    @DisplayName("Propriedades fixas sempre setadas: CONNECTION_MODE=CLIENT, CHANNEL, QUEUE_MANAGER")
    void fixedPropertiesAlwaysSet() throws JMSException {
        MqProperties props = baseProps();

        MQConnectionFactory cf = build(props);

        assertThat(cf.getIntProperty(WMQConstants.WMQ_CONNECTION_MODE))
                .as("the connection mode must always be CLIENT")
                .isEqualTo(WMQConstants.WMQ_CM_CLIENT);
        assertThat(cf.getStringProperty(WMQConstants.WMQ_CHANNEL))
                .as("the configured channel must always map to WMQ_CHANNEL")
                .isEqualTo("DEV.APP.SVRCONN");
        assertThat(cf.getStringProperty(WMQConstants.WMQ_QUEUE_MANAGER))
                .as("the configured queueManager must always map to WMQ_QUEUE_MANAGER")
                .isEqualTo("QM1");
    }
}
