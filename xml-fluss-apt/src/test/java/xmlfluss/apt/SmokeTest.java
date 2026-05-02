package xmlfluss.apt;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

import javax.tools.*;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SmokeTest {

    @Test
    void discoversXmlRecord() {
        JavaFileObject file = createSampleDocFile();

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null);
        StringWriter diagnostics = new StringWriter();
        Path out;
        try {
            out = Files.createTempDirectory("xmlfluss-apt-smoke-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            List<String> options = List.of(
                "-classpath", System.getProperty("java.class.path"),
                "-processor", "xmlfluss.apt.XmlDslProcessor",
                "-proc:only",
                "-d", out.toString(),
                "-s", out.toString()
            );
            boolean ok = compiler.getTask(diagnostics, fm, null, options, null, List.of(file)).call();
            assertTrue(ok, "compilation failed:\n" + diagnostics);
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

    private static @NonNull JavaFileObject createSampleDocFile() {
        String source = """
            package sample;
            import xmlfluss.XmlRecord;
            @XmlRecord(path = "//doc")
            public record Doc(String body) {}
            """;
        return new SimpleJavaFileObject(
            URI.create("string:///sample/Doc.java"),
            JavaFileObject.Kind.SOURCE
        ) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
    }
}
