package com.example.ibmmq.consumer;

import com.example.ibmmq.config.MqProperties;
import com.example.ibmmq.correlation.CorrelationStore;
import com.example.ibmmq.correlation.ReconcileResult;
import com.example.ibmmq.logging.MdcTraceScope;
import com.example.ibmmq.messaging.ReceivePort;
import com.example.ibmmq.messaging.ReportEnvelope;
import com.example.ibmmq.model.DeliveryEvent;
import com.example.ibmmq.model.PendingMessage;
import com.example.ibmmq.model.ReportType;
import com.example.ibmmq.persistence.DeliveryReportSchema;
import com.example.ibmmq.persistence.DeliveryReportWriteRepository;
import com.example.ibmmq.report.ReportDescriptor;
import com.example.ibmmq.report.ReportFeedbackRouter;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Le a fila de relatorios (JMSReplyTo) e processa os relatorios de entrega COA/COD/etc.
 *
 * <p><b>Como classificar:</b> o codigo de feedback do MQMD ja foi lido (da propriedade canonica
 * {@code JMS_IBM_Feedback}) pelo adapter de recebimento e chega no {@link ReportEnvelope#feedbackCode()}.
 * Esta propriedade e canonica e <em>sempre populada</em> para relatorios — diferente de
 * {@code JMS_IBM_MQMD_Feedback}, que so e preenchida quando {@code WMQ_MQMD_READ_ENABLED=true} no destino.</p>
 *
 * <p><b>Como correlacionar:</b> com o default {@code MQRO_COPY_MSG_ID_TO_CORREL_ID}, o relatorio
 * chega com {@code JMSCorrelationID == MessageId} da mensagem original. Buscamos a pendencia por esse
 * id no {@link CorrelationStore}.</p>
 *
 * <p><b>Seam (ADR-0008):</b> este entry point nao abre mais um {@code JMSContext} proprio — delega ao
 * {@link ReceivePort#receiveReport}, que faz toda a extracao MQMD ({@code JMS_IBM_Feedback},
 * {@code getJMSCorrelationID}, os seis valores MQMD do #19) e entrega um {@link ReportEnvelope}
 * decodificado. Nenhum {@code javax.jms.Message} chega aqui; a classificacao e a reconciliacao operam
 * puramente sobre o envelope.</p>
 */
@Singleton
public class ReportMessageConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(ReportMessageConsumer.class);

    private final ReceivePort receivePort;
    private final MqProperties props;
    private final CorrelationStore correlationStore;
    private final ReportFeedbackRouter feedbackRouter;
    // Append-only audit persistence of each COA/COD report (issue #40), on the WRITER datasource.
    // Nullable on purpose: the repository bean is gated on datasources.default.url, so it is ABSENT in
    // unit/context tests that run a bare ApplicationContext with no datasources — there persistence is
    // simply skipped and the consumer keeps working. Present in the k3s harness and the persistence IT.
    private final DeliveryReportWriteRepository auditRepository;

    // ADR-0010 schema-on-first-write: the audit table is created lazily on the FIRST audit write, not by an
    // eager startup hook. auditSchema owns the DDL + retry/backoff; @Nullable and co-gated with the
    // repository on datasources.default.url, so both are ABSENT together in datasource-less contexts. The
    // schemaReady latch makes the ensure exactly-once-on-SUCCESS: it flips true ONLY when ensureSchema()
    // returns, so a transient first-write DB failure is retried on the next write (see persistAudit). The
    // double-checked AtomicBoolean keeps the ensure correct under the async MessageListener scale-up variant
    // (ADR-0006) even though today's harness is single-thread-per-pod.
    private final @Nullable DeliveryReportSchema auditSchema;
    private final AtomicBoolean schemaReady = new AtomicBoolean(false);

    // Orphan-rate metric (issue #26): an in-process counter of COA/COD reports recorded for a
    // correlation id with no prior registration (an at-least-once redelivery after the pair already
    // completed, or a report this process never registered). No Micrometer dependency in this module,
    // so we follow the harness AtomicLong pattern (PublisherHarnessRunner) + a [stage=ORPHAN] WARN line;
    // a production build would back this with a Micrometer counter. Read via getOrphanReportCount().
    private final AtomicLong orphanReportCount = new AtomicLong();

    public ReportMessageConsumer(ReceivePort receivePort,
                                 MqProperties props,
                                 CorrelationStore correlationStore,
                                 ReportFeedbackRouter feedbackRouter,
                                 @Nullable DeliveryReportWriteRepository auditRepository,
                                 @Nullable DeliveryReportSchema auditSchema) {
        this.receivePort = receivePort;
        this.props = props;
        this.correlationStore = correlationStore;
        this.feedbackRouter = feedbackRouter;
        this.auditRepository = auditRepository;
        this.auditSchema = auditSchema;
    }

    /**
     * Recebe um relatorio da fila de relatorios (com timeout), classifica e registra o evento.
     *
     * @param timeoutMillis tempo maximo de espera (ms).
     * @return o {@link DeliveryEvent} derivado, ou {@code null} se o timeout expirar sem relatorio.
     */
    public DeliveryEvent receiveOneReport(long timeoutMillis) {
        // The ReceivePort adapter owns the JMSContext lifecycle, the queue:///...?mdReadEnabled=true
        // URI form (issue #19, so the JMS_IBM_MQMD_* values are populated), and the extraction of the
        // feedback code, correlation id, body, and the six MQMD values into a ReportEnvelope.
        ReportEnvelope env = receivePort.receiveReport(props.getReportQueue(), timeoutMillis);
        if (env == null) {
            LOG.debug("Nenhum relatorio dentro do timeout ({} ms)", timeoutMillis);
            return null;
        }
        return handleReport(env);
    }

    /**
     * Processa um unico relatorio decodificado. Exposto separadamente para testabilidade (pode ser
     * chamado com um {@link ReportEnvelope} sintetico, sem broker).
     */
    public DeliveryEvent handleReport(ReportEnvelope env) {
        try {
            // The feedback code was read from the canonical JMS_IBM_Feedback by the adapter; here we
            // read it (and the correlation id) off the decoded envelope.
            int feedback = env.feedbackCode();
            String correlationId = env.correlationId();

            // Classify from the feedback code (NOT from the descriptor char): an exception report (e.g.
            // MQRC_*) classifies to EXCEPTION here even though its descriptor char is the sentinel.
            ReportType type = feedbackRouter.classify(feedback);

            // Issue #19: the six MQMD values were recovered from the report's OWN descriptor by the
            // adapter (verdict (R)-all) and travel on the envelope. When mdReadEnabled is off (or a
            // synthetic envelope is used in unit tests) the byte[]/timestamp fields are null, so the
            // descriptor degrades gracefully and the already-acked report path is never aborted.
            ReportDescriptor descriptor = env.descriptor();

            // Correlaciona de volta a mensagem original (CorrelationId == MessageId original).
            Optional<PendingMessage> pending = correlationStore.findByMessageId(correlationId);
            String originalMessageId = pending.map(PendingMessage::messageId).orElse(correlationId);
            // Issue #21: capture the original send instant for the produce->report latency baseline. NULL when
            // the pending row is unknown (the rare COA-before-register case) — a low-tail sample excluded from
            // the percentiles in `make load-verify` (ADR-0007), so p95/p99 stay robust.
            Instant sentAt = pending.map(PendingMessage::sentAt).orElse(null);

            // MDC trace context: messageId = original MessageId derived by correlation; correlationId =
            // the report's JMSCorrelationID. NOTE the asymmetry — these are two DIFFERENT source values
            // (unlike the producer, which binds the same id to both). We bind BEFORE the steps so every
            // line (classify, correlate, COA/COD, reconcile) carries the same ids, closing end-to-end
            // traceability: the same id from PRODUCE shows up here on the report. The try-with-resources
            // clears both keys before the thread returns to the pool (see the producer's note); under
            // ~10k rpm a reused thread must not leak this report's ids to the next.
            try (var scope = MdcTraceScope.bind(originalMessageId, correlationId)) {
                LOG.info("[stage=CLASSIFY] Relatorio classificado: tipo={}, feedback={}, correlId={}",
                        type, feedback, correlationId);
                LOG.info("[stage=CORRELATE] Correlacionado a mensagem original: originalMsgId={}, conhecido={}",
                        originalMessageId, pending.isPresent());

                // Observation instant: shared by both the durable audit row and the DeliveryEvent below,
                // so the persisted timestamp matches the event the caller sees.
                Instant observedAt = Instant.now();

                // Atualiza o estado da pendencia conforme o tipo de relatorio.
                switch (type) {
                    case COA -> {
                        // COA = Confirmation On Arrival: a mensagem CHEGOU na fila de destino.
                        LOG.info("[stage=COA] Confirmacao de chegada (arrival) registrada: correlId={}, originalMsgId={}",
                                correlationId, originalMessageId);
                        // Append-only audit row (writer datasource). Best-effort: a persist failure must NOT
                        // break the reconciliation path that follows (the report is already acked).
                        persistAudit(type, feedback, correlationId, originalMessageId, observedAt, sentAt, descriptor);
                        // One higher-level call: mark COA + order-independent reconcile in a single step
                        // (CorrelationStore.recordReport composes the primitives). Under competing consumers
                        // the COD may have been processed FIRST on another pod, so the COA can complete the
                        // pair — recordReport handles that ordering and surfaces the outcome.
                        recordAndSurface(type, correlationId, originalMessageId);
                    }
                    case COD -> {
                        // COD = Confirmation On Delivery: a mensagem foi CONSUMIDA destrutivamente.
                        LOG.info("[stage=COD] Confirmacao de entrega (delivery) registrada: correlId={}, originalMsgId={}",
                                correlationId, originalMessageId);
                        persistAudit(type, feedback, correlationId, originalMessageId, observedAt, sentAt, descriptor);
                        recordAndSurface(type, correlationId, originalMessageId);
                    }
                    case EXPIRATION, NAN, EXCEPTION ->
                            LOG.warn("[stage=PROBLEM] Relatorio de problema: tipo={}, feedback={}, correlId={}",
                                    type, feedback, correlationId);
                    default -> { /* PAN/UNKNOWN: apenas registra no resumo abaixo. */ }
                }

                // Full 6-field event (issue #19): only this call site builds the extended DeliveryEvent;
                // pre-#19 call sites keep using the 5-arg secondary constructor unchanged.
                DeliveryEvent event = new DeliveryEvent(
                        type, feedback, correlationId, originalMessageId, observedAt,
                        descriptor.applIdentityData(),
                        descriptor.accountingToken(),
                        descriptor.correlationIdBytes(),
                        descriptor.messageIdBytes(),
                        descriptor.putTimestampUtc(),
                        descriptor.reportTypeChar());

                LOG.info("[stage=REPORT-DONE] Relatorio processado: tipo={}, feedback={}, correlId={}, originalMsgId={}, "
                                + "conhecido={}, putTsUtc={}, reportTypeChar={}, msgIdHex={}",
                        type, feedback, correlationId, originalMessageId, pending.isPresent(),
                        descriptor.putTimestampUtc(), descriptor.reportTypeChar(), descriptor.messageIdBytesHex());

                return event;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao processar relatorio de entrega", e);
        }
    }

    /**
     * Records one COA/COD report through {@link CorrelationStore#recordReport} (mark + order-independent
     * reconcile in a single composed step) and surfaces the {@link ReconcileResult.Outcome} for logging
     * and the orphan-rate metric:
     *
     * <ul>
     *   <li>{@code COMPLETED} &rarr; the report completed the COA+COD pair and THIS call removed the row
     *       — emit the {@code [stage=RECONCILE]} line (with {@code pendingCount()}) exactly as before.
     *       Safe under competing report-consumers: exactly one call removes the row (the rest are
     *       no-ops), so the line is emitted once per fully-reconciled message;</li>
     *   <li>{@code ORPHAN} &rarr; the report had no prior registration (orphan-on-redelivery, or a report
     *       this process never registered) — emit a {@code [stage=ORPHAN]} WARN and bump the orphan-rate
     *       counter. Not swept (see the known-limitations ledger);</li>
     *   <li>{@code RECORDED} &rarr; a known message whose pair is not yet complete — no extra line
     *       (the {@code [stage=COA]}/{@code [stage=COD]} line already narrated the mark).</li>
     * </ul>
     *
     * <p>This keeps {@code pendingCount()} draining to zero cluster-wide for fully-confirmed messages
     * with NO operator sweep, while orphans are surfaced rather than silently lingering.</p>
     */
    private void recordAndSurface(ReportType type, String correlationId, String originalMessageId) {
        ReconcileResult result = correlationStore.recordReport(correlationId, type);
        switch (result.outcome()) {
            case COMPLETED -> LOG.info(
                    "[stage=RECONCILE] Entrega completa (COA+COD): pendencia reconciliada e removida, "
                            + "originalMsgId={}, pendentesRestantes={}",
                    originalMessageId, correlationStore.pendingCount());
            case ORPHAN -> {
                long total = orphanReportCount.incrementAndGet();
                LOG.warn("[stage=ORPHAN] Orphan {} report (no prior registration): correlId={}, "
                                + "orphanReportCount={}, pendingCount={}",
                        type, correlationId, total, correlationStore.pendingCount());
            }
            case RECORDED -> { /* Known message, pair not yet complete — already narrated by COA/COD line. */ }
        }
    }

    /**
     * Current count of orphan COA/COD reports recorded by this consumer instance — the orphan-rate
     * metric backing the {@code [stage=ORPHAN]} WARN. In-process counter (no Micrometer in this module),
     * following the harness {@code AtomicLong} pattern; exposed for tests and any harness/JMX surface.
     *
     * @return the number of {@link ReconcileResult.Outcome#ORPHAN} outcomes observed so far.
     */
    public long getOrphanReportCount() {
        return orphanReportCount.get();
    }

    /**
     * Appends one durable COA/COD audit row to {@code delivery_report} on the WRITER datasource —
     * <b>best-effort</b>.
     *
     * <p><b>Schema-on-first-write (ADR-0010).</b> Before the first insert this path ensures the
     * {@code delivery_report} table exists via {@link #ensureSchemaReady()} (a one-time idempotent guard
     * around {@link DeliveryReportSchema#ensureSchema()}). The ensure runs only when a real report is
     * persisted (live-DB report-consumer pod), never in datasource-less contexts; a transient ensure
     * failure is best-effort (skip this write, retry next).</p>
     *
     * <p>Three properties matter here, all by design:</p>
     * <ul>
     *   <li><b>Optional.</b> When no datasource is configured (unit/context tests) the repository bean
     *       is absent ({@code auditRepository == null}) and we skip silently — the audit is a harness/
     *       production feature, not a unit-test concern.</li>
     *   <li><b>Idempotent.</b> The repository's {@code INSERT ... ON CONFLICT (correlation_id, feedback)
     *       DO NOTHING} returns {@code 0} on a duplicate (report redelivered at-least-once, or two
     *       competing consumers processing the same report) instead of throwing — no duplicate row, no
     *       exception on the acked path (AC4).</li>
     *   <li><b>Non-fatal.</b> The report was already acked under {@code AUTO_ACKNOWLEDGE} before
     *       processing, so a persist failure (transient DB outage) must NOT propagate and abort the
     *       reconciliation that follows. We catch, log at WARN, and continue — same best-effort posture
     *       as the rest of the report path (a transacted/CLIENT_ACKNOWLEDGE store path is a documented
     *       follow-up in ADR-0005).</li>
     * </ul>
     */
    private void persistAudit(ReportType type, int feedback, String correlationId,
                              String originalMessageId, Instant observedAt, Instant sentAt,
                              ReportDescriptor descriptor) {
        if (auditRepository == null) {
            return; // No datasource configured (e.g. unit/context test) — audit persistence is inert.
        }
        // ADR-0010 schema-on-first-write: ensure the audit table exists before the FIRST write, behind a
        // one-time idempotent guard. Placed AFTER the null-repo early return so datasource-less contexts
        // never connect, and BEFORE insertIfAbsent so the table always exists first. Best-effort: if the
        // ensure fails (transient DB outage), the latch stays false (retried next write) and we skip THIS
        // insert without aborting the already-acked reconciliation path.
        if (!ensureSchemaReady()) {
            return;
        }
        try {
            // Issue #19: additively persist the six recovered MQMD values (all nullable). The byte[]
            // fields go in as hex strings; the report-type char goes in as a one-char String (null when
            // it is the sentinel, so non-COA/COD reports leave the column NULL rather than storing '?').
            char domainChar = descriptor.reportTypeChar();
            String reportTypeChar = domainChar == ReportType.DOMAIN_CHAR_OTHER
                    ? null : String.valueOf(domainChar);
            LocalDateTime putTimestampUtc = descriptor.putTimestampUtc();
            int inserted = auditRepository.insertIfAbsent(
                    correlationId, originalMessageId, type.name(), feedback, observedAt,
                    descriptor.applIdentityData(),
                    descriptor.accountingTokenHex(),
                    descriptor.correlationIdBytesHex(),
                    descriptor.messageIdBytesHex(),
                    putTimestampUtc,
                    reportTypeChar,
                    sentAt);
            if (inserted == 0) {
                LOG.debug("[stage=AUDIT] Report already persisted (idempotent duplicate): type={}, correlId={}",
                        type, correlationId);
            } else {
                LOG.info("[stage=AUDIT] Report persisted (delivery_report): type={}, feedback={}, correlId={}, originalMsgId={}",
                        type, feedback, correlationId, originalMessageId);
            }
        } catch (RuntimeException e) {
            // Best-effort: the report is already acked; never break reconciliation on a persist failure.
            LOG.warn("[stage=AUDIT] Failed to persist report (best-effort, ignored): type={}, correlId={}, cause={}",
                    type, correlationId, e.getMessage());
        }
    }

    /**
     * Ensures the {@code delivery_report} audit schema exists, ONCE, on the first audit write (ADR-0010).
     *
     * <p><b>Exactly-once-on-SUCCESS.</b> The {@link #schemaReady} latch flips {@code true} ONLY when
     * {@link DeliveryReportSchema#ensureSchema()} returns successfully; every subsequent write then skips
     * straight through (fast path). A transient first-write DB failure (Postgres briefly unavailable) is
     * <b>best-effort</b>: it is logged at WARN, the latch stays {@code false}, this method returns
     * {@code false} so the caller skips THIS insert WITHOUT aborting the already-acked reconciliation path,
     * and the ensure is retried on the NEXT write.</p>
     *
     * <p><b>Concurrency.</b> A double-checked {@code AtomicBoolean} keeps the ensure correct under the async
     * {@code MessageListener} scale-up variant (ADR-0006); the {@code synchronized} block guarantees at most
     * one thread runs {@code ensureSchema()} while the rest wait, and the fast path (already-ready) takes no
     * lock. Today's harness is single-thread-per-pod, so contention is nil — this is defence-in-depth.</p>
     *
     * @return {@code true} when the schema is ready (already, or just ensured) and the caller may insert;
     *         {@code false} when the ensure failed and the caller must skip this write (retried next time).
     */
    private boolean ensureSchemaReady() {
        if (schemaReady.get()) {
            return true; // Fast path: schema already ensured by an earlier write — no lock, no DB touch.
        }
        if (auditSchema == null) {
            // Defensive: auditSchema is co-gated with auditRepository on datasources.default.url, so a
            // present repository normally implies a present schema. If it is ever absent, skip silently.
            return false;
        }
        synchronized (this) {
            if (schemaReady.get()) {
                return true; // Another thread ensured it while we waited on the lock.
            }
            try {
                auditSchema.ensureSchema();
                schemaReady.set(true); // Flip ONLY on a successful ensure (exactly-once-on-SUCCESS).
                return true;
            } catch (RuntimeException e) {
                // Best-effort: leave the latch false so the next write retries; never break reconciliation.
                LOG.warn("[stage=AUDIT-INIT] Failed to ensure delivery_report schema on first write "
                        + "(best-effort, retried next write): cause={}", e.getMessage());
                return false;
            }
        }
    }
}
