package com.example.ibmmq.persistence;

import io.micronaut.context.annotation.Requires;
import io.micronaut.data.annotation.Repository;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.GenericRepository;

import java.util.List;

/**
 * Reader repository for the append-only {@code delivery_report} audit table — bound to the
 * {@code reader} (replica) datasource via {@code @Repository("reader")}.
 *
 * <p><b>Datasource binding:</b> the {@code @Repository} value names the connection/transaction manager;
 * {@code "reader"} routes every query here to the streaming-replicated hot-standby (Aurora reader
 * endpoint). This serves audit queries/projections at scale (~334 report rows/s) without loading the
 * writer/primary.</p>
 *
 * <p><b>Writer-only gating invariant (ADR-0005, F3):</b> the reader is for QUERY/PROJECTION ONLY. No
 * read whose result gates a write or a dedup/reconcile decision may run here — replica lag makes a
 * just-written row invisible for tens of ms, a wide race window at this throughput. Dedup fails closed
 * via the UNIQUE constraint on the WRITER table, never via a read against this replica. Correlation /
 * reconciliation reads stay on {@code JdbcCorrelationStore} (the writer). Reads from this repository are
 * inherently eventually-consistent — callers (e.g. the replication IT) must poll-with-timeout, never
 * assert a row is visible immediately after the write.</p>
 *
 * <p><b>Bean gating:</b> active only when the {@code reader} datasource is configured
 * ({@code datasources.reader.url} present). The committed {@code application.yml} has no datasources, so
 * this repository stays inert in unit/context tests that run a bare {@code ApplicationContext}.</p>
 */
@Requires(property = "datasources.reader.url")
@Repository("reader")
@JdbcRepository(dialect = Dialect.POSTGRES)
public interface DeliveryReportReadRepository extends GenericRepository<DeliveryReportRecord, Long> {

    /**
     * Returns every audit row for a correlation id (read from the replica — eventually consistent).
     * At most a handful of rows per correlation id (one per distinct feedback code).
     */
    List<DeliveryReportRecord> findByCorrelationId(String correlationId);

    /** Total audit rows visible on the replica (used by the replication IT's poll-with-timeout). */
    long count();
}
