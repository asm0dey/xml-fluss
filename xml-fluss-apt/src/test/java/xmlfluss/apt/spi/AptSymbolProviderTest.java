package xmlfluss.apt.spi;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import xmlfluss.codegen.spi.RecordSymbol;
import xmlfluss.codegen.spi.SymbolProvider;

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

import static org.junit.jupiter.api.Assertions.*;
import static xmlfluss.apt.spi.SpiTestHarness.compileWith;
import static xmlfluss.apt.spi.SpiTestHarness.source;

class AptSymbolProviderTest {

    @Test
    void roundTrip_recordWithStringAndListComponents() {
        AtomicReference<@Nullable RecordSymbol> captured = new AtomicReference<>();

        AbstractProcessor proc = new SymbolCapturingProcessor("p.Demo", captured);

        JavaFileObject src = source("p/Demo",
                """
                        package p;
                        import java.util.List;
                        import xmlfluss.XmlRecord;
                        import xmlfluss.XmlAttr;
                        import xmlfluss.XmlChild;
                        @XmlRecord(path = "demo")
                        public record Demo(@XmlAttr(name = "id") String id, @XmlChild(path = "item") List<String> items) {}
                        """);

        compileWith(List.of(src), List.of(proc));

        RecordSymbol r = captured.get();
        assertNotNull(r, "processor should have been invoked");
        assertEquals("p", r.packageName());
        assertEquals("Demo", r.simpleName());
        assertEquals("demo", r.declaredPath());
        assertEquals(2, r.components().size());

        var id = r.components().get(0);
        assertEquals("id", id.name());
        assertFalse(id.isList());
        // Plain package without @NullMarked → reference-type components are nullable by default,
        // matching the original Classifier semantics relied on by the integration suite.
        assertTrue(id.nullable());
        assertEquals("java.lang.String", id.type().qualifiedName());
        assertTrue(id.annotations().has("xmlfluss.XmlAttr"));
        assertEquals("id", id.annotations().stringValue("xmlfluss.XmlAttr", "name"));

        var items = r.components().get(1);
        assertEquals("items", items.name());
        assertTrue(items.isList());
        assertEquals("java.util.List", items.type().qualifiedName());
        assertEquals("java.lang.String", items.elementType().qualifiedName());
        assertEquals("item", items.annotations().stringValue("xmlfluss.XmlChild", "path"));
    }

    @Test
    void nullable_picksUpAnnotationOnExplicitCanonicalCtorParam() {
        AtomicReference<@Nullable RecordSymbol> captured = new AtomicReference<>();

        AbstractProcessor proc = new SymbolCapturingProcessor("p.Demo", captured);

        // Plain package (no @NullMarked) → unannotated reference-type components are nullable by
        // default. The component declaration carries no JSpecify annotation; the only @NonNull is
        // on the explicit canonical-ctor parameter. Without the ctor-param walk, this would fall
        // through to the scope default (nullable=true). With the walk, the @NonNull is honoured.
        JavaFileObject src = source("p/Demo",
                """
                        package p;
                        import org.jspecify.annotations.NonNull;
                        import xmlfluss.XmlRecord;
                        import xmlfluss.XmlAttr;
                        @XmlRecord(path = "demo")
                        public record Demo(@XmlAttr(name = "id") String id) {
                            public Demo(@NonNull String id) { this.id = id; }
                        }
                        """);

        compileWith(List.of(src), List.of(proc));

        RecordSymbol r = captured.get();
        assertNotNull(r, "processor should have been invoked");
        assertEquals(1, r.components().size());
        var id = r.components().get(0);
        assertEquals("id", id.name());
        assertFalse(id.nullable(), "@NonNull on explicit canonical-ctor param must force non-null");
    }

    @SupportedAnnotationTypes("xmlfluss.XmlRecord")
    @SupportedSourceVersion(SourceVersion.RELEASE_17)
    static final class SymbolCapturingProcessor extends AbstractProcessor {
        private final String fqn;
        private final AtomicReference<@Nullable RecordSymbol> sink;

        SymbolCapturingProcessor(String fqn, AtomicReference<@Nullable RecordSymbol> sink) {
            this.fqn = fqn;
            this.sink = sink;
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
            if (env.processingOver()) return false;
            SymbolProvider sp = new AptSymbolProvider(processingEnv);
            RecordSymbol r = sp.lookupRecord(fqn);
            if (r != null) sink.set(r);
            return true;
        }
    }
}
