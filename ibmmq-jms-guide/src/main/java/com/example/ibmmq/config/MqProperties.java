package com.example.ibmmq.config;

import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * Configuracao do IBM MQ, vinculada ao bloco {@code ibm-mq:} do {@code application.yml}.
 *
 * <p>Mapeada por Micronaut via {@code @ConfigurationProperties("ibm-mq")}. Cada propriedade kebab-case
 * no YAML (ex. {@code queue-manager}) vincula ao setter camelCase correspondente.</p>
 */
@ConfigurationProperties("ibm-mq")
public class MqProperties {

    // ---- Endereco do gerenciador de filas (modo CLIENT / SVRCONN) ----
    /** Host do listener MQ (usado se connectionNameList estiver vazio). */
    private String host = "localhost";
    /** Porta do listener MQ (default 1414). */
    private int port = 1414;
    /** Canal SVRCONN usado pelo cliente. */
    private String channel = "DEV.APP.SVRCONN";
    /** Nome do gerenciador de filas. */
    private String queueManager = "QM1";

    /**
     * Lista CONNAME para multi-instancia / reconexao, formato {@code host(port),host(port)}.
     * Se preenchida, tem precedencia sobre host/port (necessaria para reconexao a outro QMgr).
     */
    private String connectionNameList = "";

    // ---- Autenticacao (MQCSP) ----
    /** Usuario MQCSP (em branco = sem autenticacao por user/senha). */
    private String user = "app";
    /** Senha MQCSP. */
    private String password = "";

    /** Nome da aplicacao (aparece em DIS CONN / monitoramento). */
    private String applicationName = "ibmmq-jms-guide";

    // ---- Filas ----
    /** Fila de negocio (destino das mensagens). */
    private String businessQueue = "DEV.QUEUE.1";
    /** Fila de relatorios (JMSReplyTo — recebe COA/COD/etc). */
    private String reportQueue = "DEV.QUEUE.2";

    // ---- Reconexao ----
    /** Habilita reconexao automatica do cliente (MQCNO_RECONNECT). */
    private boolean reconnectEnabled = true;
    /** Timeout de reconexao em segundos (default IBM = 1800). */
    private int reconnectTimeoutSeconds = 1800;
    /** Numero de conversas compartilhadas por canal TCP (SHARECNV). */
    private int sharingConversations = 10;

    // ---- TLS ----
    /** Liga TLS. Quando true, sslCipherSuite deve estar definido (e o que habilita TLS no CF). */
    private boolean tlsEnabled = false;
    /**
     * CipherSuite TLS (ex. {@code TLS_AES_256_GCM_SHA384} para TLS 1.3). Evite ciphers TLS_RSA_*
     * (desabilitados a partir do Java 25). Vazio = TLS desligado.
     */
    private String sslCipherSuite = "";

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getQueueManager() {
        return queueManager;
    }

    public void setQueueManager(String queueManager) {
        this.queueManager = queueManager;
    }

    public String getConnectionNameList() {
        return connectionNameList;
    }

    public void setConnectionNameList(String connectionNameList) {
        this.connectionNameList = connectionNameList;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getApplicationName() {
        return applicationName;
    }

    public void setApplicationName(String applicationName) {
        this.applicationName = applicationName;
    }

    public String getBusinessQueue() {
        return businessQueue;
    }

    public void setBusinessQueue(String businessQueue) {
        this.businessQueue = businessQueue;
    }

    public String getReportQueue() {
        return reportQueue;
    }

    public void setReportQueue(String reportQueue) {
        this.reportQueue = reportQueue;
    }

    public boolean isReconnectEnabled() {
        return reconnectEnabled;
    }

    public void setReconnectEnabled(boolean reconnectEnabled) {
        this.reconnectEnabled = reconnectEnabled;
    }

    public int getReconnectTimeoutSeconds() {
        return reconnectTimeoutSeconds;
    }

    public void setReconnectTimeoutSeconds(int reconnectTimeoutSeconds) {
        this.reconnectTimeoutSeconds = reconnectTimeoutSeconds;
    }

    public int getSharingConversations() {
        return sharingConversations;
    }

    public void setSharingConversations(int sharingConversations) {
        this.sharingConversations = sharingConversations;
    }

    public boolean isTlsEnabled() {
        return tlsEnabled;
    }

    public void setTlsEnabled(boolean tlsEnabled) {
        this.tlsEnabled = tlsEnabled;
    }

    public String getSslCipherSuite() {
        return sslCipherSuite;
    }

    public void setSslCipherSuite(String sslCipherSuite) {
        this.sslCipherSuite = sslCipherSuite;
    }

    /** Conveniencia: true quando user e password estao ambos preenchidos (habilita MQCSP). */
    public boolean hasCredentials() {
        return user != null && !user.isBlank() && password != null && !password.isBlank();
    }

    /** Conveniencia: true quando ha uma connectionNameList configurada. */
    public boolean hasConnectionNameList() {
        return connectionNameList != null && !connectionNameList.isBlank();
    }
}
