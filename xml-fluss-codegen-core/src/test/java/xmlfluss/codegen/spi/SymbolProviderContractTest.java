package xmlfluss.codegen.spi;

import org.junit.jupiter.api.Test;
import xmlfluss.codegen.model.TypeRef;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SymbolProviderContractTest {

    @Test
    void lookupRecord_returnsNull_forUnknownFqn() {
        FakeSymbolProvider sp = new FakeSymbolProvider();
        assertNull(sp.lookupRecord("nope.Missing"));
    }

    @Test
    void lookupRecord_returnsRegisteredRecord() {
        AnnotationView none = FakeAnnotationView.builder().build();
        FakeRecordSymbol foo = new FakeRecordSymbol(
                "p", "Foo", List.of(), Map.of(), null, null, List.of(), none, null);
        FakeSymbolProvider sp = new FakeSymbolProvider().register(foo);
        RecordSymbol r = sp.lookupRecord("p.Foo");
        assertNotNull(r);
        assertEquals("Foo", r.simpleName());
    }

    @Test
    void component_carriesAnnotationData() {
        AnnotationView ann = FakeAnnotationView.builder()
                .string("xmlfluss.XmlAttr", "value", "id")
                .build();
        ComponentSymbol c = FakeComponentSymbol.scalar(
                "id", TypeRef.of("java.lang", "String"), false, ann);
        assertTrue(c.annotations().has("xmlfluss.XmlAttr"));
        assertEquals("id", c.annotations().stringValue("xmlfluss.XmlAttr", "value"));
        assertNull(c.annotations().stringValue("xmlfluss.XmlAttr", "missing"));
    }

    @Test
    void diagnostics_capturesErrorsThroughProvider() {
        FakeSymbolProvider sp = new FakeSymbolProvider();
        sp.diagnostics().error(null, "boom");
        assertTrue(sp.diagnostics().hasErrors());
    }

    @Test
    void recordSymbol_qualifiedName_handlesEmptyPackage() {
        AnnotationView none = FakeAnnotationView.builder().build();
        FakeRecordSymbol r = new FakeRecordSymbol(
                "", "Top", List.of(), Map.of(), null, null, List.of(), none, null);
        assertEquals("Top", r.qualifiedName());
    }

    @Test
    void lookupType_returnsNull_forUnknownFqn() {
        FakeSymbolProvider sp = new FakeSymbolProvider();
        assertNull(sp.lookupType("nope.Missing"));
    }

    @Test
    void lookupType_returnsRegisteredTypeSymbol() {
        FakeTypeSymbol t = FakeTypeSymbol.builder("p.MyConverter")
                .publicNoArgCtor(true)
                .implementsParameterized(
                        "xmlfluss.Converter",
                        TypeRef.of("java.lang", "String"))
                .build();
        FakeSymbolProvider sp = new FakeSymbolProvider().register(t);

        TypeSymbol found = sp.lookupType("p.MyConverter");
        assertNotNull(found);
        assertEquals("p.MyConverter", found.qualifiedName());
        assertTrue(found.hasPublicNoArgConstructor());

        TypeRef arg0 = found.typeArgumentOf("xmlfluss.Converter", 0);
        assertNotNull(arg0);
        assertEquals("java.lang.String", arg0.qualifiedName());
    }

    @Test
    void typeArgumentOf_returnsNull_forUnknownSupertype() {
        FakeTypeSymbol t = FakeTypeSymbol.builder("p.Foo")
                .implementsParameterized(
                        "xmlfluss.Converter",
                        TypeRef.of("java.lang", "String"))
                .build();
        assertNull(t.typeArgumentOf("some.Other", 0));
    }

    @Test
    void typeArgumentOf_returnsNull_forIndexOutOfRange() {
        FakeTypeSymbol t = FakeTypeSymbol.builder("p.Foo")
                .implementsParameterized(
                        "xmlfluss.Converter",
                        TypeRef.of("java.lang", "String"))
                .build();
        assertNull(t.typeArgumentOf("xmlfluss.Converter", 1));
        assertNull(t.typeArgumentOf("xmlfluss.Converter", -1));
    }

    @Test
    void lookupType_defaultsToNull_onMinimalProvider() {
        SymbolProvider sp = new SymbolProvider() {
            @Override public RecordSymbol lookupRecord(String fqn) { return null; }
            @Override public DiagnosticReporter diagnostics() {
                return new FakeDiagnosticReporter();
            }
        };
        assertNull(sp.lookupType("anything"));
    }

    @Test
    void recordSymbol_namespaceMap_preservesInsertionOrder() {
        AnnotationView none = FakeAnnotationView.builder().build();
        Map<String, String> ns = new java.util.LinkedHashMap<>();
        ns.put("a", "urn:a");
        ns.put("b", "urn:b");
        FakeRecordSymbol r = new FakeRecordSymbol(
                "p", "Foo", List.of(), ns, null, null, List.of(), none, null);
        assertEquals(List.of("a", "b"), List.copyOf(r.declaredNamespaces().keySet()));
    }
}
