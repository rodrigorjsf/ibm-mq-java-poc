package com.example.ibmmq.correlation;

import com.example.ibmmq.model.PendingMessage;
import io.micronaut.context.annotation.Primary;
import jakarta.inject.Singleton;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementacao em memoria do {@link CorrelationStore}, baseada em {@link ConcurrentHashMap}.
 *
 * <p>Thread-safe e adequada para um unico processo. <b>Limitacao:</b> as pendencias se perdem ao
 * reiniciar a JVM — se um relatorio COA/COD chegar depois do restart, nao havera entrada para
 * correlacionar. Para sobreviver a restart use uma implementacao persistente (ver
 * {@code PersistentCorrelationStoreExample}).</p>
 *
 * <p>Marcada {@code @Primary} para ser o bean padrao injetado quando ha multiplas implementacoes
 * de {@link CorrelationStore} no contexto.</p>
 */
@Singleton
@Primary
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
