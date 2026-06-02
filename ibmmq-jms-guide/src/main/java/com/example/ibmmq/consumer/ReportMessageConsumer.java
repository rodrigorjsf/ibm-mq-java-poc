package com.example.ibmmq.consumer;

import com.example.ibmmq.config.MqProperties;
import com.example.ibmmq.correlation.CorrelationStore;
import com.example.ibmmq.correlation.ReconcileResult;
import com.example.ibmmq.model.DeliveryEvent;
import com.example.ibmmq.model.PendingMessage;
import com.example.ibmmq.model.ReportType;
import com.example.ibmmq.persistence.DeliveryReportWriteRepository;
import com.example.ibmmq.report.ReportDescriptor;
import com.example.ibmmq.report.ReportFeedbackRouter;
import com.ibm.msg.client.wmq.WMQConstants;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import javax.jms.JMSConsumer;
import javax.jms.ConnectionFactory;
import javax.jms.JMSContext;
import javax.jms.Message;

/**
 * Le a fila de relatorios (JMSReplyTo) e processa os relatorios de entrega COA/COD/etc.
 *
 * <p><b>Como classificar:</b> lemos o codigo de feedback do MQMD via a propriedade JMS
 * {@code JMS_IBM_Feedback} ({@code WMQConstants.JMS_IBM_FEEDBACK}). Esta e a propriedade canonica e
 * <em>sempre populada</em> para relatorios — diferente de {@code JMS_IBM_MQMD_Feedback}, que so e
 * preenchida quando {@code WMQ_MQMD_READ_ENABLED=true} no destino. (Ambas existem em
 * {@code JmsConstants} 9.4.5.0; usamos a primeira propositalmente.)</p>
 *
 * <p><b>Como correlacionar:</b> com o default {@code MQRO_COPY_MSG_ID_TO_CORREL_ID}, o relatorio
 * chega com {@code JMSCorrelationID == MessageId} da mensagem original. Buscamos a pendencia por esse
 * id no {@link CorrelationStore}.</p>
 */
@Singleton
public class ReportMessageConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(ReportMessageConsumer.class);

    private final ConnectionFactory connectionFactory;
    private final MqProperties props;
    private final CorrelationStore correlationStore;
    private final ReportFeedbackRouter feedbackRouter;
    // Append-only audit persistence of each COA/COD report (issue #40), on the WRITER datasource.
    // Nullable on purpose: the repository bean is gated on datasources.default.url, so it is ABSENT in
    // unit/context tests that run a bare ApplicationContext with no datasources — there persistence is
    // simply skipped and the consumer keeps working. Present in the k3s harness and the persistence IT.
    private final DeliveryReportWriteRepository auditRepository;

    // Orphan-rate metric (issue #26): an in-process counter of COA/COD reports recorded for a
    // correlation id with no prior registration (an at-least-once redelivery after the pair already
    // completed, or a report this process never registered). No Micrometer dependency in this module,
    // so we follow the harness AtomicLong pattern (PublisherHarnessRunner) + a [stage=ORPHAN] WARN line;
    // a production build would back this with a Micrometer counter. Read via getOrphanReportCount().
    private final AtomicLong orphanReportCount = new AtomicLong();

    public ReportMessageConsumer(ConnectionFactory connectionFactory,
                                 MqProperties props,
                                 CorrelationStore correlationStore,
                                 ReportFeedbackRouter feedbackRouter,
                                 @Nullable DeliveryReportWriteRepository auditRepository) {
        this.connectionFactory = connectionFactory;
        this.props = props;
        this.correlationStore = correlationStore;
        this.feedbackRouter = feedbackRouter;
        this.auditRepository = auditRepository;
    }

    /**
     * Recebe um relatorio da fila de relatorios (com timeout), classifica e registra o evento.
     *
     * @param timeoutMillis tempo maximo de espera (ms).
     * @return o {@link DeliveryEvent} derivado, ou {@code null} se o timeout expirar sem relatorio.
     */
    public DeliveryEvent receiveOneReport(long timeoutMillis) {
        try (JMSContext context = connectionFactory.createContext(JMSContext.AUTO_ACKNOWLEDGE)) {

            // Enable MQMD read on the consume destination via the URI form (issue #19): the
            // JMS_IBM_MQMD_* properties (ApplIdentityData, AccountingToken, MsgId, PutDate/PutTime) are
            // populated ONLY when mdReadEnabled=true on the report destination — there is no setter on the
            // ConnectionFactory. The URI property is preferred over an MQDestination cast because it
            // survives the JmsPoolConnectionFactory wrapper (no provider cast). The canonical
            // JMS_IBM_Feedback used for classification needs no read-enable.
            JMSConsumer consumer = context.createConsumer(
                    context.createQueue("queue:///" + props.getReportQueue() + "?mdReadEnabled=true"));

            Message report = consumer.receive(timeoutMillis);
            if (report == null) {
                LOG.debug("Nenhum relatorio dentro do timeout ({} ms)", timeoutMillis);
                return null;
            }

            return handleReport(report);
        }
    }

    /**
     * Processa um unico relatorio JMS. Exposto separadamente para testabilidade (pode ser chamado
     * com um {@code Message} mockado).
     */
    public DeliveryEvent handleReport(Message report) {
        try {
            // Le o codigo de feedback do MQMD via a propriedade canonica JMS_IBM_Feedback.
            int feedback = report.getIntProperty(WMQConstants.JMS_IBM_FEEDBACK);
            String correlationId = report.getJMSCorrelationID();

            ReportType type = feedbackRouter.classify(feedback);

            // Issue #19: recover the six MQMD values from the report's OWN descriptor (verdict (R)-all).
            // Fully null-safe and non-throwing — when mdReadEnabled is off (e.g. unit tests with a bare
            // mock) every MQMD getter returns null, so the descriptor degrades gracefully and the
            // already-acked report path is never aborted.
            ReportDescriptor descriptor = ReportDescriptor.from(report, type);

            // Correlaciona de volta a mensagem original (CorrelationId == MessageId original).
            Optional<PendingMessage> pending = correlationStore.findByMessageId(correlationId);
            String originalMessageId = pending.map(PendingMessage::messageId).orElse(correlationId);
            // Issue #21: capture the original send instant for the produce->report latency baseline. NULL when
            // the pending row is unknown (the rare COA-before-register case) — a low-tail sample excluded from
            // the percentiles in `make load-verify` (ADR-0007), so p95/p99 stay robust.
            Instant sentAt = pending.map(PendingMessage::sentAt).orElse(null);

            // MDC: correlationId = JMSCorrelationID do relatorio; messageId = MessageId original
            // derivado pela correlacao. Vinculamos ANTES das etapas para que toda linha (classify,
            // correlate, COA/COD, reconcile) carregue os mesmos ids — fechando a rastreabilidade
            // ponta-a-ponta: o mesmo id do PRODUCE aparece aqui no relatorio.
            MDC.put("messageId", originalMessageId);
            MDC.put("correlationId", correlationId);
            try {
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
            } finally {
                // Limpa o MDC antes de devolver a thread ao pool (ver nota do produtor): sob ~10k rpm
                // uma thread reutilizada nao pode vazar os ids deste relatorio para o proximo.
                MDC.remove("messageId");
                MDC.remove("correlationId");
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
}
