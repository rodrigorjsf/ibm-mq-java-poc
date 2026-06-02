package com.example.ibmmq.correlation;

import com.example.ibmmq.model.PendingMessage;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Teste de integracao DETERMINISTICO (requer Docker; SEM broker) que prova que o
 * {@link JdbcCorrelationStore} — o ledger de reconciliacao exactly-once validado live no k3d —
 * continua operando depois que o issue #40 colocou {@code micronaut-data-jdbc} no classpath.
 *
 * <p><b>O risco que este IT trava (a regressao SEV-1 do #40):</b> com {@code micronaut-data-jdbc}
 * presente, toda injecao de {@code javax.sql.DataSource} passa a ser embrulhada pelo
 * {@code DelegatingDataSourceResolver} do Micronaut Data, e um {@code getConnection()} cru FORA de um
 * escopo {@code @Connectable}/{@code @Transactional} lanca {@code NoConnectionException}. O
 * {@link JdbcCorrelationStore} faz JDBC cru (sem advice do Micronaut Data), entao ele desembrulha o
 * DataSource no construtor ({@code DelegatingDataSource.unwrapDataSource}). Este IT exercita o ciclo
 * completo de reconciliacao contra um Postgres real para garantir que o store NAO regrediu —
 * cobertura que nem o {@code CoaCodEndToEndIT} (usa o store em memoria) nem os testes unitarios tem.
 * Ver {@code research-output/micronaut-data-cqrs-readwrite-split.md} (F6).</p>
 *
 * <p>Container unico {@code postgres:16-alpine} (alinhado ao harness k3s), lifecycle manual
 * ({@code @BeforeAll}/{@code @AfterAll}) com {@code GenericContainer} do core (o Testcontainers 2.0.5
 * nao publica o modulo {@code postgresql}). Nomeado {@code *IT} para o failsafe roda-lo em
 * {@code mvn verify}.</p>
 */
@DisplayName("JdbcCorrelationStore opera com micronaut-data-jdbc no classpath (DataSource desembrulhado) — AC2 #40")
class JdbcCorrelationStoreIT {

    private static final String MESSAGE_ID = "ID:414d51204d513120202020202020202000000099";
    private static final int POSTGRES_PORT = 5432;

    private static GenericContainer<?> postgres;

    @BeforeAll
    static void startPostgres() {
        postgres = new GenericContainer<>("postgres:16-alpine")
                .withEnv("POSTGRES_USER", "corr")
                .withEnv("POSTGRES_PASSWORD", "corrpass")
                .withEnv("POSTGRES_DB", "correlation")
                .withExposedPorts(POSTGRES_PORT)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2)
                        .withStartupTimeout(Duration.ofSeconds(60)));
        postgres.start();
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static Map<String, Object> jdbcStoreProperties() {
        String jdbcUrl = "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(POSTGRES_PORT) + "/correlation";
        return Map.ofEntries(
                // Pin a valid ibm-mq.password so the context boots under the eager @Context validation of
                // MqProperties (issue #27 / ADR-0011): the bean is validated at startup and would otherwise
                // refuse to boot when IBM_MQ_PASSWORD is exported empty (user=app + blank password).
                Map.entry("ibm-mq.password", "passw0rd"),
                // Activates JdbcCorrelationStore (the shared, Postgres-backed reconciliation ledger).
                Map.entry("correlation.store", "jdbc"),
                // Writer/primary = `default` (the bare DataSource the store injects resolves here).
                Map.entry("datasources.default.url", jdbcUrl),
                Map.entry("datasources.default.driver-class-name", "org.postgresql.Driver"),
                Map.entry("datasources.default.username", "corr"),
                Map.entry("datasources.default.password", "corrpass"));
    }

    @Test
    @DisplayName("Ciclo register -> markCoa -> markCod -> removeIfFullyConfirmed funciona (JDBC cru pos-unwrap)")
    void reconcileCycleWorksWithDataJdbcOnTheClasspath() {
        try (ApplicationContext ctx = ApplicationContext.run(jdbcStoreProperties())) {
            // O store deve resolver para o JdbcCorrelationStore (e seu @PostConstruct ensureSchema —
            // CREATE TABLE via JDBC cru — deve rodar sem NoConnectionException: prova o unwrap no startup).
            CorrelationStore store = ctx.getBean(CorrelationStore.class);
            assertThat(store)
                    .as("com correlation.store=jdbc o bean deve ser o JdbcCorrelationStore")
                    .isInstanceOf(JdbcCorrelationStore.class);

            // Cada metodo abaixo abre uma conexao JDBC crua — se o DataSource ainda estivesse embrulhado
            // pelo proxy contextual do Micronaut Data, qualquer um lancaria NoConnectionException.
            assertThatCode(() -> {
                store.register(PendingMessage.newlySent(MESSAGE_ID, "pedido-99", "{\"k\":\"v\"}"));

                assertThat(store.pendingCount())
                        .as("uma pendencia registrada, ainda sem confirmacoes")
                        .isEqualTo(1);
                assertThat(store.findByMessageId(MESSAGE_ID))
                        .as("a pendencia recem-registrada deve ser encontrada pelo MessageId")
                        .isPresent();

                store.markCoaReceived(MESSAGE_ID);
                store.markCodReceived(MESSAGE_ID);

                // COA+COD confirmados: exatamente esta chamada remove a linha (DELETE condicional atomico).
                assertThat(store.removeIfFullyConfirmed(MESSAGE_ID))
                        .as("com COA+COD confirmados, removeIfFullyConfirmed deve remover a linha")
                        .isTrue();
                assertThat(store.pendingCount())
                        .as("apos a reconciliacao a contagem de pendencias drena a zero")
                        .isZero();
            }).as("JDBC cru do JdbcCorrelationStore nao deve lancar com micronaut-data-jdbc presente")
                    .doesNotThrowAnyException();
        }
    }
}
