package com.example.ibmmq.correlation;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the shared {@link CorrelationStoreContract} against the {@link JdbcCorrelationStore} arm —
 * proving the {@code default recordReport} reconciliation behaves identically on the Postgres-backed
 * primitives (UPSERT...RETURNING + conditional DELETE) as it does on the in-memory ones.
 *
 * <p>A failsafe {@code *IT} (requires Docker; no broker). It reuses the {@code JdbcCorrelationStoreIT}
 * lifecycle pattern: a single {@code postgres:16-alpine} {@link GenericContainer} (the Testcontainers
 * 2.0.5 core does not publish the {@code postgresql} module) with a manual {@code @BeforeAll}/
 * {@code @AfterAll} lifecycle, and one {@link ApplicationContext} run with {@code correlation.store=jdbc}
 * + the {@code datasources.default.*} so the {@link CorrelationStore} bean resolves to the JDBC store.</p>
 *
 * <p><b>Shared-table isolation:</b> all matrix methods exercise ONE Postgres table. The
 * {@link CorrelationStoreContract} assertions deliberately use per-id {@code findByMessageId} rather
 * than global {@code pendingCount()}, and {@link #newStore()} clears the contract's fixed ids via
 * {@code store.remove(...)} before each test, so a stub left by (say) the orphan case never pollutes a
 * later method. We clear through the store's own {@code remove} (which manages its raw JDBC connection
 * post-unwrap) rather than a test-side {@code getConnection()}, which would hit the #40
 * {@code DelegatingDataSource} {@code NoConnectionException} trap.</p>
 */
@DisplayName("Contrato de recordReport — adaptador Jdbc (Postgres Testcontainer)")
class JdbcCorrelationStoreContractIT extends CorrelationStoreContract {

    private static final int POSTGRES_PORT = 5432;

    // Mirror of the fixed ids used by CorrelationStoreContract — cleared before each test so the shared
    // Postgres table starts clean for every matrix method (no cross-method pollution).
    private static final String[] CONTRACT_IDS = {
            "ID:414d51204d513120202020202020202000000abc",
            "ID:414d51204d513120202020202020202000000fff"
    };

    private static GenericContainer<?> postgres;
    private static ApplicationContext ctx;
    private static CorrelationStore store;

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

        String jdbcUrl = "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(POSTGRES_PORT) + "/correlation";
        ctx = ApplicationContext.run(Map.ofEntries(
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
                Map.entry("datasources.default.password", "corrpass")));

        store = ctx.getBean(CorrelationStore.class);
        // The contract is only meaningful against the JDBC adapter here — guard the wiring.
        assertThat(store)
                .as("com correlation.store=jdbc o bean deve ser o JdbcCorrelationStore")
                .isInstanceOf(JdbcCorrelationStore.class);
    }

    @AfterAll
    static void stopPostgres() {
        if (ctx != null) {
            ctx.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void clearContractIds() {
        // Clear the fixed ids the contract uses, so each matrix method runs against a clean slate on the
        // shared table. remove() is idempotent (DELETE of an absent row affects zero rows).
        for (String id : CONTRACT_IDS) {
            store.remove(id);
        }
    }

    @Override
    protected CorrelationStore newStore() {
        // Single shared, persistent store (one Postgres table); per-test isolation comes from
        // clearContractIds() above plus the contract's per-id assertions.
        return store;
    }
}
