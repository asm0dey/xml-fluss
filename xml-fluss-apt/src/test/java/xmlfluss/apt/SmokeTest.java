package xmlfluss.apt;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;

import javax.tools.*;
import java.io.StringWriter;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SmokeTest {

    @Test
    void discoversXmlRecord() {
        JavaFileObject file = createSampleDocFile();

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null);
        StringWriter diagnostics = new StringWriter();
        List<String> options = List.of(
            "-classpath", System.getProperty("java.class.path"),
            "-processor", "xmlfluss.apt.XmlDslProcessor",
            "-proc:only"
        );
        boolean ok = compiler.getTask(diagnostics, fm, null, options, null, List.of(file)).call();
        assertTrue(ok, "compilation failed:\n" + diagnostics);
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
