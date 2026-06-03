package com.example.ibmmq.demo;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test (surefire, NO broker, NO Docker) of the POSITIVE gating path for the COA/COD demo runner:
 * when {@code demo.coa-cod.enabled=true} the runner bean IS present in the {@code ApplicationContext}.
 *
 * <p>The context is booted with {@code messaging.adapter=fake} so the demo's {@code onApplicationEvent}
 * fires against the in-memory broker ({@link com.example.ibmmq.messaging.InMemoryBroker}) instead of a
 * real IBM MQ instance. This keeps the test broker-free and fast while exercising the full gating path:
 * the bean is instantiated, the startup event fires, and {@code containsBean} is checked against a live
 * {@code ApplicationContext}.</p>
 *
 * <p>The negative gating paths (flag absent / flag=false → bean absent) are covered in
 * {@link CoaCodDemoRunnerGatingTest}.</p>
 */
@DisplayName("Gating da demo COA/COD (AC#3): com o flag em true, o runner EXISTE no contexto")
class CoaCodDemoRunnerEnabledGatingTest {

    /**
     * Pins a valid {@code ibm-mq.password} so the context boots deterministically under the eager
     * {@code @Context} validation of {@code MqProperties} (issue #27 / ADR-0011). Without it the ambient
     * {@code ${IBM_MQ_PASSWORD:passw0rd}} default resolves blank when the env var is exported empty, tripping
     * the credential rule (user=app + blank password) and refusing to boot. The optional {@code extra}
     * entries are merged on top.
     */
    private static Map<String, Object> bootProps(Map<String, Object> extra) {
        Map<String, Object> props = new HashMap<>();
        props.put("ibm-mq.password", "passw0rd");
        props.putAll(extra);
        return props;
    }

    @Test
    @DisplayName("Flag em true: containsBean(CoaCodDemoRunner) == true (bean instanciado pelo Micronaut)")
    void runnerPresentWhenFlagTrue() {
        // messaging.adapter=fake routes the demo through the in-memory broker (InMemoryBroker),
        // so onApplicationEvent fires and completes without any real MQ connection.
        Map<String, Object> props = bootProps(Map.of(
                "demo.coa-cod.enabled", "true",
                "messaging.adapter", "fake"
        ));
        try (ApplicationContext ctx = ApplicationContext.run(props)) {
            assertThat(ctx.containsBean(CoaCodDemoRunner.class))
                    .as("runner deve estar PRESENTE quando demo.coa-cod.enabled=true")
                    .isTrue();
        }
    }
}
