package com.example.ibmmq;

import io.micronaut.runtime.Micronaut;

/**
 * Ponto de entrada da aplicacao Micronaut.
 *
 * <p>O Micronaut e usado APENAS para injecao de dependencia, {@code @ConfigurationProperties},
 * {@code @Factory} e ciclo de vida de beans. O ciclo JMS (JMSContext, producer, consumers) e
 * gerenciado manualmente — nao usamos {@code io.micronaut.jms} (que e jakarta-only).</p>
 */
public final class Application {

    private Application() {
        // Classe utilitária: não deve ser instanciada.
    }

    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}
