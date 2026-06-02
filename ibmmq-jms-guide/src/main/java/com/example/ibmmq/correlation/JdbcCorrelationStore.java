package com.example.ibmmq.correlation;

import com.example.ibmmq.model.PendingMessage;
import io.micronaut.context.annotation.Requires;
import io.micronaut.data.connection.jdbc.advice.DelegatingDataSource;
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
 *   <li>{@link #register} uses {@code INSERT ... ON CONFLICT (message_id) DO UPDATE} of the DESCRIPTIVE
 *       fields only (never the flags) — a duplicate registration is harmless, and if a report already
 *       created a stub (below) register backfills business_key/payload/sent_at without clearing it.</li>
 *   <li>{@link #markCoaReceived}/{@link #markCodReceived} are {@code INSERT ... ON CONFLICT DO UPDATE SET
 *       flag = TRUE} (UPSERT): they CREATE the row as a stub if a report arrives before {@code register}
 *       (the QM emits the COA on arrival, processable before register — which only has the MsgId AFTER
 *       send), else set the flag; setting an already-true flag again is a no-op (no double-count).</li>
 *   <li>{@link #remove} / {@link #removeIfFullyConfirmed} are {@code DELETE}s: deleting an absent row
 *       affects zero rows — harmless if two pods both reconcile the same fully-confirmed message.</li>
 * </ul>
 *
 * <p><b>AC2 order-independent reconciliation:</b> {@link #removeIfFullyConfirmed} runs an atomic
 * {@code DELETE ... WHERE coa_received AND cod_received}, and {@code ReportMessageConsumer} calls it in
 * BOTH the COA and COD branches — so whichever report completes the pair removes the row, even when COA
 * and COD are processed out of order on different competing-consumer pods. Concurrent callers race
 * safely (exactly one DELETE affects the row; the rest affect zero rows). {@code pendingCount()}
 * therefore drains to zero cluster-wide with NO operator sweep, while each flag is still set exactly
 * once (idempotent upserts). One rare cold-start edge remains — if BOTH reports reconcile and remove the
 * row before {@code register} commits, register re-inserts a flagless orphan; see
 * {@code deploy/k3s/README.md} "Known limitations &amp; follow-ups".</p>
 *
 * <p><b>Known reconciliation limitations (best-effort ledger, by design):</b> two further residual cases
 * are accepted rather than swept, because {@code pending_message} is a best-effort reconciliation ledger
 * while {@code delivery_report} (ADR-0005) is the durable audit of what was confirmed. The higher-level
 * {@link CorrelationStore#recordReport} composes the primitives below and classifies each recorded report
 * as a {@link ReconcileResult.Outcome} — {@code RECORDED} (known, pair incomplete), {@code COMPLETED}
 * (this call removed the fully-confirmed row), or {@code ORPHAN} (no prior registration) — which is how
 * the two residual cases are surfaced rather than swept:</p>
 * <ul>
 *   <li><b>Orphan-on-redelivery.</b> After COA+COD complete and the row is removed, an at-least-once
 *       REDELIVERED report re-creates a single-flag stub (the upsert mark), which never completes and so
 *       lingers in {@code pendingCount()}. Narrow trigger (a report is delivered to one consumer; only a
 *       genuine QM redelivery or a crash in the ack window re-fires it). {@code recordReport} sees no
 *       prior row and returns {@link ReconcileResult.Outcome#ORPHAN}, which the consumer surfaces as a
 *       {@code [stage=ORPHAN]} WARN + an orphan-rate counter (no Micrometer in this module). Not swept.</li>
 *   <li><b>COA-only that never completes.</b> A message that receives a COA but never a COD (never
 *       consumed, or expired) keeps a {@code coa_received=true, cod_received=false} row. Each COA there is
 *       a {@code RECORDED} outcome (a KNOWN message whose pair is incomplete — NOT an {@code ORPHAN}), so it
 *       is not counted as an orphan. This non-zero {@code pendingCount()} is CORRECT information — it
 *       reflects genuinely-undelivered messages — so a TTL sweep is deliberately NOT added (it would mask
 *       the signal).</li>
 * </ul>
 * <p>Both cases apply equally to {@link InMemoryCorrelationStore}; the mirror semantics are intentional —
 * and because {@code recordReport} is a {@code default} method on {@link CorrelationStore} that composes
 * the primitives (it is NOT overridden here), the {@code Outcome} classification is identical on both
 * adapters.</p>
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

    // INSERT idempotente. ON CONFLICT DO UPDATE (nao DO NOTHING) preenche os campos DESCRITIVOS se a
    // linha ja existe como um STUB criado por um relatorio que chegou ANTES do register (ver markFlag) —
    // SEM tocar nos flags coa/cod (preserva o que o relatorio ja marcou). Re-registro do mesmo id apenas
    // reafirma os campos descritivos.
    private static final String INSERT_SQL = """
            INSERT INTO pending_message (message_id, business_key, payload, sent_at, coa_received, cod_received)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (message_id) DO UPDATE SET
                business_key = EXCLUDED.business_key,
                payload      = EXCLUDED.payload,
                sent_at      = EXCLUDED.sent_at""";

    private static final String SELECT_SQL = """
            SELECT message_id, business_key, payload, sent_at, coa_received, cod_received
            FROM pending_message WHERE message_id = ?""";

    // UPSERT ... RETURNING: marca o flag E devolve a linha em UMA unica statement atomica (sem read-back
    // separado -> sem corrida). Critico: e um INSERT ... ON CONFLICT, NAO um UPDATE — um relatorio pode
    // chegar ANTES do register() do producer (a QM gera o COA na CHEGADA, processavel por um competing
    // report-consumer antes do register, que so tem o messageId APOS o send). Entao a marcacao CRIA a
    // linha (um stub: flag setado, sent_at agora; campos descritivos preenchidos depois pelo register via
    // ON CONFLICT DO UPDATE) se ela ainda nao existe — nenhum relatorio e perdido, e pendingCount drena.
    private static final String UPDATE_COA_SQL = """
            INSERT INTO pending_message (message_id, sent_at, coa_received) VALUES (?, ?, TRUE)
            ON CONFLICT (message_id) DO UPDATE SET coa_received = TRUE
            RETURNING message_id, business_key, payload, sent_at, coa_received, cod_received""";
    private static final String UPDATE_COD_SQL = """
            INSERT INTO pending_message (message_id, sent_at, cod_received) VALUES (?, ?, TRUE)
            ON CONFLICT (message_id) DO UPDATE SET cod_received = TRUE
            RETURNING message_id, business_key, payload, sent_at, coa_received, cod_received""";
    private static final String DELETE_SQL =
            "DELETE FROM pending_message WHERE message_id = ?";
    // Remove SO se ambos os flags ja estao TRUE — reconciliacao independente de ordem e race-free:
    // exatamente um competing consumer afeta a linha; chamadas concorrentes / COA-COD fora de ordem
    // convergem aqui (ver removeIfFullyConfirmed).
    private static final String DELETE_IF_CONFIRMED_SQL =
            "DELETE FROM pending_message WHERE message_id = ? AND coa_received AND cod_received";

    // Conta apenas as pendencias ainda nao totalmente confirmadas (coerente com InMemory.pendingCount).
    private static final String COUNT_PENDING_SQL =
            "SELECT COUNT(*) FROM pending_message WHERE NOT (coa_received AND cod_received)";

    private final DataSource dataSource;

    public JdbcCorrelationStore(DataSource dataSource) {
        // Unwrap Micronaut Data's contextual DataSource proxy. Once micronaut-data-jdbc is on the
        // classpath (added in issue #40), DataSource injections are wrapped by DelegatingDataSourceResolver,
        // and a raw getConnection() OUTSIDE a @Connectable/@Transactional scope throws NoConnectionException.
        // This store deliberately manages its own JDBC connections (plain PreparedStatements, no Micronaut
        // Data advice), so it needs the RAW target DataSource. unwrapDataSource is a safe no-op on an
        // already-unwrapped DataSource (so this is correct with or without micronaut-data-jdbc present).
        // See research-output/micronaut-data-cqrs-readwrite-split.md (F6).
        this.dataSource = DelegatingDataSource.unwrapDataSource(dataSource);
    }

    /**
     * Cria a tabela se ainda nao existir. Roda no startup de cada pod; {@code CREATE TABLE IF NOT
     * EXISTS} e seguro sob N replicas concorrentes (o primeiro cria, os demais sao no-op).
     */
    @PostConstruct
    void ensureSchema() {
        // O Postgres pode ainda nao estar pronto quando o pod sobe. Em vez de crashar o bean no primeiro
        // erro (CrashLoopBackOff ate o DB subir), tentamos algumas vezes com backoff — defesa em
        // profundidade junto do initContainer que espera postgres:5432 (ver deploy/k3s). CREATE TABLE IF
        // NOT EXISTS e idempotente e seguro sob N replicas concorrentes.
        final int maxAttempts = 10;
        SQLException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(DDL)) {
                ps.execute();
                LOG.info("[stage=STORE-INIT] Esquema do store de correlacao garantido (pending_message)");
                return;
            } catch (SQLException e) {
                last = e;
                LOG.warn("[stage=STORE-INIT] Postgres indisponivel ao garantir esquema (tentativa {}/{}): {}",
                        attempt, maxAttempts, e.getMessage());
                try {
                    Thread.sleep(3_000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new IllegalStateException(
                "Falha ao garantir o esquema do store de correlacao JDBC apos " + maxAttempts + " tentativas", last);
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
     * Marca um flag (COA/COD) via {@code INSERT ... ON CONFLICT DO UPDATE ... RETURNING}: cria a linha
     * como stub se o relatorio chegou antes do register, ou seta o flag na linha existente — sempre em
     * UMA statement atomica (sem read-back, logo sem corrida). Setar um flag ja TRUE de novo e no-op
     * (idempotente sob entrega at-least-once e competing consumers).
     */
    private Optional<PendingMessage> markFlag(String messageId, String upsertReturningSql) {
        if (messageId == null) {
            return Optional.empty();
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(upsertReturningSql)) {
            ps.setString(1, messageId);
            // sent_at do stub, caso o relatorio tenha chegado antes do register (os campos descritivos
            // sao preenchidos depois pelo register via ON CONFLICT DO UPDATE).
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            // UPSERT ... RETURNING sempre devolve a linha (criada ou atualizada) como ResultSet.
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
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
    public boolean removeIfFullyConfirmed(String messageId) {
        if (messageId == null) {
            return false;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(DELETE_IF_CONFIRMED_SQL)) {
            ps.setString(1, messageId);
            // DELETE condicional atomico: exatamente um competing consumer afeta a linha quando ambos os
            // flags estao TRUE; COA/COD fora de ordem e chamadas concorrentes convergem aqui sem corrida.
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao remover pendencia confirmada: " + messageId, e);
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
