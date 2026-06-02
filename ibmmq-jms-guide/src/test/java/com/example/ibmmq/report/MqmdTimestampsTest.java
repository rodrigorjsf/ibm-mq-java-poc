package com.example.ibmmq.report;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste unitario (sem broker) da conversao GMT do {@code PutDate}+{@code PutTime} do MQMD para um
 * {@link LocalDateTime} relogio-de-parede UTC — issue #19, AC3 (sem vazamento da zona default da JVM).
 *
 * <p>Formato (verificado em {@code research-output/phase-f-mqmd-field-recovery.md}): {@code PutDate} =
 * {@code YYYYMMDD}, {@code PutTime} = {@code HHMMSSTH} (os dois ultimos digitos sao CENTESIMOS de
 * segundo). Ambos GMT/UTC.</p>
 */
class MqmdTimestampsTest {

    private TimeZone originalDefault;

    @AfterEach
    void restoreDefaultZone() {
        // Restaura a zona default da JVM caso um teste a tenha trocado para provar a ausencia de vazamento.
        if (originalDefault != null) {
            TimeZone.setDefault(originalDefault);
            originalDefault = null;
        }
    }

    @ParameterizedTest(name = "PutDate={0} PutTime={1} -> {2}")
    @DisplayName("Converte pares GMT PutDate/PutTime para o relogio-de-parede UTC exato (centesimos)")
    @CsvSource({
            // Caso base: meio-dia e meio, com 500 ms (TH=50 centesimos).
            "20260531, 13300050, 2026-05-31T13:30:00.500",
            // Centesimos zero.
            "20260101, 00000000, 2026-01-01T00:00:00",
            // Centesimos = 09 (90 ms).
            "20251231, 23595909, 2025-12-31T23:59:59.090",
            // Segundo final do dia com 990 ms.
            "20240229, 23595999, 2024-02-29T23:59:59.990"
    })
    void parsesGmtPutDateTimeToUtcWallClock(String putDate, String putTime, String expectedIso) {
        LocalDateTime parsed = MqmdTimestamps.parse(putDate, putTime);
        assertThat(parsed)
                .as("PutDate=%s PutTime=%s deve render o relogio UTC %s", putDate, putTime, expectedIso)
                .isEqualTo(LocalDateTime.parse(expectedIso));
    }

    @Test
    @DisplayName("Sem vazamento da zona default: a fronteira de meia-noite e identica em qualquer TimeZone")
    void noJvmDefaultZoneLeakageAtDayBoundary() {
        // PutDate/PutTime = 2026-05-31 00:00:00.00 UTC (uma fronteira de dia/meia-noite). Se o parser
        // vazasse para a zona default da JVM, o instante resultante (apos toInstantUtc) variaria com a
        // zona; provamos que NAO varia, rodando o mesmo parse sob duas zonas opostas a UTC.
        TimeZone[] zones = {
                TimeZone.getTimeZone("America/Sao_Paulo"), // UTC-3
                TimeZone.getTimeZone("Asia/Tokyo")          // UTC+9
        };

        LocalDateTime expectedWallClock = LocalDateTime.parse("2026-05-31T00:00:00");
        Instant expectedInstant = expectedWallClock.toInstant(ZoneOffset.UTC);

        originalDefault = TimeZone.getDefault();
        for (TimeZone zone : zones) {
            TimeZone.setDefault(zone);

            LocalDateTime wallClock = MqmdTimestamps.parse("20260531", "00000000");
            assertThat(wallClock)
                    .as("o relogio-de-parede UTC e invariante a zona default da JVM (%s)", zone.getID())
                    .isEqualTo(expectedWallClock);

            Instant instant = MqmdTimestamps.toInstantUtc(wallClock);
            assertThat(instant)
                    .as("o Instant deriva de ZoneOffset.UTC explicito, invariante a zona default (%s)", zone.getID())
                    .isEqualTo(expectedInstant);
        }
    }

    @Test
    @DisplayName("Entradas nulas/vazias/malformadas retornam null sem lancar (caminho ja-ackado seguro)")
    void nullBlankAndMalformedAreNullSafe() {
        assertThat(MqmdTimestamps.parse(null, "13300050")).isNull();
        assertThat(MqmdTimestamps.parse("20260531", null)).isNull();
        assertThat(MqmdTimestamps.parse(null, null)).isNull();
        assertThat(MqmdTimestamps.parse("", "")).isNull();
        assertThat(MqmdTimestamps.parse("   ", "   ")).isNull();
        // Comprimento errado (7 chars) -> null, sem lancar.
        assertThat(MqmdTimestamps.parse("2026053", "13300050")).isNull();
        // Nao-numerico -> null, sem lancar.
        assertThat(MqmdTimestamps.parse("YYYYMMDD", "HHMMSSTH")).isNull();
        // toInstantUtc tolera null.
        assertThat(MqmdTimestamps.toInstantUtc(null)).isNull();
    }
}
