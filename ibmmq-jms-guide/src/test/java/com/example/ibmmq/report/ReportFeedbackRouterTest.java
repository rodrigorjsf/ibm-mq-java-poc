package com.example.ibmmq.report;

import com.example.ibmmq.model.ReportType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Teste unitario (sem broker) do mapeamento codigo-de-feedback -> {@link ReportType}.
 *
 * <p>Usa os valores inteiros EXATOS dos constants {@code MQFB_*} (verificados no bytecode de
 * {@code CMQC} 9.4.5.0): COA=259, COD=260, EXPIRATION=258, PAN=275, NAN=276.</p>
 */
class ReportFeedbackRouterTest {

    private final ReportFeedbackRouter router = new ReportFeedbackRouter();

    @ParameterizedTest(name = "feedback {0} -> {1}")
    @DisplayName("Mapeia cada MQFB_* para o ReportType correto (valores inteiros exatos)")
    @CsvSource({
            "259, COA",
            "260, COD",
            "258, EXPIRATION",
            "275, PAN",
            "276, NAN"
    })
    void mapsKnownFeedbackCodes(int feedback, ReportType expected) {
        assertEquals(expected, router.classify(feedback));
    }

    @Test
    @DisplayName("Feedback 0 (MQFB_NONE) e tratado como UNKNOWN")
    void mapsNoneToUnknown() {
        assertEquals(ReportType.UNKNOWN, router.classify(0));
    }

    @Test
    @DisplayName("Reason code MQRC_* na faixa do sistema (ex. 2051 MQRC_PUT_INHIBITED) vira EXCEPTION")
    void mapsSystemReasonCodeToException() {
        // 2051 = MQRC_PUT_INHIBITED: esta na faixa MQFB_SYSTEM_FIRST..MQFB_SYSTEM_LAST (1..65535)
        // e nao e um MQFB_* de notificacao conhecido -> relatorio de excecao.
        assertEquals(ReportType.EXCEPTION, router.classify(2051));
        // 2053 = MQRC_Q_FULL.
        assertEquals(ReportType.EXCEPTION, router.classify(2053));
    }

    @Test
    @DisplayName("Feedback fora das faixas conhecidas (faixa de aplicacao) vira UNKNOWN")
    void mapsApplicationRangeToUnknown() {
        // 65536 = MQFB_APPL_FIRST: fora da faixa do sistema.
        assertEquals(ReportType.UNKNOWN, router.classify(65536));
    }

    @Test
    @DisplayName("COA e COD nao colidem com valores adjacentes")
    void coaCodAreDistinct() {
        assertEquals(ReportType.COA, router.classify(259));
        assertEquals(ReportType.COD, router.classify(260));
        // 271 (MQFB_XMIT_Q_MSG_ERROR) NAO deve virar COA — esta na faixa do sistema -> EXCEPTION.
        assertEquals(ReportType.EXCEPTION, router.classify(271));
    }
}
