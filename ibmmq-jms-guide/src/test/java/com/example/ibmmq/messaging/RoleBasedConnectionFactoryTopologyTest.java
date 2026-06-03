package com.example.ibmmq.messaging;

import com.example.ibmmq.config.MqConnectionFactoryFactory;
import com.example.ibmmq.consumer.BusinessMessageConsumer;
import com.example.ibmmq.consumer.ReportMessageConsumer;
import com.example.ibmmq.producer.BusinessMessageProducer;
import com.ibm.mq.jms.MQConnectionFactory;
import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.qualifiers.Qualifiers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.messaginghub.pooled.jms.JmsPoolConnectionFactory;

import javax.jms.ConnectionFactory;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Teste de topologia broker-free das DUAS connection factories baseadas em papel (ADR-0006).
 *
 * <p>Construir um {@link MQConnectionFactory} / {@link JmsPoolConnectionFactory} NAO abre socket: a
 * conexao e preguicosa (so ocorre em {@code createContext}). Por isso este teste sobe um
 * {@link ApplicationContext} de verdade e apenas RESOLVE os beans, sem tocar o broker. Usamos
 * {@code messaging.adapter=fake} nos casos de topologia das factories para deixar explicito que nenhum
 * adapter de producao precisa ser instanciado para provar o wiring das factories; um segundo contexto
 * (sem {@code fake}) prova que os adapters de producao resolvem suas factories {@code @Named}.</p>
 */
@DisplayName("RoleBasedConnectionFactoryTopology — duas factories por papel (ADR-0006), broker-free")
class RoleBasedConnectionFactoryTopologyTest {

    /**
     * Pins a valid {@code ibm-mq.password} so the context boots deterministically under the eager
     * {@code @Context} validation added in issue #27 / ADR-0011. Without this pin the ambient
     * {@code ${IBM_MQ_PASSWORD:passw0rd}} default would resolve to a blank password when the env var is
     * exported empty (the orchestrate-worktree footgun), and the credential cross-field rule (user=app +
     * blank password) would refuse to boot. The optional {@code extra} entries are merged on top (used to
     * inject {@code messaging.adapter=fake} for the factory-topology cases).
     */
    private static Map<String, Object> bootProps(Map<String, Object> extra) {
        java.util.Map<String, Object> props = new java.util.HashMap<>();
        props.put("ibm-mq.password", "passw0rd");
        props.putAll(extra);
        return props;
    }

    @Test
    @DisplayName("(1) o bean @Named(PRODUCER) e o JmsPoolConnectionFactory pooled (produtor)")
    void producerFactoryIsPooled() {
        try (ApplicationContext ctx = ApplicationContext.run(bootProps(Map.of("messaging.adapter", "fake")))) {
            JmsPoolConnectionFactory producer = ctx.getBean(
                    JmsPoolConnectionFactory.class, Qualifiers.byName(MqConnectionFactoryFactory.PRODUCER));
            assertThat(producer)
                    .as("o bean @Named(PRODUCER) deve ser o pool de conexoes do produtor")
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("(2) o bean @Named(CONSUMER) e o MQConnectionFactory dedicado nao-pooled (consumidor)")
    void consumerFactoryIsDedicatedNonPooled() {
        try (ApplicationContext ctx = ApplicationContext.run(bootProps(Map.of("messaging.adapter", "fake")))) {
            MQConnectionFactory consumer = ctx.getBean(
                    MQConnectionFactory.class, Qualifiers.byName(MqConnectionFactoryFactory.CONSUMER));
            assertThat(consumer)
                    .as("o bean @Named(CONSUMER) deve ser o MQConnectionFactory dedicado, sem pool")
                    .isNotNull()
                    .isNotInstanceOf(JmsPoolConnectionFactory.class);
        }
    }

    @Test
    @DisplayName("(3) @Primary: ConnectionFactory nao-qualificado resolve para a instancia do PRODUCER")
    void unqualifiedConnectionFactoryResolvesToPrimaryProducer() {
        try (ApplicationContext ctx = ApplicationContext.run(bootProps(Map.of("messaging.adapter", "fake")))) {
            // Sem @Primary no produtor, esta resolucao nao-qualificada lancaria NonUniqueBeanException
            // (duas factories candidatas). Esta assercao e RED sem @Primary.
            ConnectionFactory unqualified = ctx.getBean(ConnectionFactory.class);
            JmsPoolConnectionFactory producer = ctx.getBean(
                    JmsPoolConnectionFactory.class, Qualifiers.byName(MqConnectionFactoryFactory.PRODUCER));

            assertThat(unqualified)
                    .as("a injecao nao-qualificada deve resolver para o produtor @Primary (mesma instancia)")
                    .isSameAs(producer);
        }
    }

    @Test
    @DisplayName("(4) os tres pontos de entrada ainda resolvem (sem NonUniqueBeanException)")
    void entryPointsStillWireWithoutAmbiguity() {
        try (ApplicationContext ctx = ApplicationContext.run(bootProps(Map.of("messaging.adapter", "fake")))) {
            // Os tres pontos de entrada agora injetam as ports do seam (SendPort/ReceivePort), ja
            // religadas na fatia 3 / #57. @Primary no produtor mantem a resolucao da factory de produtor
            // nao-ambigua mesmo com as duas factories @Named presentes (usada pelo PooledJmsSendAdapter).
            assertThatCode(() -> {
                assertThat(ctx.getBean(BusinessMessageProducer.class)).isNotNull();
                assertThat(ctx.getBean(BusinessMessageConsumer.class)).isNotNull();
                assertThat(ctx.getBean(ReportMessageConsumer.class)).isNotNull();
            }).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("(5) os adapters de producao resolvem suas factories @Named (prova do rewire desta fatia)")
    void productionAdaptersResolveTheirNamedFactories() {
        // SEM messaging.adapter=fake: os adapters de producao sao os unicos SendPort/ReceivePort. Construir
        // os adapters apenas guarda a referencia da factory (connect e lazy em createContext), entao isto
        // segue broker-free. Esta e a assercao que discrimina "rewire funciona" de "rewire compila": um
        // qualifier errado ou um tipo de campo nao-revertido so apareceria aqui (ou no IT da fatia 4).
        try (ApplicationContext ctx = ApplicationContext.run(bootProps(Map.of()))) {
            SendPort sendPort = ctx.getBean(SendPort.class);
            ReceivePort receivePort = ctx.getBean(ReceivePort.class);

            assertThat(sendPort)
                    .as("o SendPort de producao deve ser o PooledJmsSendAdapter (sobre a factory PRODUCER)")
                    .isInstanceOf(PooledJmsSendAdapter.class);
            assertThat(receivePort)
                    .as("o ReceivePort de producao deve ser o PooledJmsReceiveAdapter (sobre a factory CONSUMER)")
                    .isInstanceOf(PooledJmsReceiveAdapter.class);
        }
    }
}
