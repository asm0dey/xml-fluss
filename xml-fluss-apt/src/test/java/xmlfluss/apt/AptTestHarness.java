package xmlfluss.apt;

import org.jspecify.annotations.NonNull;

import javax.tools.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Test harness for xml-fluss-apt. One instance per test (the harness is single-shot — it
 * compiles a record + processor pass into a private temp directory, then exposes a
 * reflective handle to the resulting {@code ${RecordName}Parser}).
 *
 * <p>Usage:
 * <pre>{@code
 *   try (var h = AptTestHarness.compile(SOURCE)) {
 *       Stream<?> s = h.parser("sample.Doc").parse(xmlBytes);
 *       ...
 *   }
 * }</pre>
 *
 * <p>The harness intentionally does not pool or cache classloaders between tests: every
 * compilation is independent so that test order cannot influence outcomes and so that
 * generated classes from one test cannot leak into another.
 */
final class AptTestHarness implements AutoCloseable {

    /**
     * Pattern used to extract the FQN of a top-level Java type from a source string.
     */
    private static final Pattern PACKAGE_PAT = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern PUBLIC_TYPE_PAT =
            Pattern.compile("(?m)\\bpublic\\s+(?:final\\s+|abstract\\s+|static\\s+)?(?:record|class|interface|enum)\\s+(\\w+)");
    private static final Pattern ANY_TYPE_PAT =
            Pattern.compile("(?m)\\b(?:record|class|interface|enum)\\s+(\\w+)");

    private final Path workDir;
    private final URLClassLoader classLoader;
    private final List<String> diagnostics;

    private AptTestHarness(Path workDir, URLClassLoader classLoader, List<String> diagnostics) {
        this.workDir = workDir;
        this.classLoader = classLoader;
        this.diagnostics = diagnostics;
    }

    /**
     * Result of a compile-only attempt — used by ErrorTest to inspect diagnostics.
     */
    record CompileResult(boolean ok, List<String> diagnostics) {
        boolean hasError(String needle) {
            for (String d : diagnostics) {
                if (d.contains(needle)) return true;
            }
            return false;
        }

        String joined() {
            return String.join("\n", diagnostics);
        }
    }

