package com.example.ibmmq.persistence;

import com.example.ibmmq.config.MqProperties;
import com.example.ibmmq.consumer.ReportMessageConsumer;
import com.example.ibmmq.correlation.CorrelationStore;
import com.example.ibmmq.messaging.ReceivePort;
import com.example.ibmmq.messaging.ReportEnvelope;
import com.example.ibmmq.model.PendingMessage;
import com.example.ibmmq.model.ReportType;
import com.example.ibmmq.report.MqmdTimestamps;
import com.example.ibmmq.report.ReportDescriptor;
import com.example.ibmmq.report.ReportFeedbackRouter;
import io.micronaut.context.ApplicationContext;
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import javax.sql.DataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

/**
 * Teste de integracao DETERMINISTICO (requer Docker; SEM broker) da persistencia append-only do
 * {@code delivery_report} (issue #40, AC3 + AC4 + AC5-deterministico).
 *
 * <p><b>Por que UM unico container Postgres (sem replicacao)?</b> Este IT prova a LOGICA de
 * persistencia — "o consumidor escreve uma linha por relatorio?" e "uma redelivery e idempotente?" —
 * que NAO depende do split fisico writer/replica. Mantendo-o num unico container ele e deterministico
 * (sem lag de replica) e permanece na gate {@code verify} padrao (o IT de replicacao fisica,
 * {@link DeliveryReportReplicationIT}, e {@code @Tag("replication")} e excluido do {@code verify}). Os
 * datasources {@code default} (writer) E {@code reader} apontam para o MESMO container aqui: numa unica
 * instancia a leitura e imediatamente consistente, entao asserimos via o repo reader SEM
 * poll-with-timeout.</p>
 *
 * <p><b>Por que {@code GenericContainer} (e nao {@code PostgreSQLContainer})?</b> O Testcontainers 2.x
 * (travado em 2.0.5 por compatibilidade com Docker engine 29.x — ver CLAUDE.md) nao publica mais o
 * modulo dedicado {@code org.testcontainers:postgresql}, entao usamos o {@code GenericContainer} do core
 * com a imagem {@code postgres:16-alpine} (a mesma do harness k3s), construindo a URL JDBC a partir da
 * porta mapeada. Lifecycle manual ({@code @BeforeAll}/{@code @AfterAll}), o mesmo padrao do
 * {@code CoaCodEndToEndIT} com o {@code MQContainer}.</p>
 *
 * <p><b>O que cobre:</b>
 * <ul>
 *   <li><b>AC3:</b> {@code ReportMessageConsumer.handleReport} de um COA e de um COD persiste UMA linha
 *       de auditoria cada (uma por relatorio recebido).</li>
 *   <li><b>AC4:</b> a redelivery de um relatorio (entrega at-least-once da QM / dois competing consumers)
 *       NAO cria linha duplicada (UNIQUE {@code (correlation_id, feedback)} + {@code INSERT ... ON
 *       CONFLICT DO NOTHING}) e NAO lanca excecao no caminho ja-ackado.</li>
 * </ul>
 *
 * <p>Nomeado {@code *IT} para que o <b>failsafe</b> (e nao o surefire) o execute em {@code mvn verify}.</p>
 */
@DisplayName("Persistencia append-only de delivery_report (DETERMINISTICO, um Postgres, sem broker)")
class DeliveryReportPersistenceIT {

    private static final String CORREL_ID = "ID:414d51204d513120202020202020202000000001";
    private static final int POSTGRES_PORT = 5432;

    // Imagem Postgres alinhada com o harness k3s (postgres:16-alpine, ver deploy/k3s/20-postgres.yaml).
    private static GenericContainer<?> postgres;

    private ApplicationContext context;
    private ReportMessageConsumer reportConsumer;
    private DeliveryReportReadRepository readRepository;

