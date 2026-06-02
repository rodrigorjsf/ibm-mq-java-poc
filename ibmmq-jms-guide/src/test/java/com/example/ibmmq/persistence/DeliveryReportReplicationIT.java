package com.example.ibmmq.persistence;

import com.example.ibmmq.model.ReportType;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste de integracao de REPLICACAO FISICA (issue #40, AC5) — dois containers Postgres por streaming
 * replication (primary/writer + hot-standby/replica) que prova o READ/WRITE SPLIT: a escrita vai ao
 * {@code default} (writer/primary) e a leitura ao {@code reader} (replica), com a linha visivel no
 * replica via REPLICACAO FISICA — asserido por POLL-WITH-TIMEOUT (nunca imediatamente apos a escrita,
 * por causa do lag de replica).
 *
 * <p><b>EXCLUIDO da gate {@code verify} padrao.</b> Anotado {@code @Tag("replication")}; o
 * {@code maven-failsafe-plugin} declara {@code <excludedGroups>replication</excludedGroups>}, entao
 * {@code mvn verify} NAO o executa (dois containers + streaming replication = caro e sensivel a lag,
 * improprio para a gate de CI). Rode-o sob demanda:</p>
 *
 * <pre>{@code   mvn -pl ibmmq-jms-guide verify -Preplication }</pre>
 *
 * <p>O perfil {@code -Preplication} inverte o filtro ({@code <groups>replication</groups>} + limpa o
 * {@code excludedGroups}), rodando APENAS os ITs deste grupo. O IT de persistencia DETERMINISTICO
 * ({@link DeliveryReportPersistenceIT}, um unico container, sem replicacao) e quem permanece no
 * {@code verify}.</p>
 *
 * <p><b>Recipe bitnami (registrada em research-output/micronaut-data-cqrs-readwrite-split.md):</b>
 * {@code bitnamilegacy/postgresql:16} (a Bitnami moveu as imagens free do Docker Hub para o namespace
 * {@code bitnamilegacy/} em 2025; {@code bitnami/postgresql:16*} nao e mais pullable), primary
 * {@code POSTGRESQL_REPLICATION_MODE=master}, replica
 * {@code =slave} apontando para o alias de rede do primary ({@code POSTGRESQL_MASTER_HOST}). Os dois
 * containers compartilham uma {@link Network} para o alias DNS resolver (o gotcha classico de dois
 * containers). Estrategia de "caught-up": o replica espera o log
 * {@code "database system is ready to accept read[- ]only connections"}; alem disso, a leitura usa
 * poll-with-timeout para tolerar o lag residual de replicacao.</p>
 */
@Tag("replication")
@DisplayName("Replicacao fisica Postgres: read-from-reader do delivery_report (poll-with-timeout)")
class DeliveryReportReplicationIT {

    // Bitnami moved its free Docker Hub images to the `bitnamilegacy/` namespace in 2025 (the
    // `bitnami/postgresql:16*` tags are no longer pullable). `bitnamilegacy/postgresql:16` keeps the same
    // turnkey streaming-replication env contract (POSTGRESQL_REPLICATION_MODE master/slave).
    private static final String IMAGE = "bitnamilegacy/postgresql:16";
    private static final String PRIMARY_ALIAS = "postgres-primary";
    private static final int PG_PORT = 5432;
    private static final String CORREL_ID = "ID:replication-test-0001";

    private static Network network;
    private static GenericContainer<?> primary;
    private static GenericContainer<?> replica;

    private ApplicationContext context;

    @BeforeAll
    static void startReplicatedPair() {
        network = Network.newNetwork();

        // Primary (writer) — streaming-replication master. A app escreve aqui (datasources.default).
        primary = new GenericContainer<>(IMAGE)
                .withNetwork(network)
                .withNetworkAliases(PRIMARY_ALIAS)
                .withEnv("POSTGRESQL_REPLICATION_MODE", "master")
                .withEnv("POSTGRESQL_REPLICATION_USER", "repluser")
                .withEnv("POSTGRESQL_REPLICATION_PASSWORD", "replpass")
                .withEnv("POSTGRESQL_USERNAME", "corr")
                .withEnv("POSTGRESQL_PASSWORD", "corrpass")
                .withEnv("POSTGRESQL_DATABASE", "correlation")
                .withExposedPorts(PG_PORT)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 1)
                        .withStartupTimeout(Duration.ofSeconds(90)));
        primary.start();

        // Replica (reader) — hot-standby por streaming replication. A app le aqui (datasources.reader).
        // A replicacao fisica copia o catalogo de roles, entao o usuario `corr` existe no standby sem
        // recriar. Sinal de caught-up: "ready to accept read-only connections".
        replica = new GenericContainer<>(IMAGE)
                .withNetwork(network)
                .withEnv("POSTGRESQL_REPLICATION_MODE", "slave")
                .withEnv("POSTGRESQL_REPLICATION_USER", "repluser")
                .withEnv("POSTGRESQL_REPLICATION_PASSWORD", "replpass")
                .withEnv("POSTGRESQL_MASTER_HOST", PRIMARY_ALIAS)
                .withEnv("POSTGRESQL_MASTER_PORT_NUMBER", String.valueOf(PG_PORT))
                .withEnv("POSTGRESQL_PASSWORD", "corrpass")
                .withExposedPorts(PG_PORT)
                .waitingFor(Wait.forLogMessage(
                                ".*database system is ready to accept read[- ]?only connections.*", 1)
                        .withStartupTimeout(Duration.ofSeconds(120)));
        replica.start();
    }

    @AfterAll
    static void stopReplicatedPair() {
        if (replica != null) {
            replica.stop();
        }
        if (primary != null) {
            primary.stop();
        }
        if (network != null) {
            network.close();
        }
    }

    private static String jdbcUrl(GenericContainer<?> c) {
        return "jdbc:postgresql://" + c.getHost() + ":" + c.getMappedPort(PG_PORT) + "/correlation";
    }

    @BeforeEach
    void startContext() {
        // default -> primary (writer), reader -> replica (hot-standby). Ambos com o mesmo usuario `corr`
        // (a replicacao fisica copia o catalogo de roles do primary).
        context = ApplicationContext.run(Map.ofEntries(
                Map.entry("datasources.default.url", jdbcUrl(primary)),
                Map.entry("datasources.default.driver-class-name", "org.postgresql.Driver"),
                Map.entry("datasources.default.username", "corr"),
                Map.entry("datasources.default.password", "corrpass"),
                Map.entry("datasources.reader.url", jdbcUrl(replica)),
                Map.entry("datasources.reader.driver-class-name", "org.postgresql.Driver"),
                Map.entry("datasources.reader.username", "corr"),
                Map.entry("datasources.reader.password", "corrpass")));

        // Cria a tabela no PRIMARY (writer). A replicacao fisica a propaga ao standby (sem DDL no replica).
        context.getBean(DeliveryReportSchema.class);
    }

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    @DisplayName("Escrita no writer fica visivel no reader por replicacao fisica (poll-with-timeout)")
    void writeToWriterBecomesVisibleOnReader() throws Exception {
        DeliveryReportWriteRepository writer = context.getBean(DeliveryReportWriteRepository.class);
        DeliveryReportReadRepository reader = context.getBean(DeliveryReportReadRepository.class);

        // Escreve no PRIMARY/writer.
        int inserted = writer.insertIfAbsent(
                CORREL_ID, CORREL_ID, ReportType.COD.name(), 260, Instant.now());
        assertThat(inserted).as("a insercao no writer afeta 1 linha").isEqualTo(1);

        // NUNCA asserir imediatamente: o standby recebe a linha com lag de tens de ms. Poll-with-timeout
        // (deadline loop) tolera o lag de replicacao sem flakar.
        List<DeliveryReportRecord> rowsOnReader = pollReaderUntilPresent(reader, Duration.ofSeconds(15));

        assertThat(rowsOnReader)
                .as("a linha escrita no writer aparece no reader (replica) dentro do timeout")
                .hasSize(1);
        assertThat(rowsOnReader.get(0).feedback()).isEqualTo(260);
        assertThat(rowsOnReader.get(0).reportType()).isEqualTo(ReportType.COD);
    }

    /**
     * Le o repo READER (replica) repetidamente ate a linha do {@code CORREL_ID} aparecer ou o deadline
     * expirar. Read-from-reader e eventualmente consistente (lag de replica), por isso o poll em vez de
     * uma leitura unica imediatamente apos a escrita.
     */
    private static List<DeliveryReportRecord> pollReaderUntilPresent(DeliveryReportReadRepository reader,
                                                                     Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        List<DeliveryReportRecord> rows = reader.findByCorrelationId(CORREL_ID);
        while (rows.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(250L);
            rows = reader.findByCorrelationId(CORREL_ID);
        }
        return rows;
    }
}
