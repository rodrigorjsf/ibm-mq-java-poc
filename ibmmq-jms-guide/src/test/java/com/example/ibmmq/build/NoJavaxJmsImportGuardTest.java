package com.example.ibmmq.build;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Build guard (issue #84, AC10 — Jakarta Messaging migration). Fails the build if any production or test
 * source reintroduces a {@code javax.jms} import after the namespace migration to {@code jakarta.jms}
 * (Jakarta Messaging 3.0, ADR-0012).
 *
 * <p>Dependency-free guard: this is a plain JUnit unit test (surefire, no broker, no Docker, no new build
 * plugin). It walks {@code src/main/java} and {@code src/test/java} and asserts that no source line is an
 * {@code import} of the legacy {@code javax.jms} namespace. It is exercised on every {@code mvn test} /
 * {@code mvn verify}, so a stray {@code import javax.jms.*} (or {@code import static javax.jms.*}) fails the
 * gate.</p>
 *
 * <p><b>Why target the import statement, not the bare string.</b> The detector matches only lines that, once
 * trimmed, START with {@code "import "} AND contain {@code "javax.jms"}. This is deliberate: documentation and
 * migration-reference comments legitimately mention {@code javax.jms} (e.g. "migrated javax.jms -> jakarta.jms"),
 * and this guard must not false-positive on prose. Comment/JavaDoc/string tokens never begin with {@code import },
 * so they are ignored. This also means the guard never flags ITSELF — its own {@code "javax.jms"} literals live in
 * comments and string constants, none of which start with {@code import }.</p>
 */
@DisplayName("Build guard: nenhum import javax.jms reintroduzido apos a migracao para jakarta.jms (#84, AC10)")
class NoJavaxJmsImportGuardTest {

    /** The legacy namespace fragment that must not appear in any import statement. */
    private static final String LEGACY_NAMESPACE = "javax.jms";

    /**
     * Returns true iff {@code line} is an import statement for the legacy {@code javax.jms} namespace.
     * Matches both {@code import javax.jms.Foo;} and {@code import static javax.jms.Foo.BAR;}. A line is an
     * import only when its trimmed form starts with {@code "import "} — so comments, JavaDoc, and string
     * literals that merely mention {@code javax.jms} are not flagged.
     */
    static boolean isLegacyJmsImport(String line) {
        String trimmed = line.strip();
        return trimmed.startsWith("import ") && trimmed.contains(LEGACY_NAMESPACE);
    }

    @Test
    @DisplayName("O detector dispara num import javax.jms e ignora comentarios/strings que apenas mencionam o token")
    void detectorFiresOnImportAndIgnoresProse() {
        // Positive: real import lines (must FIRE) — the guard's load-bearing property.
        assertThat(isLegacyJmsImport("import javax.jms.Message;")).isTrue();
        assertThat(isLegacyJmsImport("import javax.jms.ConnectionFactory;")).isTrue();
        assertThat(isLegacyJmsImport("    import javax.jms.JMSContext;")).isTrue();
        assertThat(isLegacyJmsImport("import static javax.jms.JMSContext.AUTO_ACKNOWLEDGE;")).isTrue();

        // Negative: prose/strings mentioning javax.jms (must NOT fire) — no false positive on migration refs.
        assertThat(isLegacyJmsImport("// migrated javax.jms -> jakarta.jms (ADR-0012)")).isFalse();
        assertThat(isLegacyJmsImport(" * accessors (no {@code javax.jms} re-import).")).isFalse();
        assertThat(isLegacyJmsImport("private static final String LEGACY = \"javax.jms\";")).isFalse();
        assertThat(isLegacyJmsImport("import jakarta.jms.Message;")).isFalse();
    }

    @Test
    @DisplayName("Nenhuma fonte (main + test) importa javax.jms — a arvore atual esta limpa")
    void noSourceImportsLegacyJavaxJms() {
        Path moduleRoot = locateModuleRoot();
        List<String> offenders = new ArrayList<>();
        int scannedFiles = 0;
        for (String relative : List.of("src/main/java", "src/test/java")) {
            Path sourceRoot = moduleRoot.resolve(relative);
            if (!Files.isDirectory(sourceRoot)) {
                continue;
            }
            scannedFiles += collectOffenders(sourceRoot, offenders);
        }
        // Guard against a SILENT no-op: if the module root could not be located the scan would resolve no
        // files and the guard would pass vacuously. Assert it actually walked the real tree (this very test
        // file lives under src/test/java, so the count is always >= 1 when the source roots resolve).
        assertThat(scannedFiles)
                .as("the guard must actually scan the source tree (located module root: %s)", moduleRoot)
                .isPositive();
        assertThat(offenders)
                .as("nenhum arquivo .java deve importar o namespace legado javax.jms (migracao jakarta.jms, #84)")
                .isEmpty();
    }

    /**
     * Walks {@code sourceRoot} for {@code *.java} files, appends "{path}:{line}: {content}" for offenders, and
     * returns the number of {@code .java} files scanned (used to detect a silent no-op).
     */
    private static int collectOffenders(Path sourceRoot, List<String> offenders) {
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            List<Path> javaFiles = files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
            javaFiles.forEach(javaFile -> scanFile(javaFile, offenders));
            return javaFiles.size();
        } catch (IOException e) {
            throw new UncheckedIOException("could not walk source root " + sourceRoot, e);
        }
    }

    /** Reads {@code javaFile} line-by-line and records any legacy {@code javax.jms} import as an offender. */
    private static void scanFile(Path javaFile, List<String> offenders) {
        try {
            List<String> lines = Files.readAllLines(javaFile);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (isLegacyJmsImport(line)) {
                    offenders.add(javaFile + ":" + (i + 1) + ": " + line.strip());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read source file " + javaFile, e);
        }
    }

    /**
     * Resolves the module root (the directory holding {@code src/main/java}). Surefire runs with the module
     * directory as the working directory, so {@code user.dir} normally IS the module root; but to be robust to
     * the runner's CWD we walk up from {@code user.dir} until a {@code src/main/java} is found. Falls back to
     * {@code user.dir} when none is located (in which case the source roots simply do not exist and the scan is
     * a no-op rather than a false failure).
     */
    private static Path locateModuleRoot() {
        Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("src/main/java"))) {
                return candidate;
            }
        }
        return start;
    }
}
