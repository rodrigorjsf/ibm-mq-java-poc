package com.example.ibmmq.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MdcTraceScope}: the single contract for the "Log trace context (MDC)" concept.
 *
 * <p>Asserts the two load-bearing guarantees: {@code bind()} puts both keys into the MDC while the scope
 * is open, and {@code close()} (via try-with-resources) clears both — so a reused carrier/virtual thread
 * cannot leak one message's ids onto the next. Also locks the key literals to their logback contract
 * values ({@code "messageId"} / {@code "correlationId"}) at the source, since drifting them silently
 * drops the ids from every log line.</p>
 */
class MdcTraceScopeTest {

    @AfterEach
    void clearMdc() {
        // Defensive: ensure no MDC entry leaks to the next test on this thread, even if an assertion
        // fails inside an open scope before close() runs.
        MDC.clear();
    }

    @Test
    @DisplayName("as constantes carregam os literais exatos do contrato com o logback.xml")
    void constantsHoldTheLogbackContractLiterals() {
        // The keys MUST match logback.xml's %X{messageId} / %X{correlationId} exactly, or every log line
        // silently loses its ids. Lock the literals at the source.
        assertThat(MdcTraceScope.MESSAGE_ID).isEqualTo("messageId");
        assertThat(MdcTraceScope.CORRELATION_ID).isEqualTo("correlationId");
    }

    @Test
    @DisplayName("bind vincula messageId e correlationId no MDC enquanto o escopo esta aberto")
    void bindPutsBothKeysWhileScopeIsOpen() {
        try (var scope = MdcTraceScope.bind("MSG-1", "CORR-1")) {
            assertThat(scope).isNotNull();
            assertThat(MDC.get(MdcTraceScope.MESSAGE_ID)).isEqualTo("MSG-1");
            assertThat(MDC.get(MdcTraceScope.CORRELATION_ID)).isEqualTo("CORR-1");
        }
    }

    @Test
    @DisplayName("bind preserva a assimetria: messageId e correlationId podem diferir")
    void bindPreservesAsymmetricValues() {
        // The report consumer binds two DIFFERENT values (original messageId vs report correlationId);
        // guard the arg order so a future swap/collapse is caught here (LoggingFlowTest cannot, because
        // both values coincide under MQRO_COPY_MSG_ID_TO_CORREL_ID in its scenarios).
        try (var ignored = MdcTraceScope.bind("ORIGINAL-MSG-ID", "REPORT-CORREL-ID")) {
            assertThat(MDC.get(MdcTraceScope.MESSAGE_ID)).isEqualTo("ORIGINAL-MSG-ID");
            assertThat(MDC.get(MdcTraceScope.CORRELATION_ID)).isEqualTo("REPORT-CORREL-ID");
        }
    }

    @Test
    @DisplayName("close (try-with-resources) limpa ambas as chaves do MDC ao final do bloco")
    void closeClearsBothKeysAfterTheBlock() {
        try (var ignored = MdcTraceScope.bind("MSG-2", "CORR-2")) {
            // ids bound here
            assertThat(MDC.get(MdcTraceScope.MESSAGE_ID)).isEqualTo("MSG-2");
        }
        // After the try-with-resources closes, the thread-local must be clean.
        assertThat(MDC.get(MdcTraceScope.MESSAGE_ID)).isNull();
        assertThat(MDC.get(MdcTraceScope.CORRELATION_ID)).isNull();
    }

    @Test
    @DisplayName("close limpa o MDC mesmo quando uma excecao escapa do bloco try-with-resources")
    void closeClearsMdcEvenWhenBlockThrows() {
        // The leak we are defending against happens on the exceptional path: close() must still run.
        try (var ignored = MdcTraceScope.bind("MSG-3", "CORR-3")) {
            throw new RuntimeException("boom");
        } catch (RuntimeException expected) {
            // swallowed: we only care that close() ran on the way out
        }
        assertThat(MDC.get(MdcTraceScope.MESSAGE_ID)).isNull();
        assertThat(MDC.get(MdcTraceScope.CORRELATION_ID)).isNull();
    }
}
