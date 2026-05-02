package xmlfluss.apt.spi;

import javax.annotation.processing.AbstractProcessor;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared in-process javac harness for SPI unit tests on the APT side.
 *
 * <p>Concrete tests provide their own annotation-trigger {@link AbstractProcessor} (the probe that
 * captures whatever symbol they want to assert against) and a small set of synthetic source files;
 * this harness only owns the wiring to drive {@code javac} in-process against the current
 * {@code java.class.path}.
 *
 * <p>Two flavors are provided:
 * <ul>
 *   <li>{@link #compileWith(List, List)} — annotation-processing only ({@code -proc:only}). Use
 *       when the probe doesn't need real type-checking of the synthetic sources, only their AST
 *       and annotation mirrors. {@link AptSymbolProviderTest} uses this.</li>
 *   <li>{@link #compileWithProcessing(List, List)} — full compile ({@code -proc:full}). Required
 *       when the probe walks resolved supertypes / type arguments via {@code Types}, since those
 *       queries assume types are fully attributed. {@link AptTypeSymbolTest} uses this.</li>
 * </ul>
 */
final class SpiTestHarness {

    private SpiTestHarness() {}

    /** Build an in-memory {@link JavaFileObject} for {@code binaryName} (e.g. {@code "p/Demo"}). */
    static InMemoryJavaSource source(String binaryName, String content) {
        return new InMemoryJavaSource(binaryName, content);
    }

    /**
     * Compile {@code sources} with {@code processors} in {@code -proc:only} mode and assert that
     * javac reports success. Suitable for probes that only need annotation mirrors and the
     * declared shape of the input sources.
     */
    static void compileWith(List<JavaFileObject> sources, List<AbstractProcessor> processors) {
        run(sources, processors, "-proc:only");
    }

    /**
     * Compile {@code sources} with {@code processors} in {@code -proc:full} mode and assert that
     * javac reports success. Required when probes resolve supertypes / type arguments via
     * {@code Types}, since those queries need the inputs to be fully type-checked.
     */
    static void compileWithProcessing(List<JavaFileObject> sources, List<AbstractProcessor> processors) {
        run(sources, processors, "-proc:full");
    }

    private static void run(List<JavaFileObject> sources, List<AbstractProcessor> processors, String procMode) {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        Path out;
        try {
            out = Files.createTempDirectory("xmlfluss-apt-test-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            List<String> options = List.of(
                    "-classpath", System.getProperty("java.class.path"),
                    procMode,
                    "--release", "17",
                    "-d", out.toString(),
                    "-s", out.toString()
            );
            var task = javac.getTask(null, null, null, options, null, sources);
            task.setProcessors(processors);
            boolean ok = task.call();
            assertTrue(ok, "javac should succeed");
        } finally {
            deleteTree(out);
        }
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) return;
        try (var s = Files.walk(root)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    /**
     * In-memory {@link SimpleJavaFileObject} backed by an in-process string. Package-private so
     * tests can refer to it directly when a processor or assertion needs the concrete type.
     */
    static final class InMemoryJavaSource extends SimpleJavaFileObject {
        private final String content;

        InMemoryJavaSource(String binaryName, String content) {
            super(URI.create("string:///" + binaryName.replace('.', '/') + ".java"), Kind.SOURCE);
            this.content = content;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }
    }
}
