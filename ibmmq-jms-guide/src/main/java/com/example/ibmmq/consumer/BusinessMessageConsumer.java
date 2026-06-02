package com.example.ibmmq.consumer;

import com.example.ibmmq.config.MqProperties;
import com.example.ibmmq.messaging.ReceivePort;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Consome (GET destrutivo) da fila de negocio. <b>E este consumo que dispara o relatorio COD.</b>
 *
 * <p><b>COD-on-consume:</b> o relatorio COD e gerado pelo gerenciador de filas no momento em que a
 * mensagem e recuperada destrutivamente. Nao ha API JMS para "pedir" o COD no consumo — ele decorre
 * automaticamente das opcoes de report ja gravadas no MQMD pela mensagem original.</p>
 *
 * <p><b>Syncpoint / timing:</b> o consumo roda dentro de uma unidade de trabalho (UoW) transacionada.
 * O COD e gerado dentro dessa UoW e <em>so fica disponivel apos o commit</em>. Se a UoW sofrer
 * rollback (backout), o COD nao e enviado e a mensagem volta para a fila — coerente com "entregue de
 * verdade".</p>
 *
 * <p><b>Seam (ADR-0008):</b> este entry point nao abre mais um {@code JMSContext} proprio — delega ao
 * {@link ReceivePort#receiveWithinUnitOfWork}. A porta executa o handler DENTRO da UoW e
 * <b>commita</b> ao retorno normal (liberando o COD) ou faz <b>rollback</b> ao lancar (sem COD); este
 * consumidor NUNCA chama commit()/rollback() diretamente. Nenhum {@code javax.jms.Message} chega aqui —
 * o handler ve apenas o corpo decodificado.</p>
 *
 * <p><b>Nota de observabilidade (ADR-0008, consequencia intencional):</b> o seam expoe apenas o CORPO
 * da mensagem consumida, NAO o messageId consumido (a assinatura {@code handle(String body)} e fixa).
 * Por isso as linhas {@code [stage=CONSUME]}/{@code [stage=COMMIT]} nao podem mais vincular
 * messageId/correlationId no MDC — uma consequencia aceita do seam travado. As tags de etapa e o corpo
 * permanecem nas linhas; apenas o MDC de messageId e omitido para estas duas linhas.</p>
 */
@Singleton
public class BusinessMessageConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(BusinessMessageConsumer.class);

    private final ReceivePort receivePort;
    private final MqProperties props;

    public BusinessMessageConsumer(ReceivePort receivePort, MqProperties props) {
        this.receivePort = receivePort;
        this.props = props;
    }

    /**
     * Recebe uma mensagem da fila de negocio (com timeout), processa e comita.
     *
     * @param timeoutMillis tempo maximo de espera por uma mensagem (ms).
     * @return o corpo da mensagem consumida, ou {@code null} se o timeout expirar sem mensagem.
     */
    public String receiveOne(long timeoutMillis) {
        // Transacted unit of work: the port commits on a normal handler return (releasing the COD) or
        // rolls back on a throw (the message returns; no COD). On timeout it returns null without
        // invoking the handler. We never call commit()/rollback() ourselves.
        String body = receivePort.receiveWithinUnitOfWork(props.getBusinessQueue(), timeoutMillis, consumedBody -> {
            // Observability note (ADR-0008): the seam surfaces only the body, not the consumed
            // messageId, so this line cannot bind messageId/correlationId in MDC.
            LOG.info("[stage=CONSUME] Mensagem de negocio consumida (GET destrutivo): body={}", consumedBody);

            // ... processamento de negocio aqui ...

            return consumedBody;
        });

        if (body == null) {
            LOG.debug("Nenhuma mensagem na fila de negocio dentro do timeout ({} ms)", timeoutMillis);
            return null;
        }

        // The port committed on the handler's normal return — the COD is now released to the report
        // queue. Same observability note: no messageId is available to bind in MDC for this line.
        LOG.info("[stage=COMMIT] Consumo confirmado (commit): COD liberado para a fila de relatorios");

        return body;
    }
}
