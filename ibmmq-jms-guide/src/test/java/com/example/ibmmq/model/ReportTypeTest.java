package com.example.ibmmq.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste unitario (sem broker) da projecao {@link ReportType#toDomainChar()} — issue #19, campo #6.
 *
 * <p>Derivacao pura: {@code COA -> 'A'}, {@code COD -> 'D'}, qualquer outro tipo -> sentinela
 * {@link ReportType#DOMAIN_CHAR_OTHER}.</p>
 */
class ReportTypeTest {

    @Test
    @DisplayName("COA projeta para 'A' e COD projeta para 'D'")
    void coaAndCodMapToDomainChars() {
        assertThat(ReportType.COA.toDomainChar()).isEqualTo('A');
        assertThat(ReportType.COD.toDomainChar()).isEqualTo('D');
    }

    @ParameterizedTest(name = "{0} -> sentinela")
    @DisplayName("Tipos nao-COA/COD projetam para a sentinela DOMAIN_CHAR_OTHER")
    @EnumSource(value = ReportType.class, names = {"EXPIRATION", "PAN", "NAN", "EXCEPTION", "UNKNOWN"})
    void nonCoaCodMapToSentinel(ReportType type) {
        assertThat(type.toDomainChar()).isEqualTo(ReportType.DOMAIN_CHAR_OTHER);
    }

    @Test
    @DisplayName("A sentinela e distinta de 'A' e 'D' (nunca colide com uma projecao real)")
    void sentinelIsDistinctFromRealChars() {
        assertThat(ReportType.DOMAIN_CHAR_OTHER).isNotIn('A', 'D');
    }
}