    /**
     * Run the APT processor in {@code -proc:only} mode against {@code source}. Does not
     * compile generated code. Returns diagnostics for ERROR-path assertions.
     */
    static CompileResult procOnly(String... sources) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> dc = new DiagnosticCollector<>();
        StandardJavaFileManager fm = compiler.getStandardFileManager(dc, Locale.ROOT, null);
        List<JavaFileObject> files = new ArrayList<>();
        for (String s : sources) files.add(stringSource(s));
        List<String> options = List.of(
                "-classpath", System.getProperty("java.class.path"),
                "-processor", "xmlfluss.apt.XmlDslProcessor",
                "-proc:only",
                "-Xlint:none",
                "--release", "17"
        );
        StringWriter compilerOut = new StringWriter();
        boolean ok;
        try {
            ok = compiler.getTask(compilerOut, fm, dc, options, null, files).call();
        } finally {
            try {
                fm.close();
            } catch (IOException ignored) {
            }
        }
        List<String> msgs = new ArrayList<>();
        for (var d : dc.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                msgs.add(d.getMessage(Locale.ROOT));
            }
        }
        // Some processor diagnostics come back with kind=OTHER and the message text in the
        // compiler stream. Capture both for robustness.
        if (!compilerOut.getBuffer().isEmpty()) msgs.add(compilerOut.toString());
        return new CompileResult(ok, msgs);
    }

    /**
     * Compile {@code source} (and any extra sources) with the APT processor, then compile
     * the generated parser too, into a fresh temp directory. Throws {@link AssertionError}
     * if compilation fails — the per-test ErrorTest path uses {@link #procOnly} instead.
     */
    static AptTestHarness compile(String... sources) throws IOException {
        Path work = Files.createTempDirectory("apt-test-");
        Path classesOut = work.resolve("classes");
        Path sourcesOut = work.resolve("generated-sources");
        Files.createDirectories(classesOut);
        Files.createDirectories(sourcesOut);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> dc = new DiagnosticCollector<>();
        StandardJavaFileManager fm = compiler.getStandardFileManager(dc, Locale.ROOT, null);
        try {
            fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classesOut));
            fm.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(sourcesOut));
        } catch (IOException ioe) {
            throw new IllegalStateException("could not configure file manager", ioe);
        }
        List<JavaFileObject> files = new ArrayList<>();
        for (String s : sources) files.add(stringSource(s));
        List<String> options = List.of(
                "-classpath", System.getProperty("java.class.path"),
                "-processor", "xmlfluss.apt.XmlDslProcessor",
                "-Xlint:none",
                "--release", "17"
        );
        StringWriter compilerOut = new StringWriter();
        boolean ok = compiler.getTask(compilerOut, fm, dc, options, null, files).call();
        try {
            fm.close();
        } catch (IOException ignored) {
        }

        List<String> msgs = new ArrayList<>();
        for (var d : dc.getDiagnostics()) msgs.add(d.getKind() + ": " + d.getMessage(Locale.ROOT));
        if (!compilerOut.getBuffer().isEmpty()) msgs.add(compilerOut.toString());

        if (!ok) {
            // Best-effort cleanup, then surface the failure.
            deleteRecursively(work);
            throw new AssertionError("compilation failed:\n" + String.join("\n", msgs));
        }

        URL[] urls = new URL[]{classesOut.toUri().toURL()};
        // Parent-first so JUnit / runtime classes resolve normally; the generated classes
        // and the user record itself live in the child URLClassLoader only.
        URLClassLoader cl = new URLClassLoader(urls, AptTestHarness.class.getClassLoader());
        return new AptTestHarness(work, cl, msgs);
    }

    /**
     * Returns a typed handle to the {@code ${recordFqn}Parser} class.
     */
    ParserFacade parser(String recordFqn) {
        try {
            Class<?> parserCls = classLoader.loadClass(recordFqn + "Parser");
            return new ParserFacade(parserCls);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Parser class not found for " + recordFqn
                    + " (compilation diagnostics were:\n" + String.join("\n", diagnostics) + ")", e);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            classLoader.close();
        } finally {
            deleteRecursively(workDir);
        }
    }

    /**
     * Reflective wrapper over the generated parser class. The signature is fixed:
     * {@code static Stream<T> parse(InputStream)} and
     * {@code static Stream<T> parse(InputStream, boolean)}.
     */
    static final class ParserFacade {
        private final Method parseOne;
        private final Method parseTwo;
        private final Class<?> parserCls;

        ParserFacade(Class<?> parserCls) {
            this.parserCls = parserCls;
            try {
                this.parseOne = parserCls.getMethod("parse", InputStream.class);
                this.parseTwo = parserCls.getMethod("parse", InputStream.class, boolean.class);
            } catch (NoSuchMethodException nsme) {
                throw new AssertionError("generated parser is missing parse method", nsme);
            }
        }

        @SuppressWarnings("unchecked")
        Stream<Object> parse(InputStream in) {
            try {
                return (Stream<Object>) parseOne.invoke(null, in);
            } catch (InvocationTargetException ite) {
                throw rethrow(ite);
            } catch (IllegalAccessException iae) {
                throw new AssertionError("parse() is not accessible", iae);
            }
        }

        @SuppressWarnings("unchecked")
        Stream<Object> parse(InputStream in, boolean ignoreNamespace) {
            try {
                return (Stream<Object>) parseTwo.invoke(null, in, ignoreNamespace);
            } catch (InvocationTargetException ite) {
                throw rethrow(ite);
            } catch (IllegalAccessException iae) {
                throw new AssertionError("parse(InputStream, boolean) is not accessible", iae);
            }
        }

        Class<?> parserClass() {
            return parserCls;
        }
    }

    /**
     * Translates an InvocationTargetException into the underlying runtime/error throwable.
     */
    private static RuntimeException rethrow(InvocationTargetException ite) {
        Throwable cause = ite.getCause();
        if (cause instanceof RuntimeException re) return re;
        if (cause instanceof Error er) throw er;
        return new RuntimeException(cause);
    }

    /**
     * Build a JavaFileObject from a source string. The URI is derived from the package + the
     * file's principal type — the first {@code public} top-level type when one exists,
     * otherwise the first declared type. Only one {@code public} top-level type is allowed
     * per source per the JLS.
     */
    private static JavaFileObject stringSource(String source) {
        Matcher pkg = PACKAGE_PAT.matcher(source);
        if (!pkg.find()) throw new IllegalArgumentException("source missing 'package' declaration");
        String pkgName = pkg.group(1);
        Matcher pub = PUBLIC_TYPE_PAT.matcher(source);
        if (pub.find()) return makeFile(pkgName, pub.group(1), source);
        Matcher any = ANY_TYPE_PAT.matcher(source);
        if (!any.find()) throw new IllegalArgumentException("source has no top-level type");
        return makeFile(pkgName, any.group(1), source);
    }

    private static JavaFileObject makeFile(String pkgName, String simpleName, String source) {
        URI uri = URI.create("string:///" + pkgName.replace('.', '/') + "/" + simpleName + ".java");
        return new SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public @NonNull FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public @NonNull FileVisitResult postVisitDirectory(@NonNull Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Convenience for tests: build a UTF-8 byte stream from a string.
     */
    static InputStream xml(String s) {
        return new java.io.ByteArrayInputStream(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
