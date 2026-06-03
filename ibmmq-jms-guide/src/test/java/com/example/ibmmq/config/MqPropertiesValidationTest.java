package com.example.ibmmq.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure-unit test (surefire, NO broker, NO context) of the fail-fast cross-field and structural rules of
 * {@link MqProperties#validateCrossFieldConstraints()} (issue #27 / ADR-0011).
 *
 * <p>Calls {@code validateCrossFieldConstraints()} directly on a {@code new MqProperties()}. A
 * {@code new MqProperties()} is NOT container-managed, so its {@code @PostConstruct} never fires on its own
 * — invoking the public method exercises exactly the boot-time check without paying for an
 * {@code ApplicationContext}. The context-start counterpart that proves the same rules refuse to BOOT lives
 * in {@link MqPropertiesStartupValidationTest}; the two must agree because they assert the same method.</p>
 */
@DisplayName("MqProperties — validacao fail-fast de campos cruzados e estruturais (issue #27 / ADR-0011)")
class MqPropertiesValidationTest {

    /** A fully valid, no-TLS, authenticated instance — the baseline each nest mutates one field of. */
    private static MqProperties validProps() {
        MqProperties props = new MqProperties();
        props.setHost("localhost");
        props.setPort(1414);
        props.setChannel("DEV.APP.SVRCONN");
        props.setQueueManager("QM1");
        props.setUser("app");
        props.setPassword("passw0rd");
        props.setTlsEnabled(false);
        props.setSslCipherSuite("");
        return props;
    }

    @Nested
    @DisplayName("Regra de credencial: user nao-branco exige password")
    class UserRequiresPassword {

        @Test
        @DisplayName("user=app + password em branco -> rejeita (mensagem cita user e password)")
        void userWithBlankPasswordRejected() {
            MqProperties props = validProps();
            props.setPassword("");

            assertThatThrownBy(props::validateCrossFieldConstraints)
                    .as("a non-blank user with a blank password must be rejected")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("password")
                    .hasMessageContaining("user");
        }

        @Test
        @DisplayName("user=app + password null -> rejeita")
        void userWithNullPasswordRejected() {
            MqProperties props = validProps();
            props.setPassword(null);

            assertThatThrownBy(props::validateCrossFieldConstraints)
                    .as("a non-blank user with a null password must be rejected")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("password");
        }

        @Test
        @DisplayName("user em branco + password em branco -> OK (contrato no-auth)")
        void blankUserBlankPasswordIsNoAuth() {
            MqProperties props = validProps();
            props.setUser("");
            props.setPassword("");

            assertThatCode(props::validateCrossFieldConstraints)
                    .as("a blank user is the no-auth contract and must NOT trip the credential rule")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("user=app + password preenchido -> OK")
        void userWithPasswordOk() {
            MqProperties props = validProps();
            props.setUser("app");
            props.setPassword("passw0rd");

            assertThatCode(props::validateCrossFieldConstraints)
                    .as("a non-blank user with a non-blank password is valid")
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Regra de TLS: tls ligado exige cipher valido (nao-branco, nao TLS_RSA_*)")
    class TlsRequiresValidCipher {

        @Test
        @DisplayName("tls on + cipher em branco -> rejeita (mensagem cita cipher e tls)")
        void tlsOnBlankCipherRejected() {
            MqProperties props = validProps();
            props.setTlsEnabled(true);
            props.setSslCipherSuite("");

            assertThatThrownBy(props::validateCrossFieldConstraints)
                    .as("TLS enabled with a blank cipher suite must be rejected")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cipher")
                    .hasMessageContaining("tls");
        }

        @Test
        @DisplayName("tls on + TLS_RSA_WITH_AES_128_CBC_SHA -> rejeita (mensagem cita TLS_RSA_ e Java 25)")
        void tlsOnTlsRsaCipherRejected() {
            MqProperties props = validProps();
            props.setTlsEnabled(true);
            props.setSslCipherSuite("TLS_RSA_WITH_AES_128_CBC_SHA");

            assertThatThrownBy(props::validateCrossFieldConstraints)
                    .as("a TLS_RSA_* cipher suite is disabled from Java 25 (ADR-0001) and must be rejected")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("TLS_RSA_")
                    .hasMessageContaining("Java 25");
        }

        @Test
        @DisplayName("tls on + TLS_AES_256_GCM_SHA384 -> OK")
        void tlsOnModernCipherOk() {
            MqProperties props = validProps();
            props.setTlsEnabled(true);
            props.setSslCipherSuite("TLS_AES_256_GCM_SHA384");

            assertThatCode(props::validateCrossFieldConstraints)
                    .as("a modern TLS 1.3 cipher suite is valid when TLS is enabled")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("tls off + cipher em branco -> OK (cipher ignorado)")
        void tlsOffBlankCipherIgnored() {
            MqProperties props = validProps();
            props.setTlsEnabled(false);
            props.setSslCipherSuite("");

            assertThatCode(props::validateCrossFieldConstraints)
                    .as("the cipher field is ignored when TLS is off")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("tls off + TLS_RSA_* -> OK (ignorado, regra so vale com tls on)")
        void tlsOffTlsRsaIgnored() {
            MqProperties props = validProps();
            props.setTlsEnabled(false);
            props.setSslCipherSuite("TLS_RSA_WITH_AES_128_CBC_SHA");

            assertThatCode(props::validateCrossFieldConstraints)
                    .as("even a TLS_RSA_* cipher is ignored when TLS is off")
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Restricoes estruturais (mesmo @PostConstruct, para o direct-call e o context-start concordarem)")
    class StructuralConstraints {

        @Test
        @DisplayName("port=0 -> rejeita (mensagem cita port)")
        void portZeroRejected() {
            MqProperties props = validProps();
            props.setPort(0);

            assertThatThrownBy(props::validateCrossFieldConstraints)
                    .as("port 0 is below the valid range [1, 65535]")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("port");
        }

        @Test
        @DisplayName("port=65536 -> rejeita (mensagem cita port)")
        void portTooHighRejected() {
            MqProperties props = validProps();
            props.setPort(65536);

            assertThatThrownBy(props::validateCrossFieldConstraints)
                    .as("port 65536 is above the valid range [1, 65535]")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("port");
        }

        @Test
        @DisplayName("port=1414 -> OK")
        void portInRangeOk() {
            MqProperties props = validProps();
            props.setPort(1414);

            assertThatCode(props::validateCrossFieldConstraints)
                    .as("port 1414 is inside the valid range")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("channel em branco -> rejeita (mensagem cita channel)")
        void blankChannelRejected() {
            MqProperties props = validProps();
            props.setChannel("");

            assertThatThrownBy(props::validateCrossFieldConstraints)
                    .as("a blank channel must be rejected")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("channel");
        }

        @Test
        @DisplayName("queueManager em branco -> rejeita (mensagem cita queueManager)")
        void blankQueueManagerRejected() {
            MqProperties props = validProps();
            props.setQueueManager("");

            assertThatThrownBy(props::validateCrossFieldConstraints)
                    .as("a blank queueManager must be rejected")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("queueManager");
        }

        @Test
        @DisplayName("instancia totalmente valida -> OK")
        void fullyValidInstanceOk() {
            assertThatCode(() -> validProps().validateCrossFieldConstraints())
                    .as("a fully valid instance passes every structural and cross-field rule")
                    .doesNotThrowAnyException();
            assertThat(validProps().getPort())
                    .as("baseline sanity: validProps() really is in range")
                    .isBetween(1, 65535);
        }
    }
}
