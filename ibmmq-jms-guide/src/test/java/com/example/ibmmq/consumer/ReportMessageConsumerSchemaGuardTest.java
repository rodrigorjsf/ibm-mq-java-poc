package com.example.ibmmq.consumer;

import com.example.ibmmq.config.MqProperties;
import com.example.ibmmq.correlation.InMemoryCorrelationStore;
import com.example.ibmmq.messaging.ReceivePort;
import com.example.ibmmq.messaging.ReportEnvelope;
import com.example.ibmmq.model.PendingMessage;
import com.example.ibmmq.model.ReportType;
import com.example.ibmmq.persistence.DeliveryReportSchema;
import com.example.ibmmq.persistence.DeliveryReportWriteRepository;
import com.example.ibmmq.report.ReportFeedbackRouter;
import com.ibm.mq.constants.MQConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes unitarios (surefire, SEM Docker, SEM DB vivo) do guarda de schema-on-first-write do
 * {@link ReportMessageConsumer} (ADR-0010, issue #52).
 *
 * <p>Prova as tres propriedades load-bearing da decisao, sem tocar o banco (a porta de recebimento, o
 * repositorio writer e o {@link DeliveryReportSchema} sao mocks):</p>
 * <ul>
 *   <li><b>Exactly-once-SUCCESS:</b> apos um primeiro {@code ensureSchema()} que retorna com sucesso, N
 *       escritas de auditoria chamam {@code ensureSchema()} <em>exatamente uma vez</em> e
 *       {@code insertIfAbsent(...)} N vezes — e o ensure precede o primeiro insert.</li>
 *   <li><b>Nada de DB sem insert:</b> quando nao ha datasource (repo nulo) o consumer nunca chama
 *       {@code ensureSchema()} — nenhuma conexao e aberta ate o primeiro insert real. (No contexto
 *       Micronaut isso e reforcado pelo {@code CorrelationStoreNamedDatasourcesSmokeTest}, que so
 *       resolve DEFINICOES de bean e nunca instancia o schema bean.)</li>
 *   <li><b>Best-effort no primeiro insucesso:</b> se o primeiro {@code ensureSchema()} lanca (Postgres
 *       transitoriamente indisponivel), o latch NAO vira, o insert e pulado, a reconciliacao continua, e
 *       a proxima escrita REtenta o ensure.</li>
 * </ul>
 */
@DisplayName("Guarda de schema-on-first-write do ReportMessageConsumer (ADR-0010, #52)")
class ReportMessageConsumerSchemaGuardTest {

    private static final String MSG_ID = "ID:414d51204d513120202020202020202000000001";

    /**
     * Constroi o consumer com o repo writer e o schema mockados como os DOIS ultimos argumentos do
     * construtor (apos o {@link ReportFeedbackRouter}), e uma pendencia ja registrada para que a
     * correlacao reversa resolva.
     */
    private static ReportMessageConsumer consumerWith(InMemoryCorrelationStore store,
                                                      DeliveryReportWriteRepository repo,
                                                      DeliveryReportSchema schema) {
        store.register(PendingMessage.newlySent(MSG_ID, "pedido-52", "{}"));
        return new ReportMessageConsumer(
                mock(ReceivePort.class), new MqProperties(), store, new ReportFeedbackRouter(), repo, schema);
    }

    /** Um relatorio COA sintetico (feedback 259) correlacionado a {@link #MSG_ID}. */
    private static ReportEnvelope coaReport() {
        return ReportEnvelope.synthetic(MQConstants.MQFB_COA, MSG_ID, "", ReportType.COA);
    }

    @Test
    @DisplayName("Apos o primeiro ensureSchema() com sucesso, N escritas chamam ensureSchema() UMA vez e insertIfAbsent N vezes (e o ensure precede o insert)")
    void ensureSchemaRunsExactlyOnceAcrossNWrites() {
        InMemoryCorrelationStore store = new InMemoryCorrelationStore();
        DeliveryReportWriteRepository repo = mock(DeliveryReportWriteRepository.class);
        DeliveryReportSchema schema = mock(DeliveryReportSchema.class);
        when(repo.insertIfAbsent(anyString(), anyString(), anyString(), anyInt(), any(),
                any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

        ReportMessageConsumer consumer = consumerWith(store, repo, schema);

        for (int i = 0; i < 3; i++) {
            consumer.handleReport(coaReport());
        }

        // ensureSchema() exatamente uma vez (latch idempotente); insertIfAbsent uma vez por escrita.
        verify(schema, times(1)).ensureSchema();
        verify(repo, times(3)).insertIfAbsent(anyString(), anyString(), anyString(), anyInt(), any(),
                any(), any(), any(), any(), any(), any(), any());

        // O ensure PRECEDE o(s) insert(s): o guarda corre antes do delegate. atLeastOnce() no passo do repo
        // porque o InOrder consome todas as 3 invocacoes de insert apos o ensureSchema() (uma verificacao
        // estrita de uma unica chamada falharia com "wanted 1 but was 3").
        InOrder order = inOrder(schema, repo);
        order.verify(schema).ensureSchema();
        order.verify(repo, atLeastOnce()).insertIfAbsent(anyString(), anyString(), anyString(), anyInt(), any(),
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Sem datasource (repo nulo), o consumer NUNCA chama ensureSchema() — nenhuma conexao ate o primeiro insert")
    void ensureSchemaNeverRunsWhenAuditRepositoryIsAbsent() {
        InMemoryCorrelationStore store = new InMemoryCorrelationStore();
        DeliveryReportSchema schema = mock(DeliveryReportSchema.class);

        // auditRepository == null: nenhum datasource configurado (contexto unitario/smoke).
        ReportMessageConsumer consumer = consumerWith(store, null, schema);

        for (int i = 0; i < 3; i++) {
            consumer.handleReport(coaReport());
        }

        verify(schema, never()).ensureSchema();
    }

    @Test
    @DisplayName("ensureSchema() que falha (Postgres indisponivel) NAO vira o latch, pula o insert, continua a reconciliacao e REtenta na proxima escrita")
    void transientEnsureFailureIsBestEffortAndRetriedNextWrite() {
        InMemoryCorrelationStore store = new InMemoryCorrelationStore();
        DeliveryReportWriteRepository repo = mock(DeliveryReportWriteRepository.class);
        DeliveryReportSchema schema = mock(DeliveryReportSchema.class);
        when(repo.insertIfAbsent(anyString(), anyString(), anyString(), anyInt(), any(),
                any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
        // Toda chamada ao ensureSchema() falha (Postgres transitoriamente indisponivel).
        doThrow(new IllegalStateException("db down")).when(schema).ensureSchema();

        ReportMessageConsumer consumer = consumerWith(store, repo, schema);

        for (int i = 0; i < 3; i++) {
            // A reconciliacao continua mesmo com o ensure falhando (relatorio ja foi ackado): nao lanca,
            // retorna um DeliveryEvent nao-nulo.
            assertThat(consumer.handleReport(coaReport()))
                    .as("o caminho ja-ackado nunca aborta por falha de schema-init (best-effort)")
                    .isNotNull();
        }

        // O latch so vira no SUCESSO: como toda tentativa falha, o ensure e REtentado a cada escrita...
        verify(schema, times(3)).ensureSchema();
        // ...e nenhum insert acontece enquanto o schema nao estiver pronto (insert pulado apos a falha).
        verify(repo, never()).insertIfAbsent(anyString(), anyString(), anyString(), anyInt(), any(),
                any(), any(), any(), any(), any(), any(), any());
    }
}
