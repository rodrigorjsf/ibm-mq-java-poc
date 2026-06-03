package com.example.ibmmq.demo;

import com.example.ibmmq.consumer.BusinessMessageConsumer;
import com.example.ibmmq.consumer.ReportMessageConsumer;
import com.example.ibmmq.model.DeliveryEvent;
import com.example.ibmmq.producer.BusinessMessageProducer;
import com.ibm.mq.constants.MQConstants;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Runner gated (acionado por um flag EXPLICITO) que executa o fluxo COA/COD ponta-a-ponta contra o
 * broker local, reutilizando os beans de producao (producer, consumer, report consumer) e o logger
 * narrado (#11), imprimindo um banner por etapa e um resumo PASS/FAIL.
 *
 * <p><b>Gating (AC#3):</b> a anotacao {@code @Requires(property = "demo.coa-cod.enabled", value = "true")}
 * garante que este bean <em>nao e instanciado</em> quando o flag esta ausente — logo o startup normal
 * da aplicacao NAO e afetado e o caminho sem-flag e testavel por unidade (o bean simplesmente nao
 * existe no {@code ApplicationContext}). O flag e EXPLICITO: nunca ligado por padrao.</p>
 *
 * <p><b>Por que {@link ApplicationEventListener}&lt;{@link StartupEvent}&gt;?</b> {@code StartupEvent} e
 * publicado pelo Micronaut assim que o contexto sobe; o listener roda <em>sincronamente</em> dentro do
 * bootstrap. Como o bean so existe quando o flag esta ligado, "subir o contexto com o flag" e
 * exatamente "rodar a demo" — um unico comando. Nem {@code StartupEvent} nem o listener trazem novas
 * dependencias (vem de {@code micronaut-runtime}, ja presente).</p>
 *
 * <p><b>Reuso, nao reimplementacao:</b> este runner NAO fala JMS diretamente. Ele orquestra os beans
 * existentes — {@link BusinessMessageProducer#send}, {@link BusinessMessageConsumer#receiveOne},
 * {@link ReportMessageConsumer#receiveOneReport} — que ja emitem a narracao por etapa
 * ([stage=PRODUCE/CONSUME/COMMIT/CLASSIFY/CORRELATE/COA/COD/RECONCILE]). O runner acrescenta apenas um
 * banner de orquestracao e o resumo final de validacao; NAO re-loga aquelas etapas.</p>
 *
 * <p><b>Autoridade de relatorio (gotcha 2035):</b> para o QMgr GERAR+ENTREGAR um COA/COD ele faz um
 * PUT-com-contexto na ReplyToQ, exigindo autoridade de contexto ({@code +setall}). O usuario {@code app}
 * de baixo privilegio do dev image NAO possui isso -> o PUT do relatorio falha com
 * {@code MQRC_NOT_AUTHORIZED (2035)}, o relatorio vai para a DLQ e a fila de relatorios fica vazia
 * (a demo "trava" sem nunca ver os relatorios). Por isso a demo deve conectar como {@code admin} via
 * {@code DEV.ADMIN.SVRCONN} — ver {@code application-demo.yml} e o comando documentado no README.</p>
 *
 * <p><b>Sem {@code System.exit}:</b> o listener roda dentro do JVM da aplicacao (e, nos testes, dentro
 * do JVM do surefire). Encerrar o processo mataria o test runner e seria desnecessario — o resumo
 * PASS/FAIL e logado e o metodo retorna normalmente.</p>
 */
@Singleton
@Requires(property = "demo.coa-cod.enabled", value = "true")
public class CoaCodDemoRunner implements ApplicationEventListener<StartupEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(CoaCodDemoRunner.class);

    /** Chave de negocio fixa para a demo (apenas rastreabilidade legivel no log). */
    private static final String DEMO_BUSINESS_KEY = "demo-coa-cod";
    /** Payload JSON de exemplo enviado pela demo. */
    private static final String DEMO_PAYLOAD = "{\"demo\":\"coa-cod\",\"pedido\":42}";

    /** Timeout (ms) default para o GET destrutivo da mensagem de negocio. */
    private static final long DEFAULT_CONSUME_TIMEOUT_MILLIS = Duration.ofSeconds(15).toMillis();
    /** Timeout (ms) default por tentativa de leitura da fila de relatorios. */
    private static final long DEFAULT_REPORT_POLL_TIMEOUT_MILLIS = Duration.ofSeconds(5).toMillis();
    /** Deadline total (ms) default para colher AMBOS os relatorios (COA + COD). */
    private static final long DEFAULT_REPORT_DEADLINE_MILLIS = Duration.ofSeconds(30).toMillis();

    private final BusinessMessageProducer producer;
    private final BusinessMessageConsumer consumer;
    private final ReportMessageConsumer reportConsumer;

    private final long consumeTimeoutMillis;
    private final long reportPollTimeoutMillis;
    private final long reportDeadlineMillis;

    /** Construtor de producao (injetado pelo Micronaut): usa os timeouts/deadline default. */
    @Inject
    public CoaCodDemoRunner(BusinessMessageProducer producer,
                            BusinessMessageConsumer consumer,
                            ReportMessageConsumer reportConsumer) {
        this(producer, consumer, reportConsumer,
                DEFAULT_CONSUME_TIMEOUT_MILLIS,
                DEFAULT_REPORT_POLL_TIMEOUT_MILLIS,
                DEFAULT_REPORT_DEADLINE_MILLIS);
    }

    /**
     * Construtor parametrizado (package-private) usado pelos testes para encurtar o deadline e manter
     * o surefire rapido quando um relatorio nunca chega (cenario FAIL). NAO e um ponto de injecao do
     * Micronaut — o {@code @Singleton} resolve pelo construtor publico de tres argumentos.
     */
    CoaCodDemoRunner(BusinessMessageProducer producer,
                     BusinessMessageConsumer consumer,
                     ReportMessageConsumer reportConsumer,
                     long consumeTimeoutMillis,
                     long reportPollTimeoutMillis,
                     long reportDeadlineMillis) {
        this.producer = producer;
        this.consumer = consumer;
        this.reportConsumer = reportConsumer;
        this.consumeTimeoutMillis = consumeTimeoutMillis;
        this.reportPollTimeoutMillis = reportPollTimeoutMillis;
        this.reportDeadlineMillis = reportDeadlineMillis;
    }

    /**
     * Disparado pelo {@code StartupEvent}: roda a demo uma unica vez. Captura excecoes para que uma
     * falha da demo (ex. broker ausente) seja reportada como FAIL no resumo — sem derrubar o contexto
     * de forma abrupta nem matar o JVM.
     */
    @Override
    public void onApplicationEvent(StartupEvent event) {
        try {
            DemoResult result = runDemo();
            logSummary(result);
        } catch (RuntimeException e) {
            LOG.error("[demo=COA/COD] [resultado=FAIL] A demo falhou com excecao — "
                    + "verifique se o broker esta no ar (docker compose up -d) e se a config admin "
                    + "(DEV.ADMIN.SVRCONN/admin) esta ativa (-Dmicronaut.environments=demo).", e);
        }
    }

    /**
     * Orquestra o fluxo COA/COD ponta-a-ponta e devolve um {@link DemoResult} com os relatorios
     * colhidos e o veredito de validacao. Exposto (package-private) para teste por unidade com os
     * beans JMS mockados.
     *
     * <p>Ordem: (a) {@code messageId = producer.send(...)}; (b) {@code consumer.receiveOne(timeout)}
     * (o commit dispara o COD); (c) loop {@code reportConsumer.receiveOneReport(timeout)} sob um
     * deadline generoso, colhendo {@link DeliveryEvent}s ate ver AMBOS COA(259) e COD(260);
     * (d) valida: ambos os feedbacks presentes e cada {@code correlationId == messageId}.</p>
     */
    DemoResult runDemo() {
        LOG.info("[demo=COA/COD] [stage=BANNER] Iniciando a demo COA/COD ponta-a-ponta "
                + "(produce -> consume -> COA/COD) contra o broker local.");

        // (a) PRODUZIR — o bean ja narra [stage=PRODUCE]; aqui so guardamos o messageId.
        String messageId = producer.send(DEMO_BUSINESS_KEY, DEMO_PAYLOAD);
        LOG.info("[demo=COA/COD] [stage=BANNER] Mensagem enviada (messageId={}). Consumindo para "
                + "disparar o COD...", messageId);

        // (b) CONSUMIR destrutivamente — o commit (narrado [stage=COMMIT]) libera o COD.
        String body = consumer.receiveOne(consumeTimeoutMillis);
        if (body == null) {
            LOG.warn("[demo=COA/COD] [stage=BANNER] Nenhuma mensagem de negocio consumida dentro do "
                    + "timeout ({} ms) — o COD nao sera disparado.", consumeTimeoutMillis);
        }

        // (c) COLHER os relatorios sob um deadline generoso, em qualquer ordem (COA/COD), tolerando
        // retornos null (timeout) ate ver AMBOS os feedbacks.
        CollectedReports collected = collectReports();

        // (d) VALIDAR — reuse the flags already computed by collectReports (m3).
        return validate(messageId, collected);
    }

    /**
     * Loop deadline-bounded que le a fila de relatorios reutilizando o {@link ReportMessageConsumer}
     * (que ja narra [stage=CLASSIFY/CORRELATE/COA/COD/RECONCILE]). Para assim que AMBOS COA(259) e
     * COD(260) forem vistos, ou quando o deadline expirar.
     *
     * <p>Uses {@link System#nanoTime()} for a monotonic deadline (m4); the subtraction-based
     * comparison ({@code nanoTime() - deadlineNanos < 0}) is overflow-safe per the JDK contract.</p>
     */
    private CollectedReports collectReports() {
        List<DeliveryEvent> reports = new ArrayList<>();
        boolean coaSeen = false;
        boolean codSeen = false;

        // m4: nanoTime() is monotonic — immune to wall-clock adjustments (NTP, DST, leap seconds).
        long deadlineNanos = System.nanoTime() + reportDeadlineMillis * 1_000_000L;
        while (System.nanoTime() - deadlineNanos < 0 && !(coaSeen && codSeen)) {
            DeliveryEvent event = reportConsumer.receiveOneReport(reportPollTimeoutMillis);
            if (event == null) {
                // Timeout sem relatorio: continua ate o deadline.
                continue;
            }
            reports.add(event);
            if (event.feedbackCode() == MQConstants.MQFB_COA) {
                coaSeen = true;
            } else if (event.feedbackCode() == MQConstants.MQFB_COD) {
                codSeen = true;
            }
        }
        // Return the list together with the flags already computed during collection (m3:
        // avoids re-scanning the list in validate() via anyMatch).
        return new CollectedReports(List.copyOf(reports), coaSeen, codSeen);
    }

    /**
     * Carries the result of one {@link #collectReports()} pass: the immutable report list
     * plus the COA/COD seen-flags already computed during the collection loop (m3: no need
     * for validate() to re-derive them via anyMatch).
     */
    private record CollectedReports(
            List<DeliveryEvent> reports,
            boolean coaSeen,
            boolean codSeen
    ) {}

    /**
     * Valida (AC#2) que chegaram AMBOS os relatorios — COA (feedback {@code MQFB_COA}=259) e COD
     * ({@code MQFB_COD}=260) — e que cada um correlaciona ao {@code messageId} original
     * ({@code correlationId == messageId}, default {@code MQRO_COPY_MSG_ID_TO_CORREL_ID}). Usa os
     * constants {@code MQConstants.MQFB_*}, nunca literais; o feedback foi lido pelo consumer via
     * {@code WMQConstants.JMS_IBM_FEEDBACK}.
     */
    private DemoResult validate(String messageId, CollectedReports collected) {
        // Reuse the flags computed by collectReports — no redundant anyMatch re-scan (m3).
        boolean coaSeen = collected.coaSeen();
        boolean codSeen = collected.codSeen();
        List<DeliveryEvent> reports = collected.reports();

        // Cada relatorio COA/COD deve correlacionar ao messageId original (correlId == messageId).
        boolean correlationOk = reports.stream()
                .filter(e -> e.feedbackCode() == MQConstants.MQFB_COA
                        || e.feedbackCode() == MQConstants.MQFB_COD)
                .allMatch(e -> messageId != null && messageId.equals(e.correlationId()));

        boolean passed = coaSeen && codSeen && correlationOk;
        return new DemoResult(passed, messageId, coaSeen, codSeen, correlationOk, List.copyOf(reports));
    }

    /** Imprime o resumo final PASS/FAIL da demo (a unica saida que o runner acrescenta a narracao). */
    private void logSummary(DemoResult result) {
        if (result.passed()) {
            LOG.info("[demo=COA/COD] [resultado=PASS] Fluxo validado: COA(feedback={})={}, "
                            + "COD(feedback={})={}, correlId==messageId={} (messageId={}). "
                            + "Relatorios colhidos={}.",
                    MQConstants.MQFB_COA, result.coaSeen(),
                    MQConstants.MQFB_COD, result.codSeen(),
                    result.correlationOk(), result.messageId(), result.reports().size());
        } else {
            LOG.warn("[demo=COA/COD] [resultado=FAIL] Validacao falhou: COA(feedback={})={}, "
                            + "COD(feedback={})={}, correlId==messageId={} (messageId={}). "
                            + "Relatorios colhidos={}. Dica: confirme broker no ar + config admin "
                            + "(DEV.ADMIN.SVRCONN) para a autoridade de contexto que gera os relatorios.",
                    MQConstants.MQFB_COA, result.coaSeen(),
                    MQConstants.MQFB_COD, result.codSeen(),
                    result.correlationOk(), result.messageId(), result.reports().size());
        }
    }

    /**
     * Resultado da demo: veredito agregado + sinais individuais que sustentam o resumo e os testes.
     *
     * @param passed        verdadeiro quando COA+COD foram vistos e ambos correlacionam ao messageId.
     * @param messageId     JMSMessageID retornado pelo {@code producer.send(...)} (chave de correlacao).
     * @param coaSeen       se chegou um relatorio COA (feedback {@code MQFB_COA}=259).
     * @param codSeen       se chegou um relatorio COD (feedback {@code MQFB_COD}=260).
     * @param correlationOk se todos os relatorios COA/COD tem {@code correlationId == messageId}.
     * @param reports       a lista de relatorios colhidos (copia imutavel).
     */
    record DemoResult(
            boolean passed,
            String messageId,
            boolean coaSeen,
            boolean codSeen,
            boolean correlationOk,
            List<DeliveryEvent> reports
    ) {
    }
}
