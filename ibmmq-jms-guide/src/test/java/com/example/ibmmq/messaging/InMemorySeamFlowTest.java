package com.example.ibmmq.messaging;

import com.ibm.mq.constants.MQConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Teste de porta broker-free: valida a fidelidade dos fakes in-memory contra os contratos de
 * {@link SendPort} / {@link ReceivePort} (ADR-0008 AC5). Sem broker, sem Micronaut, sem ponto de
 * entrada de producao — apenas {@link InMemoryBroker}, {@link InMemorySendPort} e
 * {@link InMemoryReceivePort} construidos diretamente.
 *
 * <p>A suíte e sequencial por design: a fila de relatorios e um FIFO unico; a COA deve ser
 * drenada antes que a COD seja visivel.</p>
 */
@DisplayName("InMemorySeamFlow — fidelidade dos fakes COA/COD sem broker")
class InMemorySeamFlowTest {

    private InMemoryBroker broker;
    private SendPort sendPort;
    private ReceivePort receivePort;

    @BeforeEach
    void setUp() {
        broker = new InMemoryBroker();
        sendPort = new InMemorySendPort(broker);
        receivePort = new InMemoryReceivePort(broker);
    }

    @Test
    @DisplayName("(1) send retorna messageId com prefixo 'ID:'")
    void sendRetornaMessageIdComPrefixo() {
        String messageId = sendPort.send(
                OutboundMessage.persistentWithCoaCod("pedido-1", "{\"pedido\":1}", "DEV.QUEUE.1", "DEV.QUEUE.2"));

        assertThat(messageId).startsWith("ID:");
    }

    @Test
    @DisplayName("(2) receiveReport antes do consume retorna a COA (259) com correlationId == messageId")
    void receiveReportAntesDoConsumeRetornaCoA() {
        String messageId = sendPort.send(
                OutboundMessage.persistentWithCoaCod("pedido-1", "{\"pedido\":1}", "DEV.QUEUE.1", "DEV.QUEUE.2"));

        ReportEnvelope coa = receivePort.receiveReport("DEV.QUEUE.2", 1000L);

        assertThat(coa).isNotNull();
        assertThat(coa.feedbackCode()).isEqualTo(MQConstants.MQFB_COA);
        assertThat(coa.correlationId()).isEqualTo(messageId);
    }

    @Test
    @DisplayName("(3) receiveWithinUnitOfWork + commit retorna a COD (260) com correlationId == messageId")
    void receiveWithinUnitOfWorkComCommitRetornaCod() {
        String messageId = sendPort.send(
                OutboundMessage.persistentWithCoaCod("pedido-1", "{\"pedido\":1}", "DEV.QUEUE.1", "DEV.QUEUE.2"));

        // Drena a COA que esta na cabeca da fila de relatorios.
        ReportEnvelope coa = receivePort.receiveReport("DEV.QUEUE.2", 1000L);
        assertThat(coa).isNotNull();
        assertThat(coa.feedbackCode()).isEqualTo(MQConstants.MQFB_COA);

        // Consume com sucesso -> commit -> COD enfileirada.
        receivePort.receiveWithinUnitOfWork("DEV.QUEUE.1", 1000L, body -> body);

        // A COD deve ser o proximo relatorio na fila.
        ReportEnvelope cod = receivePort.receiveReport("DEV.QUEUE.2", 1000L);
        assertThat(cod).isNotNull();
        assertThat(cod.feedbackCode()).isEqualTo(MQConstants.MQFB_COD);
        assertThat(cod.correlationId()).isEqualTo(messageId);
    }

    @Test
    @DisplayName("(4) rollback: sem COD apos falha do handler; mensagem de negocio retorna para reprocessamento")
    void rollbackNaoGeraCoDAMensagemRetorna() {
        String messageId = sendPort.send(
                OutboundMessage.persistentWithCoaCod("pedido-rollback", "{\"pedido\":2}", "DEV.QUEUE.1", "DEV.QUEUE.2"));

        // Drena a COA da mensagem enviada.
        ReportEnvelope coa = receivePort.receiveReport("DEV.QUEUE.2", 1000L);
        assertThat(coa).isNotNull();
        assertThat(coa.feedbackCode()).isEqualTo(MQConstants.MQFB_COA);
        assertThat(coa.correlationId()).isEqualTo(messageId);

        // Handler falha -> rollback -> mensagem retorna para a fila de negocios; COD NAO e gerada.
        assertThatThrownBy(() ->
                receivePort.receiveWithinUnitOfWork("DEV.QUEUE.1", 1000L, body -> {
                    throw new IllegalStateException("boom");
                })
        ).isInstanceOf(IllegalStateException.class);

        // Nenhuma COD deve estar na fila de relatorios (apenas a COA ja foi drenada).
        ReportEnvelope noCod = receivePort.receiveReport("DEV.QUEUE.2", 1000L);
        assertThat(noCod).isNull();

        // A mensagem de negocios retornou para a fila: um consumo normal a recupera com sucesso.
        String recovered = receivePort.receiveWithinUnitOfWork("DEV.QUEUE.1", 1000L, body -> body);
        assertThat(recovered).isNotNull();
    }
}