    @BeforeAll
    static void startPostgres() {
        postgres = new GenericContainer<>("postgres:16-alpine")
                .withEnv("POSTGRES_USER", "corr")
                .withEnv("POSTGRES_PASSWORD", "corrpass")
                .withEnv("POSTGRES_DB", "correlation")
                .withExposedPorts(POSTGRES_PORT)
                // O log "database system is ready to accept connections" e emitido DUAS vezes (o primeiro
                // e durante o bootstrap interno, antes de o servidor estar acessivel pela porta). Exigir
                // duas ocorrencias evita conectar cedo demais; combinado com a espera pela porta TCP.
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

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(POSTGRES_PORT) + "/correlation";
    }

    @BeforeEach
    void startContext() {
        // default (writer) E reader apontam para o MESMO container: leitura imediatamente consistente
        // (sem replicacao), entao o IT e deterministico e nao precisa de poll-with-timeout.
        // correlation.store NAO e jdbc: usamos o InMemoryCorrelationStore para a correlacao (o foco
        // deste IT e a persistencia append-only, nao a reconciliacao — que tem cobertura propria).
        String jdbcUrl = jdbcUrl();
        context = ApplicationContext.run(Map.ofEntries(
                // Pin a valid ibm-mq.password so the context boots under the eager @Context validation of
                // MqProperties (issue #27 / ADR-0011); this IT exercises persistence, not MQ, but the
                // MqProperties bean is still validated at startup and would otherwise refuse to boot when
                // IBM_MQ_PASSWORD is exported empty (user=app + blank password trips the credential rule).
                Map.entry("ibm-mq.password", "passw0rd"),
                Map.entry("datasources.default.url", jdbcUrl),
                Map.entry("datasources.default.driver-class-name", "org.postgresql.Driver"),
                Map.entry("datasources.default.username", "corr"),
                Map.entry("datasources.default.password", "corrpass"),
                Map.entry("datasources.reader.url", jdbcUrl),
                Map.entry("datasources.reader.driver-class-name", "org.postgresql.Driver"),
                Map.entry("datasources.reader.username", "corr"),
                Map.entry("datasources.reader.password", "corrpass")));

        // Tocar o bean de schema dispara o @PostConstruct ensureSchema() contra o container vivo
        // (CREATE TABLE IF NOT EXISTS + UNIQUE(correlation_id, feedback)). Os @Singleton sao lazy, entao
        // pedimos o bean explicitamente para garantir que a tabela exista antes do primeiro INSERT.
        context.getBean(DeliveryReportSchema.class);

        // Isolamento entre testes: o container e @BeforeAll (uma instancia por classe) e a tabela e
        // CREATE TABLE IF NOT EXISTS (nunca recriada), entao linhas de um teste vazariam para o proximo —
        // e o JUnit 5 NAO roda os metodos em ordem de fonte. Como os tres testes usam o MESMO correlationId
        // e a mesma keyspace (correlation_id, feedback), truncamos a tabela writer aqui para que cada teste
        // comece limpo (DataSource cru = `default` = writer, o alvo correto).
        truncateDeliveryReport();

        DeliveryReportWriteRepository writeRepository = context.getBean(DeliveryReportWriteRepository.class);
        readRepository = context.getBean(DeliveryReportReadRepository.class);

        // Correlation store em memoria — registra a pendencia para que a correlacao reversa
        // (originalMsgId) e a reconciliacao no consumer funcionem sem um Postgres-backed store.
        CorrelationStore store = context.getBean(CorrelationStore.class);
        store.register(PendingMessage.newlySent(CORREL_ID, "pedido-40", "{}"));

        // A ReceivePort nao e exercitada por handleReport(envelope) — o IT dirige o consumer diretamente
        // com um ReportEnvelope decodificado (sem broker), entao um mock da porta basta.
        reportConsumer = new ReportMessageConsumer(
                mock(ReceivePort.class), new MqProperties(), store,
                new ReportFeedbackRouter(), writeRepository);
    }

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    /** Esvazia a tabela writer entre testes (DataSource cru resolve para o `default` = writer). */
    private void truncateDeliveryReport() {
        // Unwrap Micronaut Data's contextual DataSource proxy before raw JDBC (same reason as the
        // production beans): a plain getConnection() on the wrapped DataSource throws NoConnectionException.
        DataSource rawDataSource = DelegatingDataSource.unwrapDataSource(context.getBean(DataSource.class));
        try (Connection conn = rawDataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("TRUNCATE TABLE delivery_report");
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao truncar delivery_report entre testes", e);
        }
    }

