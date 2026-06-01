package com.example.ibmmq.correlation;

import com.example.ibmmq.model.PendingMessage;
import io.micronaut.context.annotation.Requires;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * Shared, persistent {@link CorrelationStore} backed by a relational database (Postgres in the k3s
 * harness) via plain JDBC. This is the cluster-wide store that makes correlation reconcile across
 * competing-consumer pods: every pod reads/writes the SAME {@code pending_message} table, so a COA
 * observed by one report-consumer pod and a COD observed by another still reconcile to a single row.
 *
 * <p><b>Why JDBC (not Micronaut Data {@code @JdbcRepository}):</b> hand-written SQL keeps the
 * compile surface minimal (no extra annotation-processor path wired into the compiler plugin) and
 * makes the exactly-once/idempotency semantics explicit and auditable. We only need five small
 * statements.</p>
 *
 * <p><b>Schema</b> (created lazily by {@link #ensureSchema()} on startup — safe for N replicas via
 * {@code CREATE TABLE IF NOT EXISTS}):</p>
 * <pre>
 *   CREATE TABLE pending_message (
 *       message_id   VARCHAR(256) PRIMARY KEY,
 *       business_key VARCHAR(256),
 *       payload      TEXT,
 *       sent_at      TIMESTAMP    NOT NULL,
 *       coa_received BOOLEAN      NOT NULL DEFAULT FALSE,
 *       cod_received BOOLEAN      NOT NULL DEFAULT FALSE
 *   );
 * </pre>
 *
 * <p><b>Idempotency (reports are at-least-once):</b> the queue manager may redeliver a COA/COD
 * report, and competing report-consumers may process the same report concurrently. Every mutation
 * here is naturally idempotent:</p>
 * <ul>
 *   <li>{@link #register} uses {@code INSERT ... ON CONFLICT (message_id) DO NOTHING} — a duplicate
 *       send/registration is a no-op, never a primary-key violation.</li>
 *   <li>{@link #markCoaReceived}/{@link #markCodReceived} are {@code UPDATE ... SET flag = TRUE}:
 *       setting an already-true flag to TRUE again is a no-op (no double-count, no side effect).</li>
 *   <li>{@link #remove} is {@code DELETE WHERE message_id = ?}: deleting an absent row affects zero
 *       rows — harmless if two pods both reconcile the same fully-confirmed message.</li>
 * </ul>
 *
 * <p><b>AC2 ordering limitation (documented, not silently shipped):</b> {@link CorrelationStore}'s
 * "reconcile + remove on full confirmation" lives in {@code ReportMessageConsumer} and only removes
 * in the COD branch gated on {@code isFullyConfirmed()}. Across competing report-consumers, COA and
 * COD can be processed out of order on different pods; if COD lands before COA, the row is marked COD
 * but not removed (not yet fully confirmed), and the later COA branch does not remove it either. The
 * row is then a correctly-marked-but-not-removed pending row, eventually swept by an operator query
 * (see {@code deploy/k3s/README.md}). The data is never lost or double-counted — each message is
 * confirmed exactly once per flag — but {@code pendingCount()} may not reach 0 under out-of-order
 * delivery without the sweep. Tightening this to consumer-side "remove when both flags TRUE
 * regardless of which branch sees it last" is a deliberate follow-up (it would change
 * {@code ReportMessageConsumer}, out of scope for this slice).</p>
 *
 * <p><b>Bean gating:</b> active only when {@code correlation.store=jdbc} (set by the harness
 * ConfigMap). The complementary {@link InMemoryCorrelationStore} loads otherwise, so exactly one
 * {@link CorrelationStore} bean exists in any configuration. Requires a {@link DataSource} bean,
 * provided by {@code micronaut-jdbc-hikari} + a {@code datasources.*} config (env-driven in the
 * harness; intentionally absent from the committed {@code application.yml} so unit-test contexts stay
 * inert).</p>
 */
@Singleton
@Requires(property = "correlation.store", value = "jdbc")
public class JdbcCorrelationStore implements CorrelationStore {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcCorrelationStore.class);

    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS pending_message (
                message_id   VARCHAR(256) PRIMARY KEY,
                business_key VARCHAR(256),
                payload      TEXT,
                sent_at      TIMESTAMP    NOT NULL,
                coa_received BOOLEAN      NOT NULL DEFAULT FALSE,
                cod_received BOOLEAN      NOT NULL DEFAULT FALSE
            )""";

    // INSERT idempotente: uma re-registro (mesmo message_id) e no-op, nao um erro de PK.
    private static final String INSERT_SQL = """
            INSERT INTO pending_message (message_id, business_key, payload, sent_at, coa_received, cod_received)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (message_id) DO NOTHING""";

    private static final String SELECT_SQL = """
            SELECT message_id, business_key, payload, sent_at, coa_received, cod_received
            FROM pending_message WHERE message_id = ?""";

    private static final String UPDATE_COA_SQL =
            "UPDATE pending_message SET coa_received = TRUE WHERE message_id = ?";
    private static final String UPDATE_COD_SQL =
            "UPDATE pending_message SET cod_received = TRUE WHERE message_id = ?";
    private static final String DELETE_SQL =
            "DELETE FROM pending_message WHERE message_id = ?";

    // Conta apenas as pendencias ainda nao totalmente confirmadas (coerente com InMemory.pendingCount).
    private static final String COUNT_PENDING_SQL =
            "SELECT COUNT(*) FROM pending_message WHERE NOT (coa_received AND cod_received)";

    private final DataSource dataSource;

    public JdbcCorrelationStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Cria a tabela se ainda nao existir. Roda no startup de cada pod; {@code CREATE TABLE IF NOT
     * EXISTS} e seguro sob N replicas concorrentes (o primeiro cria, os demais sao no-op).
     */
    @PostConstruct
    void ensureSchema() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(DDL)) {
            ps.execute();
            LOG.info("[stage=STORE-INIT] Esquema do store de correlacao garantido (pending_message)");
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao garantir o esquema do store de correlacao JDBC", e);
        }
    }

    @Override
    public void register(PendingMessage pending) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
            ps.setString(1, pending.messageId());
            ps.setString(2, pending.businessKey());
            ps.setString(3, pending.payload());
            ps.setTimestamp(4, Timestamp.from(pending.sentAt()));
            ps.setBoolean(5, pending.coaReceived());
            ps.setBoolean(6, pending.codReceived());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao registrar pendencia: " + pending.messageId(), e);
        }
    }

    @Override
    public Optional<PendingMessage> findByMessageId(String messageId) {
        if (messageId == null) {
            return Optional.empty();
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_SQL)) {
            ps.setString(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao buscar pendencia: " + messageId, e);
        }
    }

    @Override
    public Optional<PendingMessage> markCoaReceived(String messageId) {
        return markFlag(messageId, UPDATE_COA_SQL);
    }

    @Override
    public Optional<PendingMessage> markCodReceived(String messageId) {
        return markFlag(messageId, UPDATE_COD_SQL);
    }

    /**
     * Aplica um UPDATE idempotente do flag e devolve a pendencia atualizada (lida de volta na mesma
     * conexao). Setar um flag ja TRUE para TRUE de novo e no-op — seguro sob entrega at-least-once
     * de relatorios e consumidores concorrentes.
     */
    private Optional<PendingMessage> markFlag(String messageId, String updateSql) {
        if (messageId == null) {
            return Optional.empty();
        }
        try (Connection conn = dataSource.getConnection()) {
            int affected;
            try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                ps.setString(1, messageId);
                affected = ps.executeUpdate();
            }
            if (affected == 0) {
                // Pendencia desconhecida (ex. relatorio orfao): no-op, coerente com o InMemory.
                return Optional.empty();
            }
            try (PreparedStatement ps = conn.prepareStatement(SELECT_SQL)) {
                ps.setString(1, messageId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao marcar flag de relatorio: " + messageId, e);
        }
    }

    @Override
    public void remove(String messageId) {
        if (messageId == null) {
            return;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(DELETE_SQL)) {
            ps.setString(1, messageId);
            // DELETE de uma linha ausente afeta 0 linhas — idempotente sob reconciliacao concorrente.
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao remover pendencia: " + messageId, e);
        }
    }

    @Override
    public int pendingCount() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(COUNT_PENDING_SQL);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao contar pendencias", e);
        }
    }

    private static PendingMessage mapRow(ResultSet rs) throws SQLException {
        Timestamp sentAt = rs.getTimestamp("sent_at");
        Instant sentInstant = sentAt != null ? sentAt.toInstant() : Instant.now();
        return new PendingMessage(
                rs.getString("message_id"),
                rs.getString("business_key"),
                rs.getString("payload"),
                sentInstant,
                rs.getBoolean("coa_received"),
                rs.getBoolean("cod_received"));
    }
}
