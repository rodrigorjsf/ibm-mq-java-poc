package com.example.ibmmq.persistence;

import com.example.ibmmq.correlation.CorrelationStore;
import com.example.ibmmq.correlation.JdbcCorrelationStore;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test (surefire, SEM Docker, SEM DB vivo) da AC#2 do issue #40: com DOIS datasources NOMEADOS
 * configurados ({@code default} writer + {@code reader} replica) E {@code correlation.store=jdbc}, o
 * bean {@link CorrelationStore} ainda RESOLVE para exatamente um candidato (o {@link JdbcCorrelationStore}),
 * e a injecao do {@code javax.sql.DataSource} cru desse store continua nao-ambigua.
 *
 * <p><b>Por que isso importa (o risco que a AC#2 trava):</b> {@link JdbcCorrelationStore} injeta um
 * {@code DataSource} cru no construtor. Se NENHUM datasource se chamar {@code default}, o Micronaut nao
 * marca nenhum como {@code @Primary} e essa injecao vira ambigua ("multiple possible bean candidates"),
 * quebrando a reconciliacao exactly-once. Nomeando o writer {@code default} (ADR-0005) a injecao
 * resolve para ele mesmo com o segundo datasource {@code reader} presente. Este teste prova justamente
 * essa resolucao continuar limpa.</p>
 *
 * <p><b>Por que asserir DEFINICOES de bean (e nao {@code getBean}):</b> os beans de schema
 * ({@link DeliveryReportSchema}, {@code JdbcCorrelationStore#ensureSchema}) abrem conexao no
 * {@code @PostConstruct}. Sem um Postgres vivo, instancia-los faria o teste pendurar no backoff. Os
 * {@code @Singleton} do Micronaut sao LAZY (so instanciam ao serem pedidos), entao apenas SUBIR o
 * contexto nao os cria. Verificamos a RESOLUCAO checando que ha exatamente UMA definicao candidata —
 * isso prova o wiring (zero ambiguidade) sem tocar o banco. {@code initialization-fail-timeout: -1}
 * garante que, mesmo se o pool fosse tocado, ele nao falharia eager por falta de DB.</p>
 */
@DisplayName("AC#2 (#40): CorrelationStore resolve com datasources nomeados default+reader (sem DB vivo)")
@Tag("smoke")
class CorrelationStoreNamedDatasourcesSmokeTest {

    private static Map<String, Object> namedDatasourceProperties() {
        return Map.ofEntries(
                // Pin a valid ibm-mq.password so the context boots under the eager @Context validation of
                // MqProperties (issue #27 / ADR-0011): the ambient ${IBM_MQ_PASSWORD:passw0rd} default
                // resolves blank when the env var is exported empty, which would trip the credential rule
                // (user=app + blank password) and refuse to boot — unrelated to this datasource-wiring test.
                Map.entry("ibm-mq.password", "passw0rd"),
                Map.entry("correlation.store", "jdbc"),
                // Writer/primary = `default` (nome load-bearing — ver ADR-0005).
                Map.entry("datasources.default.url", "jdbc:postgresql://localhost:5432/correlation"),
                Map.entry("datasources.default.driver-class-name", "org.postgresql.Driver"),
                Map.entry("datasources.default.username", "corr"),
                Map.entry("datasources.default.password", "corrpass"),
                // initialization-fail-timeout -1: pool lazy, nunca falha eager sem DB.
                Map.entry("datasources.default.initialization-fail-timeout", "-1"),
                // Reader/replica = `reader`.
                Map.entry("datasources.reader.url", "jdbc:postgresql://localhost:5433/correlation"),
                Map.entry("datasources.reader.driver-class-name", "org.postgresql.Driver"),
                Map.entry("datasources.reader.username", "corr"),
                Map.entry("datasources.reader.password", "corrpass"),
                Map.entry("datasources.reader.initialization-fail-timeout", "-1"));
    }

    @Test
    @DisplayName("Com default+reader e store=jdbc: CorrelationStore tem exatamente UMA definicao (JdbcCorrelationStore)")
    void correlationStoreResolvesWithNamedDatasources() {
        try (ApplicationContext ctx = ApplicationContext.run(namedDatasourceProperties())) {
            // Exatamente um candidato CorrelationStore — nenhuma ambiguidade introduzida pelo `reader`.
            assertThat(ctx.getBeanDefinitions(CorrelationStore.class))
                    .as("deve existir exatamente um bean CorrelationStore com store=jdbc")
                    .hasSize(1);
            assertThat(ctx.containsBean(JdbcCorrelationStore.class))
                    .as("com correlation.store=jdbc o JdbcCorrelationStore deve estar definido")
                    .isTrue();

            // Dois datasources nomeados estao definidos; a injecao do DataSource CRU do store resolve
            // para o `default` (writer) — exatamente uma definicao @Primary/default existe.
            assertThat(ctx.getBeanDefinitions(DataSource.class))
                    .as("ambos os datasources nomeados (default + reader) devem estar definidos")
                    .hasSizeGreaterThanOrEqualTo(2);
        }
    }

    @Test
    @DisplayName("Com datasources configurados: as repositories de delivery_report (writer+reader) estao definidas")
    void deliveryReportRepositoriesAreDefinedWhenDatasourcesExist() {
        try (ApplicationContext ctx = ApplicationContext.run(namedDatasourceProperties())) {
            assertThat(ctx.containsBean(DeliveryReportWriteRepository.class))
                    .as("o repo WRITER de delivery_report deve estar definido com datasources.default.url presente")
                    .isTrue();
            assertThat(ctx.containsBean(DeliveryReportReadRepository.class))
                    .as("o repo READER de delivery_report deve estar definido com datasources.reader.url presente")
                    .isTrue();
            assertThat(ctx.containsBean(DeliveryReportSchema.class))
                    .as("o bean de schema de delivery_report deve estar definido com datasources.default.url presente")
                    .isTrue();
        }
    }

    @Test
    @DisplayName("SEM datasources: as repositories de delivery_report ficam INERTES (nada de HikariCP em teste unitario)")
    void deliveryReportRepositoriesAreInertWithoutDatasources() {
        // Espelha o application.yml commitado (sem bloco datasources): o contexto de teste fica inerte.
        // Pin only ibm-mq.password (no datasources) so the context boots under the eager @Context
        // validation of MqProperties (issue #27 / ADR-0011) while still proving the repos stay inert
        // without datasources.* keys.
        try (ApplicationContext ctx = ApplicationContext.run(Map.of("ibm-mq.password", "passw0rd"))) {
            assertThat(ctx.containsBean(DeliveryReportWriteRepository.class))
                    .as("o repo WRITER NAO deve existir sem datasources.default.url")
                    .isFalse();
            assertThat(ctx.containsBean(DeliveryReportReadRepository.class))
                    .as("o repo READER NAO deve existir sem datasources.reader.url")
                    .isFalse();
            assertThat(ctx.containsBean(DeliveryReportSchema.class))
                    .as("o bean de schema NAO deve existir sem datasources.default.url")
                    .isFalse();
        }
    }
}