    // ---- Stubbed MQMD values (issue #19): the same on every report so the assertions are deterministic.
    // CORREL_ID hex bytes are arbitrary-but-fixed; APPL_IDENTITY/ACCOUNTING_TOKEN/MSG_ID are fixed fixtures.
    private static final String APPL_IDENTITY = "APP.IDENTITY.40";
    private static final byte[] ACCOUNTING_TOKEN = new byte[]{
            0x16, 0x01, 0x05, 0x15, 0x00, 0x00, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05,
            0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11,
            0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19}; // 32 bytes (MQ_ACCOUNTING_TOKEN_LENGTH)
    private static final byte[] CORREL_ID_BYTES = new byte[]{(byte) 0x41, (byte) 0x4d, 0x51, 0x20};
    private static final byte[] MSG_ID_BYTES = new byte[]{(byte) 0xab, (byte) 0xcd, (byte) 0xef, 0x01};
    private static final String PUT_DATE = "20260531";
    private static final String PUT_TIME = "13300050"; // 13:30:00.500 UTC

    private static final String ACCOUNTING_TOKEN_HEX = "160105150000000102030405060708090a0b0c0d0e0f10111213141516171819";

    private static final ReportFeedbackRouter ROUTER = new ReportFeedbackRouter();

    /**
     * Builds a FULL {@link ReportEnvelope} (NOT {@link ReportEnvelope#synthetic}, which would null five of
     * the six MQMD fields) carrying all six recovered #19 values, exactly as the production
     * {@code PooledJmsReceiveAdapter} would extract them under {@code mdReadEnabled=true}. The report-type
     * char is DERIVED from the feedback (259 -> COA -> 'A', 260 -> COD -> 'D') so the persisted
     * {@code report_type_char} assertion holds; {@code putTimestampUtc} is parsed by {@link MqmdTimestamps}
     * (the same path production uses) so it equals the asserted {@code 2026-05-31T13:30:00.500}.
     */
    private static ReportEnvelope reportWithFeedback(int feedback) {
        char reportTypeChar = ROUTER.classify(feedback).toDomainChar();
        LocalDateTime putTimestampUtc = MqmdTimestamps.parse(PUT_DATE, PUT_TIME);
        ReportDescriptor descriptor = new ReportDescriptor(
                APPL_IDENTITY, ACCOUNTING_TOKEN, CORREL_ID_BYTES, MSG_ID_BYTES, putTimestampUtc, reportTypeChar);
        return new ReportEnvelope(feedback, CORREL_ID, "", descriptor);
    }

