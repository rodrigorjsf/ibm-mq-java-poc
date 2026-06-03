package com.example.ibmmq.config;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Startup-validation proof (issue #27, AC2/AC3): a misconfigured context REFUSES TO BOOT, a valid one boots
 * cleanly — proven by booting a real {@link ApplicationContext} and asserting on {@code run(...).close()}.
 *
 * <p>This is the half that an earlier WIP attempt could not satisfy: the WIP {@code MqProperties} was only
 * {@code @Validated @ConfigurationProperties} with no {@code @Context}, so the lazy bean was never validated
 * at boot and a bad config started cleanly. ADR-0011's fix — adding {@code @Context} — is what makes
 * {@code ApplicationContext.run(badMap).close()} throw here.</p>
 *
 * <p><b>Environment independence (AC3).</b> EVERY property map below — valid AND invalid — pins its own
 * {@code ibm-mq.password}, so the proof never depends on the ambient {@code IBM_MQ_PASSWORD} (the orchestrate
 * worktree exported it empty, which would otherwise make even a "valid" config trip the credential rule).
 * Pinning the password in the invalid maps too means each invalid case fails for the ONE reason it isolates
 * (bad port / blank channel / etc.), never incidentally because of a blank password.</p>
 *
 * <p><b>Exception shape.</b> A failing {@code @Context} bean makes {@code run(...)} throw a Micronaut bean
 * exception wrapping either a {@code ConstraintViolationException} (jakarta path) or the
 * {@code IllegalArgumentException} from {@link MqProperties#validateCrossFieldConstraints()}. We do NOT pin
 * the exact wrapper class; we assert it throws and that the throwable chain mentions the offending field.</p>
 */
@DisplayName("MqProperties — prova de boot fail-fast: config invalida NAO sobe, config valida sobe (AC2/AC3)")
class MqPropertiesStartupValidationTest {

    /** A fully valid base config that boots cleanly; each invalid case overrides exactly one entry. */
    private static Map<String, Object> validBase() {
        Map<String, Object> props = new HashMap<>();
        props.put("ibm-mq.host", "localhost");
        props.put("ibm-mq.port", 1414);
        props.put("ibm-mq.channel", "DEV.APP.SVRCONN");
        props.put("ibm-mq.queue-manager", "QM1");
        props.put("ibm-mq.user", "app");
        // Pinned in EVERY map (valid and invalid) so the proof is independent of ambient IBM_MQ_PASSWORD.
        props.put("ibm-mq.password", "passw0rd");
        props.put("ibm-mq.tls-enabled", false);
        props.put("ibm-mq.ssl-cipher-suite", "");
        return props;
    }

    /** validBase() with one entry overridden — keeps every OTHER field valid so only one rule can fire. */
    private static Map<String, Object> validBaseWith(String key, Object value) {
        Map<String, Object> props = validBase();
        props.put(key, value);
        return props;
    }

    /**
     * Asserts that booting a context with {@code badProps} refuses to start and that the failure chain
     * mentions {@code fieldToken}. Closes the context if (unexpectedly) it did start.
     */
    private static void assertRefusesToBoot(Map<String, Object> badProps, String fieldToken) {
        Throwable thrown = catchThrowable(() -> {
            try (ApplicationContext ctx = ApplicationContext.run(badProps)) {
                // If the bean validated, the context started — that is the failure this test guards against.
                assertThat(ctx.isRunning()).isTrue();
            }
        });

        assertThat(thrown)
                .as("a context with an invalid ibm-mq config must REFUSE TO BOOT")
                .isNotNull();
        assertThat(throwableChainMessage(thrown))
                .as("the boot failure must mention the offending field token '%s'", fieldToken)
                .contains(fieldToken);
    }

    /** Concatenates every message in the throwable's cause chain (root causes carry the field token). */
    private static String throwableChainMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable cur = t; cur != null && cur != cur.getCause(); cur = cur.getCause()) {
            if (cur.getMessage() != null) {
                sb.append(cur.getMessage()).append('\n');
            }
        }
        return sb.toString();
    }

    @Nested
    @DisplayName("Configs INVALIDAS recusam o boot (uma violacao isolada por caso)")
    class InvalidConfigsRefuseToBoot {

        @Test
        @DisplayName("port fora de faixa (0) -> NAO sobe (mensagem cita port)")
        void portOutOfRangeRefusesToBoot() {
            assertRefusesToBoot(validBaseWith("ibm-mq.port", 0), "port");
        }

        @Test
        @DisplayName("port fora de faixa (70000) -> NAO sobe (mensagem cita port)")
        void portTooHighRefusesToBoot() {
            assertRefusesToBoot(validBaseWith("ibm-mq.port", 70000), "port");
        }

        @Test
        @DisplayName("channel em branco -> NAO sobe (mensagem cita channel)")
        void blankChannelRefusesToBoot() {
            assertRefusesToBoot(validBaseWith("ibm-mq.channel", ""), "channel");
        }

        @Test
        @DisplayName("queueManager em branco -> NAO sobe (mensagem cita queue-manager/queueManager)")
        void blankQueueManagerRefusesToBoot() {
            assertRefusesToBoot(validBaseWith("ibm-mq.queue-manager", ""), "queue");
        }

        @Test
        @DisplayName("user setado + password em branco -> NAO sobe (mensagem cita password)")
        void userWithBlankPasswordRefusesToBoot() {
            assertRefusesToBoot(validBaseWith("ibm-mq.password", ""), "password");
        }

        @Test
        @DisplayName("tls on + cipher em branco -> NAO sobe (mensagem cita cipher)")
        void tlsOnBlankCipherRefusesToBoot() {
            Map<String, Object> props = validBase();
            props.put("ibm-mq.tls-enabled", true);
            props.put("ibm-mq.ssl-cipher-suite", "");
            assertRefusesToBoot(props, "cipher");
        }

        @Test
        @DisplayName("tls on + cipher TLS_RSA_* -> NAO sobe (mensagem cita TLS_RSA_)")
        void tlsOnTlsRsaCipherRefusesToBoot() {
            Map<String, Object> props = validBase();
            props.put("ibm-mq.tls-enabled", true);
            props.put("ibm-mq.ssl-cipher-suite", "TLS_RSA_WITH_AES_128_CBC_SHA");
            assertRefusesToBoot(props, "TLS_RSA_");
        }
    }

    @Nested
    @DisplayName("Config VALIDA sobe e fecha limpa")
    class ValidConfigBoots {

        @Test
        @DisplayName("config valida (user+password, sem TLS) -> sobe e fecha sem lancar")
        void validConfigBootsCleanly() {
            assertThatCode(() -> {
                try (ApplicationContext ctx = ApplicationContext.run(validBase())) {
                    assertThat(ctx.isRunning())
                            .as("a valid ibm-mq config must boot the context cleanly")
                            .isTrue();
                    assertThat(ctx.getBean(MqProperties.class).getPort())
                            .as("the validated MqProperties bean is available and carries the pinned port")
                            .isEqualTo(1414);
                }
            }).as("a valid config must boot and close without throwing").doesNotThrowAnyException();
        }

        @Test
        @DisplayName("config valida no-auth (user em branco) -> sobe (regra de credencial nao dispara)")
        void noAuthConfigBootsCleanly() {
            Map<String, Object> props = validBase();
            props.put("ibm-mq.user", "");
            props.put("ibm-mq.password", "");
            assertThatCode(() -> {
                try (ApplicationContext ctx = ApplicationContext.run(props)) {
                    assertThat(ctx.isRunning())
                            .as("a no-auth config (blank user) must boot — the credential rule does not fire")
                            .isTrue();
                }
            }).as("a no-auth config must boot and close without throwing").doesNotThrowAnyException();
        }
    }
}
