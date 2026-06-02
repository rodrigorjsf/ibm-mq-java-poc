package com.example.ibmmq.correlation;

import org.junit.jupiter.api.DisplayName;

/**
 * Runs the shared {@link CorrelationStoreContract} against the {@link InMemoryCorrelationStore} arm.
 *
 * <p>A surefire {@code *Test} (unit, no broker/container): each test gets a fresh
 * {@link InMemoryCorrelationStore} via {@link #newStore()}, so the matrix runs in full isolation. The
 * Jdbc arm of the same contract lives in {@code JdbcCorrelationStoreContractIT} (failsafe, Postgres
 * Testcontainer) — proving the {@code default recordReport} reconciliation behaves identically on both
 * adapters' differing primitives.</p>
 */
@DisplayName("Contrato de recordReport — adaptador InMemory")
class InMemoryCorrelationStoreContractTest extends CorrelationStoreContract {

    @Override
    protected CorrelationStore newStore() {
        return new InMemoryCorrelationStore();
    }
}
