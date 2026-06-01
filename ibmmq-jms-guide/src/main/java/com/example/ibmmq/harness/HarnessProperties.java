package com.example.ibmmq.harness;

import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * Configuracao do harness distribuido (k3s), vinculada ao bloco {@code harness:} do
 * {@code application.yml} / variaveis de ambiente.
 *
 * <p>Uma UNICA imagem serve os tres papeis (publisher / business-consumer / report-consumer); o papel
 * efetivo de cada pod e selecionado por {@code harness.role} (mapeado de uma env via ConfigMap). Os
 * beans runner sao gated por {@code @Requires(property="harness.role", value=...)}, entao apenas o
 * runner do papel configurado e instanciado — e nenhum runner instancia em um contexto de teste
 * unitario (onde {@code harness.role} esta ausente), evitando abrir JMS durante os testes.</p>
 */
@ConfigurationProperties("harness")
public class HarnessProperties {

    /**
     * Papel deste pod: {@code publisher}, {@code business-consumer} ou {@code report-consumer}.
     * Ausente (default) = nenhum runner ativo (modo biblioteca/teste).
     */
    private String role = "";

    /** Intervalo (ms) entre envios do publisher. Default: ~10 msg/s por pod publisher. */
    private long publishIntervalMillis = 100L;

    /** Prefixo do businessKey gerado pelo publisher (ajuda a identificar o pod de origem nos logs). */
    private String businessKeyPrefix = "harness";

    /** Timeout (ms) de cada receive() dos consumidores. Loop volta a esperar ao expirar sem mensagem. */
    private long receiveTimeoutMillis = 5_000L;

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public long getPublishIntervalMillis() {
        return publishIntervalMillis;
    }

    public void setPublishIntervalMillis(long publishIntervalMillis) {
        this.publishIntervalMillis = publishIntervalMillis;
    }

    public String getBusinessKeyPrefix() {
        return businessKeyPrefix;
    }

    public void setBusinessKeyPrefix(String businessKeyPrefix) {
        this.businessKeyPrefix = businessKeyPrefix;
    }

    public long getReceiveTimeoutMillis() {
        return receiveTimeoutMillis;
    }

    public void setReceiveTimeoutMillis(long receiveTimeoutMillis) {
        this.receiveTimeoutMillis = receiveTimeoutMillis;
    }
}
