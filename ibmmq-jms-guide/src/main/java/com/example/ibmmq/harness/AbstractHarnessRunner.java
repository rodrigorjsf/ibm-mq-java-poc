package com.example.ibmmq.harness;

import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base dos runners do harness: gerencia uma thread de trabalho <b>nao-daemon</b> que roda um laco
 * {@code while(running)} acionando a logica de negocio EXISTENTE (producer/consumer/report) sem
 * altera-la.
 *
 * <p><b>Por que uma thread nao-daemon (e nao {@code @Scheduled}):</b> a aplicacao nao expoe um
 * servidor HTTP, entao nao ha {@code EmbeddedApplication} para manter a JVM viva — {@code Micronaut.run}
 * retorna e o processo encerraria (CrashLoop nos pods). Uma thread nao-daemon ({@code setDaemon(false)})
 * segura a JVM ativa enquanto o laco roda; o pool de {@code @Scheduled} e daemon e nao serviria.</p>
 *
 * <p><b>Modelo de concorrencia (AC1):</b> o paralelismo de consumo vem das <b>N replicas</b> de pods
 * (competing consumers), nao de multiplas threads por pod. Por isso o laco por pod e
 * <b>single-thread</b> — uma replica = um consumidor concorrente.</p>
 *
 * <p>Dispara no {@link StartupEvent} (apos o contexto estar pronto, com todos os beans injetados) e
 * para de forma limpa no {@link PreDestroy} (shutdown do pod / SIGTERM do k8s).</p>
 */
abstract class AbstractHarnessRunner implements ApplicationEventListener<StartupEvent> {

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread worker;

    /** Nome legivel do papel (para o nome da thread e logs). */
    protected abstract String roleName();

    /** Logger da subclasse concreta (para que as linhas saiam sob o logger do runner real). */
    protected abstract Logger log();

    /** Uma iteracao do laco: aciona a logica de negocio existente uma vez. Nao deve travar para sempre. */
    protected abstract void runOnce() throws Exception;

    /** True enquanto o laco deve continuar (consultado pelas subclasses, se necessario). */
    protected boolean isRunning() {
        return running.get();
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        if (!running.compareAndSet(false, true)) {
            return; // ja iniciado (idempotente)
        }
        worker = new Thread(this::loop, "harness-" + roleName());
        // NAO-daemon: segura a JVM viva sem servidor HTTP (ver Javadoc da classe).
        worker.setDaemon(false);
        worker.start();
        log().info("[stage=HARNESS-START] Runner '{}' iniciado (thread nao-daemon: {})",
                roleName(), worker.getName());
    }

    private void loop() {
        log().info("[stage=HARNESS-LOOP] Laco do runner '{}' em execucao", roleName());
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                runOnce();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Resiliencia: uma falha transitoria (ex. reconexao MQ) nao deve matar o laco.
                // Loga e continua; sob ~10k rpm o cliente MQ reconecta sozinho (WMQ_CLIENT_RECONNECT).
                log().warn("[stage=HARNESS-ERROR] Falha em uma iteracao do runner '{}'; continuando",
                        roleName(), e);
                pauseAfterError();
            }
        }
        log().info("[stage=HARNESS-STOP] Laco do runner '{}' encerrado", roleName());
    }

    /** Pequena pausa apos erro para nao entrar em busy-loop quando o broker esta indisponivel. */
    private void pauseAfterError() {
        try {
            Thread.sleep(1_000L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            running.set(false);
        }
    }

    @PreDestroy
    void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        log().info("[stage=HARNESS-SHUTDOWN] Parando runner '{}' (SIGTERM/shutdown)", roleName());
        Thread w = worker;
        if (w != null) {
            w.interrupt();
            try {
                // Espera o laco terminar a iteracao corrente para um shutdown limpo.
                w.join(10_000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
