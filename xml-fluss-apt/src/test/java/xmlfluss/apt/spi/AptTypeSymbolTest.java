package xmlfluss.apt.spi;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import xmlfluss.codegen.model.TypeRef;
import xmlfluss.codegen.spi.SymbolProvider;
import xmlfluss.codegen.spi.TypeSymbol;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static xmlfluss.apt.spi.SpiTestHarness.compileWithProcessing;
import static xmlfluss.apt.spi.SpiTestHarness.source;

/**
 * Verifies {@link AptTypeSymbol}'s constructor-and-supertype walk against a live javac round.
 * Uses {@link SpiTestHarness} for the in-process compile wiring.
 */
class AptTypeSymbolTest {

    @Test
    void publicNoArgConverter_resolvesProducedTypeArg() {
        AtomicReference<@Nullable Result> sink = new AtomicReference<>();

        AbstractProcessor proc = new TypeSymbolProbe(
                "p.OkConverter",
                "p.NoCtorConverter",
                "p.DoesNotExist",
                sink);

        JavaFileObject src = source("p/OkConverter",
                """
                        package p;
                        import xmlfluss.Converter;
                        import xmlfluss.Location;
                        public class OkConverter implements Converter<String> {
                            public OkConverter() {}
                            @Override public String convert(String raw, Location loc) { return raw; }
                        }
                        """);

        JavaFileObject src2 = source("p/NoCtorConverter",
                """
                        package p;
                        import xmlfluss.Converter;
                        import xmlfluss.Location;
                        public class NoCtorConverter implements Converter<Integer> {
                            private NoCtorConverter() {}
                            @Override public Integer convert(String raw, Location loc) { return Integer.valueOf(raw); }
                        }
                        """);

        // A trivial annotated record so the processor's annotation trigger fires.
        JavaFileObject anchor = source("p/Anchor",
                """
                        package p;
                        import xmlfluss.XmlRecord;
                        @XmlRecord(path = "a")
                        public record Anchor() {}
                        """);

        compileWithProcessing(List.of(src, src2, anchor), List.of(proc));

        Result r = sink.get();
        assertNotNull(r, "processor should have run");

        // Public no-arg ctor + Converter<String>
        assertNotNull(r.ok, "OkConverter should resolve");
        assertEquals("p.OkConverter", r.ok.qualifiedName());
        assertTrue(r.ok.hasPublicNoArgConstructor(),
                "OkConverter declares an explicit public no-arg ctor");
        TypeRef produced = r.ok.typeArgumentOf("xmlfluss.Converter", 0);
        assertNotNull(produced, "Converter<T> arg should resolve");
        assertEquals("java.lang.String", produced.qualifiedName());

        // Out-of-range index returns null even when the supertype is implemented.
        assertNull(r.ok.typeArgumentOf("xmlfluss.Converter", 1));
        // Unrelated supertype returns null.
        assertNull(r.ok.typeArgumentOf("java.lang.Runnable", 0));

        // Private-ctor type → no public no-arg ctor.
        assertNotNull(r.noCtor, "NoCtorConverter should resolve");
        assertFalse(r.noCtor.hasPublicNoArgConstructor(),
                "NoCtorConverter's only ctor is private");
        // Supertype walk still works for the type arg.
        TypeRef noCtorArg = r.noCtor.typeArgumentOf("xmlfluss.Converter", 0);
        assertNotNull(noCtorArg);
        assertEquals("java.lang.Integer", noCtorArg.qualifiedName());

        // Unknown FQN → null.
        assertNull(r.missing, "lookupType for unknown FQN should be null");
    }

    private record Result(@Nullable TypeSymbol ok, @Nullable TypeSymbol noCtor, @Nullable TypeSymbol missing) {}

    @SupportedAnnotationTypes("xmlfluss.XmlRecord")
    @SupportedSourceVersion(SourceVersion.RELEASE_17)
    static final class TypeSymbolProbe extends AbstractProcessor {
        private final String okFqn;
        private final String noCtorFqn;
        private final String missingFqn;
        private final AtomicReference<@Nullable Result> sink;

        TypeSymbolProbe(String okFqn, String noCtorFqn, String missingFqn, AtomicReference<@Nullable Result> sink) {
            this.okFqn = okFqn;
            this.noCtorFqn = noCtorFqn;
            this.missingFqn = missingFqn;
            this.sink = sink;
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
            if (env.processingOver()) return false;
            SymbolProvider sp = new AptSymbolProvider(processingEnv);
            TypeSymbol ok = sp.lookupType(okFqn);
            TypeSymbol noCtor = sp.lookupType(noCtorFqn);
            TypeSymbol missing = sp.lookupType(missingFqn);
            sink.set(new Result(ok, noCtor, missing));
            return true;
        }
    }
}
