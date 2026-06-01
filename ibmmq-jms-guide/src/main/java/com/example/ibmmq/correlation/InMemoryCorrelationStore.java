package com.example.ibmmq.correlation;

import com.example.ibmmq.model.PendingMessage;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementacao em memoria do {@link CorrelationStore}, baseada em {@link ConcurrentHashMap}.
 *
 * <p>Thread-safe e adequada para um unico processo. <b>Limitacao:</b> as pendencias se perdem ao
 * reiniciar a JVM — se um relatorio COA/COD chegar depois do restart, nao havera entrada para
 * correlacionar. Pior ainda em um cluster: cada pod tem seu proprio mapa, entao um COA recebido pelo
 * pod A e o COD recebido pelo pod B nunca se reconciliam. Para correlacao cluster-wide use a
 * implementacao persistente/compartilhada ({@link JdbcCorrelationStore}).</p>
 *
 * <p><b>Selecao de bean (gating):</b> esta implementacao e o <em>default</em> — fica ativa sempre que
 * a propriedade {@code correlation.store} <em>nao</em> for {@code jdbc} (ou estiver ausente). O par
 * {@link JdbcCorrelationStore} usa o gate complementar {@code correlation.store=jdbc}. Os dois gates
 * sao mutuamente exclusivos, garantindo que exista <b>exatamente um</b> bean {@link CorrelationStore}
 * em qualquer configuracao — testes e o default de desenvolvimento recebem este (in-memory) sem
 * precisar setar nada; o harness k3s seta {@code correlation.store=jdbc} para ativar o store
 * compartilhado. (Substitui o antigo {@code @Primary}, que nao desambigua quando dois beans coexistem.)</p>
 */
@Singleton
@Requires(property = "correlation.store", notEquals = "jdbc")
public class InMemoryCorrelationStore implements CorrelationStore {

    // Chave = MessageId original (JMSMessageID). Valor = pendencia.
    private final ConcurrentHashMap<String, PendingMessage> store = new ConcurrentHashMap<>();

    @Override
    public void register(PendingMessage pending) {
        store.put(pending.messageId(), pending);
    }

    @Override
    public Optional<PendingMessage> findByMessageId(String messageId) {
        if (messageId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.get(messageId));
    }

    @Override
    public Optional<PendingMessage> markCoaReceived(String messageId) {
        // compute garante atomicidade da atualizacao mesmo sob concorrencia de relatorios.
        return updateAtomically(messageId, PendingMessage::withCoaReceived);
    }

    @Override
    public Optional<PendingMessage> markCodReceived(String messageId) {
        return updateAtomically(messageId, PendingMessage::withCodReceived);
    }

    private Optional<PendingMessage> updateAtomically(
            String messageId,
            java.util.function.UnaryOperator<PendingMessage> mutation) {
        if (messageId == null) {
            return Optional.empty();
        }
        PendingMessage updated = store.computeIfPresent(messageId, (k, existing) -> mutation.apply(existing));
        return Optional.ofNullable(updated);
    }

    @Override
    public void remove(String messageId) {
        if (messageId != null) {
            store.remove(messageId);
        }
    }

    @Override
    public int pendingCount() {
        return store.size();
    }
}
