package com.example.ibmmq.demo;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test (surefire, NO broker, NO Docker) of the GATING of the COA/COD demo runner when the flag
 * is ABSENT or different from {@code true} — direct proof of AC#3: the normal application startup is
 * NOT affected when the flag is not present.
 *
 * <p>Boots a real {@code ApplicationContext} WITHOUT the {@code demo.coa-cod.enabled} property
 * (and, in a second case, with it set to {@code false}). Since {@link CoaCodDemoRunner} is annotated
 * with {@code @Requires(property = "demo.coa-cod.enabled", value = "true")}, the bean is NOT
 * instantiated — {@code containsBean} returns {@code false}. Nothing touches the broker: the runner
 * is the only {@code ApplicationEventListener<StartupEvent>} in the context and the JMS beans are
 * lazy, so a context without the flag opens no MQ connections.</p>
 */
@DisplayName("Gating da demo COA/COD (AC#3): sem o flag, o runner NAO existe e o startup nao e afetado")
class CoaCodDemoRunnerGatingTest {

    /**
     * Pins a valid {@code ibm-mq.password} so the context boots deterministically under the eager
     * {@code @Context} validation of {@code MqProperties} (issue #27 / ADR-0011). Without it the ambient
     * {@code ${IBM_MQ_PASSWORD:passw0rd}} default resolves blank when the env var is exported empty, tripping
     * the credential rule (user=app + blank password) and refusing to boot. The optional {@code extra}
     * entries are merged on top (e.g. the demo flag).
     */
    private static Map<String, Object> bootProps(Map<String, Object> extra) {
        java.util.Map<String, Object> props = new java.util.HashMap<>();
        props.put("ibm-mq.password", "passw0rd");
        props.putAll(extra);
        return props;
    }

    @Test
    @DisplayName("Flag ausente: containsBean(CoaCodDemoRunner) == false")
    void runnerAbsentWhenFlagMissing() {
        try (ApplicationContext ctx = ApplicationContext.run(bootProps(Map.of()))) {
            assertThat(ctx.containsBean(CoaCodDemoRunner.class))
                    .as("runner must be ABSENT without the demo.coa-cod.enabled flag")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("Flag em false: containsBean(CoaCodDemoRunner) == false (valor deve ser exatamente true)")
    void runnerAbsentWhenFlagFalse() {
        // PROPERTY overload: run(Map<String,Object>) injects the key/value pair as a property
        // (as opposed to run(String...) which would interpret the arguments as environment NAMES).
        // This tests the flag actually PRESENT but != "true": @Requires(value="true") rejects the bean.
        try (ApplicationContext ctx = ApplicationContext.run(bootProps(Map.of("demo.coa-cod.enabled", "false")))) {
            assertThat(ctx.containsBean(CoaCodDemoRunner.class))
                    .as("runner must be ABSENT when flag != true")
                    .isFalse();
        }
    }
}
