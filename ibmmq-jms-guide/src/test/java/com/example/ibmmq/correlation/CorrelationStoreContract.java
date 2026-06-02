package com.example.ibmmq.correlation;

import com.example.ibmmq.correlation.ReconcileResult.Outcome;
import com.example.ibmmq.model.PendingMessage;
import com.example.ibmmq.model.ReportType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Shared CONTRACT TEST for {@link CorrelationStore#recordReport(String, ReportType)} run against BOTH
 * adapters — NOT a shared code path. Each adapter implements its own primitives (InMemory:
 * {@code compute}/{@code computeIfPresent}; Jdbc: UPSERT...RETURNING + conditional DELETE), but the
 * {@code default recordReport} reconciliation logic must behave identically on top of either. The two
 * concrete subclasses ({@link InMemoryCorrelationStoreContractTest} as a surefire {@code *Test};
 * {@code JdbcCorrelationStoreContractIT} as a failsafe {@code *IT}) bind {@link #newStore()} to a fresh
 * store, so the matrix below pins the SAME idempotency + ordering invariants on both.
 *
 * <p><b>What this pins (the idempotency + ordering matrix):</b> COA-then-COD, COD-before-COA, duplicate
 * COA, duplicate COD, an orphan report (no prior registration), and a report arriving after the pair
 * already completed. Assertions favour per-id state ({@code findByMessageId}) over global
 * {@code pendingCount()} so they stay immune to any cross-test row left by another method against a
 * shared table (the Jdbc arm reuses one Postgres table across all methods).</p>
 */
abstract class CorrelationStoreContract {

    private static final String MSG_ID = "ID:414d51204d513120202020202020202000000abc";
    private static final String ORPHAN_ID = "ID:414d51204d513120202020202020202000000fff";

    /** Provides a fresh, isolated {@link CorrelationStore} for each test. */
    protected abstract CorrelationStore newStore();

    @Test
    @DisplayName("COA depois COD: o COA registra (RECORDED), o COD completa o par (COMPLETED) e remove a pendencia")
    void coaThenCod() {
        CorrelationStore store = newStore();
        store.register(PendingMessage.newlySent(MSG_ID, "pedido-coa-cod", "{}"));

        ReconcileResult coa = store.recordReport(MSG_ID, ReportType.COA);
        assertThat(coa.outcome()).as("COA de uma mensagem conhecida ainda nao completa o par").isEqualTo(Outcome.RECORDED);
        assertThat(coa.pending()).as("a pendencia previa (conhecida) acompanha o resultado").isNotNull();
        assertThat(coa.pending().messageId()).isEqualTo(MSG_ID);
        assertThat(store.findByMessageId(MSG_ID)).as("apos so o COA a linha permanece pendente").isPresent();

        ReconcileResult cod = store.recordReport(MSG_ID, ReportType.COD);
        assertThat(cod.outcome()).as("o COD completa COA+COD -> COMPLETED").isEqualTo(Outcome.COMPLETED);
        assertThat(cod.pending()).as("o resultado ainda carrega a pendencia previa para derivar o evento").isNotNull();
        assertThat(store.findByMessageId(MSG_ID)).as("apos a reconciliacao a linha e removida").isEmpty();
    }

    @Test
    @DisplayName("COD antes COA (fora de ordem): o COD registra (RECORDED), o COA completa o par (COMPLETED)")
    void codBeforeCoa() {
        CorrelationStore store = newStore();
        store.register(PendingMessage.newlySent(MSG_ID, "pedido-cod-coa", "{}"));

        ReconcileResult cod = store.recordReport(MSG_ID, ReportType.COD);
        assertThat(cod.outcome()).as("COD sozinho de uma mensagem conhecida nao completa o par").isEqualTo(Outcome.RECORDED);
        assertThat(store.findByMessageId(MSG_ID)).isPresent();

        ReconcileResult coa = store.recordReport(MSG_ID, ReportType.COA);
        assertThat(coa.outcome()).as("independente de ordem: e o COA que fecha o par aqui").isEqualTo(Outcome.COMPLETED);
        assertThat(store.findByMessageId(MSG_ID)).as("par completo fora de ordem tambem drena").isEmpty();
    }

    @Test
    @DisplayName("COA duplicado (entrega at-least-once): o segundo COA e idempotente e segue RECORDED")
    void duplicateCoaIsIdempotent() {
        CorrelationStore store = newStore();
        store.register(PendingMessage.newlySent(MSG_ID, "pedido-coa-dup", "{}"));

        assertThat(store.recordReport(MSG_ID, ReportType.COA).outcome()).isEqualTo(Outcome.RECORDED);
        // Segundo COA (redelivery): nao completa nada, nao remove, segue RECORDED — sem double-count.
        ReconcileResult second = store.recordReport(MSG_ID, ReportType.COA);
        assertThat(second.outcome()).as("COA duplicado permanece RECORDED (idempotente)").isEqualTo(Outcome.RECORDED);
        assertThat(store.findByMessageId(MSG_ID))
                .as("a linha continua pendente com COA=true, COD=false")
                .hasValueSatisfying(p -> {
                    assertThat(p.coaReceived()).isTrue();
                    assertThat(p.codReceived()).isFalse();
                });
    }

    @Test
    @DisplayName("COD duplicado apos COA: o COD que fecha o par e COMPLETED; o COD repetido (linha ja removida) e ORPHAN")
    void duplicateCodAfterCompletion() {
        CorrelationStore store = newStore();
        store.register(PendingMessage.newlySent(MSG_ID, "pedido-cod-dup", "{}"));
        store.recordReport(MSG_ID, ReportType.COA);

        ReconcileResult first = store.recordReport(MSG_ID, ReportType.COD);
        assertThat(first.outcome()).as("o COD que fecha COA+COD e COMPLETED").isEqualTo(Outcome.COMPLETED);

        // Linha ja removida pela reconciliacao -> um COD redelivered nao tem registro previo -> ORPHAN.
        ReconcileResult second = store.recordReport(MSG_ID, ReportType.COD);
        assertThat(second.outcome()).as("COD repetido depois do par completo nao tem linha previa -> ORPHAN").isEqualTo(Outcome.ORPHAN);
        assertThat(second.pending()).as("ORPHAN nao carrega pendencia previa").isNull();
    }

    @Test
    @DisplayName("Relatorio orfao: COA sem registro previo retorna ORPHAN com pending nulo (e cria stub idempotente)")
    void orphanReportHasNoPrior() {
        CorrelationStore store = newStore();
        // Nenhum register para ORPHAN_ID: o relatorio chega para um id que este processo nunca registrou.
        ReconcileResult result = store.recordReport(ORPHAN_ID, ReportType.COA);

        assertThat(result.outcome()).as("relatorio sem registro previo e ORPHAN").isEqualTo(Outcome.ORPHAN);
        assertThat(result.pending()).as("ORPHAN nao tem pendencia previa").isNull();
        // mark* sempre faz upsert: o stub e criado (espelha o comportamento de ambos os adapters).
        assertThat(store.findByMessageId(ORPHAN_ID))
                .as("o mark cria um stub mesmo sem register previo (upsert)")
                .isPresent();
    }

    @Test
    @DisplayName("Relatorio apos par completo (redelivery): o par fecha (COMPLETED), o relatorio tardio e ORPHAN")
    void reportAfterPairCompleted() {
        CorrelationStore store = newStore();
        store.register(PendingMessage.newlySent(MSG_ID, "pedido-pos-completo", "{}"));
        assertThat(store.recordReport(MSG_ID, ReportType.COA).outcome()).isEqualTo(Outcome.RECORDED);
        assertThat(store.recordReport(MSG_ID, ReportType.COD).outcome()).isEqualTo(Outcome.COMPLETED);
        assertThat(store.findByMessageId(MSG_ID)).as("par completo -> linha removida").isEmpty();

        // Um COA redelivered chega DEPOIS do par completo: a linha foi removida, logo nao ha registro previo.
        ReconcileResult late = store.recordReport(MSG_ID, ReportType.COA);
        assertThat(late.outcome())
                .as("relatorio apos o par completo recria um stub orfao -> ORPHAN (orphan-on-redelivery)")
                .isEqualTo(Outcome.ORPHAN);
        assertThat(late.pending()).isNull();
    }

    @Test
    @DisplayName("recordReport rejeita tipos nao-COA/COD com IllegalArgumentException (falha alta em uso indevido)")
    void rejectsNonCoaCodTypes() {
        CorrelationStore store = newStore();
        assertThatThrownBy(() -> store.recordReport(MSG_ID, ReportType.EXCEPTION))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.recordReport(MSG_ID, ReportType.EXPIRATION))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
