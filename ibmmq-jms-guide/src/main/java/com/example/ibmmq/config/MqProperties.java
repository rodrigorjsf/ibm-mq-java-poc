package com.example.ibmmq.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import io.micronaut.validation.Validated;
import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Configuracao do IBM MQ, vinculada ao bloco {@code ibm-mq:} do {@code application.yml}.
 *
 * <p>Mapeada por Micronaut via {@code @ConfigurationProperties("ibm-mq")}. Cada propriedade kebab-case
 * no YAML (ex. {@code queue-manager}) vincula ao setter camelCase correspondente.</p>
 *
 * <p><b>Fail-fast validation at startup (issue #27 / ADR-0011).</b> The bean is annotated
 * {@code @Context} so the container instantiates AND validates it at context startup rather than lazily —
 * misconfiguration then refuses to boot loudly instead of surfacing as an {@code MQRC_*} at connect time.
 * {@code @Context} here is <b>validation-only</b>: this bean opens no MQ connection (its
 * {@code @PostConstruct} only throws on violation), so it does not reintroduce the eager-connect hang that
 * an eager connecting singleton caused earlier in the project.</p>
 *
 * <p>Validation runs in two redundant layers, on purpose:</p>
 * <ul>
 *   <li><b>jakarta bean-validation</b> ({@code @Validated} + {@code @Min}/{@code @Max}/{@code @NotBlank})
 *       fires through the container's validation path at startup.</li>
 *   <li><b>{@link #validateCrossFieldConstraints()}</b> — a public {@code @PostConstruct} method that
 *       re-checks the structural constraints AND enforces the cross-field rules (credential and TLS cipher)
 *       that a single-field annotation cannot express. Keeping it public lets a pure-unit test invoke it
 *       directly without booting a context.</li>
 * </ul>
 *
 * <p>The bean stays a <b>mutable</b> {@code @ConfigurationProperties} class (NOT a record): Micronaut
 * binding writes each property through its setter.</p>
 */
@Context
@Validated
@ConfigurationProperties("ibm-mq")
public class MqProperties {

    // ---- Endereco do gerenciador de filas (modo CLIENT / SVRCONN) ----
    /** Host do listener MQ (usado se connectionNameList estiver vazio). */
    private String host = "localhost";
    /** Porta do listener MQ (default 1414). */
    @Min(value = 1, message = "ibm-mq.port: port must be between 1 and 65535")
    @Max(value = 65535, message = "ibm-mq.port: port must be between 1 and 65535")
    private int port = 1414;
    /** Canal SVRCONN usado pelo cliente. */
    @NotBlank(message = "ibm-mq.channel: channel must not be blank")
    private String channel = "DEV.APP.SVRCONN";
    /** Nome do gerenciador de filas. */
    @NotBlank(message = "ibm-mq.queue-manager: queueManager must not be blank")
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

    /**
     * Fail-fast cross-field (and structural) validation, invoked at context startup via
     * {@code @PostConstruct} because {@code MqProperties} is {@code @Context} (issue #27 / ADR-0011).
     *
     * <p>Public on purpose: a pure-unit test can call it directly on a {@code new MqProperties()} (which is
     * NOT container-managed and therefore never runs this method on its own) to assert each rule, without
     * booting an {@code ApplicationContext}. The structural checks below are intentionally redundant with the
     * jakarta {@code @Min}/{@code @Max}/{@code @NotBlank} annotations on the fields: the annotations fire only
     * through the container's {@code @Validated} path, so re-checking them here keeps the direct-call path and
     * the context-start path in agreement.</p>
     *
     * <p>Rules enforced:</p>
     * <ul>
     *   <li><b>Structural</b> — {@code port} in {@code [1, 65535]}; {@code channel} and {@code queueManager}
     *       non-blank.</li>
     *   <li><b>Credential</b> — if {@code user} is non-blank then {@code password} is required. A blank
     *       {@code user} means no-auth (MQCSP off) and the rule does not fire, preserving the no-auth
     *       contract.</li>
     *   <li><b>TLS cipher</b> — when {@code tlsEnabled} is true the {@code sslCipherSuite} must be non-blank
     *       and must NOT be a {@code TLS_RSA_*} suite (those are disabled from Java 25 — see ADR-0001). When
     *       TLS is off the cipher field is ignored.</li>
     * </ul>
     *
     * @throws IllegalArgumentException with a clear, field-scoped message on the first violation found.
     */
    @PostConstruct
    public void validateCrossFieldConstraints() {
        // ---- Structural (redundant with the jakarta annotations; see method JavaDoc) ----
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(
                    "ibm-mq.port: port must be between 1 and 65535, but was " + port);
        }
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("ibm-mq.channel: channel must not be blank");
        }
        if (queueManager == null || queueManager.isBlank()) {
            throw new IllegalArgumentException("ibm-mq.queue-manager: queueManager must not be blank");
        }

        // ---- Credential cross-field: a non-blank user requires a password (no-auth = blank user) ----
        boolean userSet = user != null && !user.isBlank();
        boolean passwordSet = password != null && !password.isBlank();
        if (userSet && !passwordSet) {
            throw new IllegalArgumentException(
                    "ibm-mq.password: a password is required when ibm-mq.user is set "
                            + "(user='" + user + "'); leave the user blank for a no-auth deployment");
        }

        // ---- TLS cipher cross-field: TLS on requires a non-blank, non-TLS_RSA_* cipher (ADR-0001) ----
        if (tlsEnabled) {
            if (sslCipherSuite == null || sslCipherSuite.isBlank()) {
                throw new IllegalArgumentException(
                        "ibm-mq.ssl-cipher-suite: a cipher suite is required when ibm-mq.tls-enabled=true");
            }
            if (sslCipherSuite.startsWith("TLS_RSA_")) {
                throw new IllegalArgumentException(
                        "ibm-mq.ssl-cipher-suite: TLS_RSA_* cipher suites are disabled from Java 25 "
                                + "(ADR-0001) and must not be used; was '" + sslCipherSuite + "'");
            }
        }
    }
}
