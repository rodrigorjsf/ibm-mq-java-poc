package com.example.ibmmq.demo;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste unitario (surefire, SEM broker, SEM Docker) do GATING do runner da demo COA/COD quando o flag
 * esta AUSENTE ou diferente de {@code true} — prova direta da AC#3: o startup normal da aplicacao NAO
 * e afetado quando o flag nao esta presente.
 *
 * <p>Sobe um {@code ApplicationContext} de verdade SEM a propriedade {@code demo.coa-cod.enabled}
 * (e, num segundo caso, com ela em {@code false}). Como {@link CoaCodDemoRunner} e anotado com
 * {@code @Requires(property = "demo.coa-cod.enabled", value = "true")}, o bean NAO e instanciado —
 * {@code containsBean} retorna {@code false}. Nada toca o broker: o runner e o unico
 * {@code ApplicationEventListener<StartupEvent>} do contexto e os beans JMS sao lazy, entao um contexto
 * sem o flag nao abre nenhuma conexao MQ.</p>
 */
@DisplayName("Gating da demo COA/COD (AC#3): sem o flag, o runner NAO existe e o startup nao e afetado")
class CoaCodDemoRunnerGatingTest {

    @Test
    @DisplayName("Flag ausente: containsBean(CoaCodDemoRunner) == false")
    void runnerAbsentWhenFlagMissing() {
        try (ApplicationContext ctx = ApplicationContext.run()) {
            assertThat(ctx.containsBean(CoaCodDemoRunner.class))
                    .as("runner deve estar AUSENTE sem o flag demo.coa-cod.enabled")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("Flag em false: containsBean(CoaCodDemoRunner) == false (valor deve ser exatamente true)")
    void runnerAbsentWhenFlagFalse() {
        // Overload de PROPRIEDADES: run(Map<String,Object>) injeta o par chave/valor como property
        // (diferente de run(String...), que interpretaria os argumentos como NOMES de environment).
        // Assim testamos de fato o flag PRESENTE porem != "true": @Requires(value="true") reprova o bean.
        try (ApplicationContext ctx = ApplicationContext.run(Map.of("demo.coa-cod.enabled", "false"))) {
            assertThat(ctx.containsBean(CoaCodDemoRunner.class))
                    .as("runner deve estar AUSENTE quando o flag != true")
                    .isFalse();
        }
    }
}