    @Test
    @DisplayName("COA e COD persistem UMA linha de auditoria cada (uma por relatorio recebido) — AC3")
    void coaAndCodEachPersistOneAuditRow() {
        reportConsumer.handleReport(reportWithFeedback(259)); // MQFB_COA
        reportConsumer.handleReport(reportWithFeedback(260)); // MQFB_COD

        List<DeliveryReportRecord> rows = readRepository.findByCorrelationId(CORREL_ID);
        assertThat(rows)
                .as("um COA e um COD persistidos = duas linhas de auditoria para o correlationId")
                .hasSize(2);
        assertThat(rows).extracting(DeliveryReportRecord::reportType)
                .as("uma linha COA e uma linha COD")
                .containsExactlyInAnyOrder(ReportType.COA, ReportType.COD);
        assertThat(rows).extracting(DeliveryReportRecord::feedback)
                .containsExactlyInAnyOrder(259, 260);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.correlationId()).isEqualTo(CORREL_ID);
            assertThat(row.originalMessageId()).isEqualTo(CORREL_ID);
            assertThat(row.observedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("COA e COD persistem ADITIVAMENTE os seis campos MQMD recuperados (issue #19) — AC5")
    void coaAndCodPersistRecoveredMqmdFields() {
        reportConsumer.handleReport(reportWithFeedback(259)); // MQFB_COA
        reportConsumer.handleReport(reportWithFeedback(260)); // MQFB_COD

        List<DeliveryReportRecord> rows = readRepository.findByCorrelationId(CORREL_ID);
        assertThat(rows).as("um COA e um COD = duas linhas").hasSize(2);

        // Os seis campos sao comuns a ambas as linhas (mesmos stubs); o report_type_char difere por tipo.
        for (DeliveryReportRecord row : rows) {
            assertThat(row.applIdentityData())
                    .as("appl_identity_data recuperado").isEqualTo(APPL_IDENTITY);
            assertThat(row.accountingTokenHex())
                    .as("accounting_token_hex recuperado (32 bytes => 64 hex chars)")
                    .isEqualTo(ACCOUNTING_TOKEN_HEX)
                    .hasSize(64);
            assertThat(row.correlationIdBytesHex())
                    .as("correlation_id_bytes_hex recuperado").isEqualTo("414d5120");
            assertThat(row.messageIdBytesHex())
                    .as("message_id_bytes_hex recuperado").isEqualTo("abcdef01");
            assertThat(row.putTimestampUtc())
                    .as("put_timestamp_utc recuperado como relogio-de-parede UTC (sem vazamento de zona)")
                    .isEqualTo(LocalDateTime.parse("2026-05-31T13:30:00.500"));
        }

        // report_type_char: 'A' para o COA, 'D' para o COD.
        DeliveryReportRecord coaRow = rows.stream()
                .filter(r -> r.reportType() == ReportType.COA).findFirst().orElseThrow();
        DeliveryReportRecord codRow = rows.stream()
                .filter(r -> r.reportType() == ReportType.COD).findFirst().orElseThrow();
        assertThat(coaRow.reportTypeChar()).as("report_type_char do COA").isEqualTo("A");
        assertThat(codRow.reportTypeChar()).as("report_type_char do COD").isEqualTo("D");
    }

    @Test
    @DisplayName("Redelivery do MESMO relatorio NAO cria duplicata e NAO lanca (idempotente) — AC4")
    void redeliveryOfSameReportIsIdempotentAndDoesNotThrow() {
        ReportEnvelope coa = reportWithFeedback(259); // MQFB_COA

        // Primeira entrega: persiste uma linha.
        reportConsumer.handleReport(coa);
        assertThat(readRepository.findByCorrelationId(CORREL_ID))
                .as("a primeira entrega do COA persiste exatamente uma linha")
                .hasSize(1);

        // Redelivery (entrega at-least-once / dois competing consumers): NAO deve lancar...
        assertThatCode(() -> {
            reportConsumer.handleReport(coa);
            reportConsumer.handleReport(coa);
        }).as("a redelivery no caminho ja-ackado nunca lanca (INSERT ... ON CONFLICT DO NOTHING)")
                .doesNotThrowAnyException();

        // ...e NAO deve criar linha duplicada (UNIQUE correlation_id, feedback).
        assertThat(readRepository.findByCorrelationId(CORREL_ID))
                .as("a redelivery e idempotente: continua exatamente UMA linha (sem duplicata)")
                .hasSize(1);
    }

    @Test
    @DisplayName("insertIfAbsent retorna 1 na primeira insercao e 0 na duplicata (ON CONFLICT DO NOTHING)")
    void insertIfAbsentReturnsRowsAffected() {
        DeliveryReportWriteRepository writeRepository =
                context.getBean(DeliveryReportWriteRepository.class);

        int first = writeRepository.insertIfAbsent(
                CORREL_ID, CORREL_ID, ReportType.COD.name(), 260, Instant.now());
        int duplicate = writeRepository.insertIfAbsent(
                CORREL_ID, CORREL_ID, ReportType.COD.name(), 260, Instant.now());

        assertThat(first).as("a primeira insercao afeta 1 linha").isEqualTo(1);
        assertThat(duplicate).as("a duplicata afeta 0 linhas (DO NOTHING), sem lancar").isZero();
    }
}
