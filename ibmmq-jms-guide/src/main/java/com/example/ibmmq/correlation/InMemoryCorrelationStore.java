package com.example.ibmmq.correlation;

import com.example.ibmmq.model.PendingMessage;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.time.Instant;
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
        // merge preservando os flags: se um relatorio chegou ANTES do register e criou um stub (coa/cod
        // ja marcado), o register preenche os campos descritivos SEM zerar os flags (espelha o
        // ON CONFLICT DO UPDATE do JdbcCorrelationStore).
        store.merge(pending.messageId(), pending, (existing, incoming) ->
                new PendingMessage(incoming.messageId(), incoming.businessKey(), incoming.payload(),
                        incoming.sentAt(),
                        existing.coaReceived() || incoming.coaReceived(),
                        existing.codReceived() || incoming.codReceived()));
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
        return markFlag(messageId, true, false);
    }

    @Override
    public Optional<PendingMessage> markCodReceived(String messageId) {
        return markFlag(messageId, false, true);
    }

    /**
     * Marca um flag criando a linha como stub se ela ainda nao existe (relatorio chegou ANTES do
     * register) — espelha o UPSERT do {@link JdbcCorrelationStore}. {@code compute} torna a operacao
     * atomica mesmo sob relatorios concorrentes; setar um flag ja TRUE de novo e no-op.
     */
    private Optional<PendingMessage> markFlag(String messageId, boolean coa, boolean cod) {
        if (messageId == null) {
            return Optional.empty();
        }
        return Optional.of(store.compute(messageId, (k, existing) -> existing == null
                ? new PendingMessage(messageId, null, null, Instant.now(), coa, cod)
                : new PendingMessage(existing.messageId(), existing.businessKey(), existing.payload(),
                        existing.sentAt(),
                        existing.coaReceived() || coa,
                        existing.codReceived() || cod)));
    }

    @Override
    public void remove(String messageId) {
        if (messageId != null) {
            store.remove(messageId);
        }
    }

    @Override
    public boolean removeIfFullyConfirmed(String messageId) {
        if (messageId == null) {
            return false;
        }
        // Check-and-remove atomico: computeIfPresent retornando null remove a entrada. O holder captura
        // se FOI esta chamada que observou ambos os flags TRUE e removeu (exatamente uma vence a corrida).
        boolean[] removed = {false};
        store.computeIfPresent(messageId, (k, existing) -> {
            if (existing.isFullyConfirmed()) {
                removed[0] = true;
                return null; // remove
            }
            return existing;
        });
        return removed[0];
    }

    @Override
    public int pendingCount() {
        return store.size();
    }
}
