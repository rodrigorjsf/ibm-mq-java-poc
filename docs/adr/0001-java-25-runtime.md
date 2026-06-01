# 0001 — Production runtime is Java 25 (supersedes brief P1's Java 21)

**Status:** accepted (2026-05-31) — supersedes premise **P1** of the locked brief `research-prompt-ibmmq-jms-micronaut.md`.

## Decision

The production runtime for this stack is **Java 25** (Amazon Corretto 25, an LTS). `ibmmq-jms-guide/pom.xml` sets `maven.compiler.release=25`, so the emitted bytecode cannot run on Java 21 — Java 25 is the actual, in-use target. This deliberately supersedes brief premise **P1 ("Runtime: Java 21")**. The brief itself is left untouched as the historical record; this ADR is where the change of mind is recorded.

## Why (the trade-off)

- **Java 21 (rejected):** an LTS, and the version the locked brief originally fixed; the IBM Semeru JRE *bundled* with MQ 9.4.5 is Semeru 21. But it is not the version actually being built/run here.
- **Java 25 (chosen):** also an LTS; **documented and in-scope for IBM MQ 9.4.x** (validated in `research-output/phase-a-fact-sheet.md` — the "Java 25 not listed" fear was refuted, and the `SecurityManager`/JEP 486 breakage fear was refuted). It is the version actually in use. Crucially, **JEP 491 (final in JDK 24, present in 25) removes virtual-thread pinning on `synchronized` blocks**, which materially improves the concurrency story for the IBM MQ client — whose internal `synchronized` blocks were the classic VT-pinning hazard on Java 21.

## Consequences

- Run the client with `--enable-native-access=ALL-UNNAMED` (native-access warning on `System.loadLibrary`) and **avoid `TLS_RSA_*` CipherSpecs** (disabled from Java 25).
- The IBM MQ client remains certified against the homogeneous 9.4 client↔QMgr pair; the bundled Semeru is 21, but the client runs on Java 25.
- The guide's Virtual Threads analysis (§5.7) is reframed for JEP 491: the `synchronized`-block pinning hazard is gone; residual pinning is only on native/JNI frames (measure it with `-Djdk.tracePinnedThreads=full` / JFR `jdk.VirtualThreadPinned`). `Session`/`JMSContext` remain not thread-safe regardless.
- Future "why Java 25 when the brief says 21?" questions are answered here.
