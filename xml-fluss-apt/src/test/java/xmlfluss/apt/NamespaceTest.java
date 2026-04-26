package xmlfluss.apt;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage for the Phase 5 {@code @XmlNs} / {@code @XmlNamespaces} surface on xml-fluss-apt.
 *
 * <p>Each test compiles a synthetic record annotated with one or more {@code @XmlNs} entries
 * and asserts the parser resolves prefixed paths against the bound URIs. Inheritance of
 * parent bindings into nested records, allowed redeclarations, default-namespace bare names,
 * the reserved {@code xml:} prefix, and Clark notation ({@code //{uri}name}, {@code //{*}name})
 * are all exercised here. Conflict cases (incompatible prefix redeclaration, unbound prefix)
 * live in {@link ErrorTest}.
 */
final class NamespaceTest {

    private static Object component(Object record, String name) throws Exception {
        Method m = record.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(record);
    }

    @Test
    void parsesRecordPathWithExplicitPrefix() throws Exception {
        // @XmlRecord path uses 'atom:' — the binding lives on the same record.
        String src = """
                package sample;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlNs;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//atom:entry")
                @XmlNs(prefix = "atom", uri = "http://www.w3.org/2005/Atom")
                public record Entry(
                    @XmlAttr String id,
                    @XmlChild(path = "atom:title") String title
                ) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = """
                    <feed xmlns:atom="http://www.w3.org/2005/Atom">
                      <atom:entry id="e1"><atom:title>One</atom:title></atom:entry>
                      <atom:entry id="e2"><atom:title>Two</atom:title></atom:entry>
                    </feed>
                    """;
            try (Stream<Object> s = h.parser("sample.Entry").parse(AptTestHarness.xml(xml))) {
                List<Object> out = s.toList();
                assertEquals(2, out.size());
                assertEquals("e1", component(out.get(0), "id"));
                assertEquals("One", component(out.get(0), "title"));
                assertEquals("e2", component(out.get(1), "id"));
                assertEquals("Two", component(out.get(1), "title"));
            }
        }
    }

    @Test
    void defaultNamespaceMatchesBareChildPaths() throws Exception {
        // prefix="" — bare names in @XmlChild paths and @XmlRecord path resolve to this URI.
        String src = """
                package sample;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlNs;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//entry")
                @XmlNs(prefix = "", uri = "http://www.w3.org/2005/Atom")
                public record Entry(
                    @XmlAttr String id,
                    @XmlChild String title
                ) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            // Document declares the same URI as the unprefixed default; bare names should match.
            String xml = """
                    <feed xmlns="http://www.w3.org/2005/Atom">
                      <entry id="e1"><title>Hello</title></entry>
                    </feed>
                    """;
            try (Stream<Object> s = h.parser("sample.Entry").parse(AptTestHarness.xml(xml))) {
                Object only = s.toList().get(0);
                assertEquals("e1", component(only, "id"));
                assertEquals("Hello", component(only, "title"));
            }
        }
    }

    @Test
    void readsXmlLangPrefixedAttribute() throws Exception {
        // The 'xml' prefix is reserved (XML 1.0). Binding it explicitly mirrors what users write.
        String src = """
                package sample;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlNs;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//note")
                @XmlNs(prefix = "xml", uri = "http://www.w3.org/XML/1998/namespace")
                public record Note(
                    @XmlAttr(name = "id") String id,
                    @XmlAttr(name = "xml:lang") String lang
                ) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = """
                    <root>
                      <note id="n1" xml:lang="en"/>
                      <note id="n2" xml:lang="fr"/>
                    </root>
                    """;
            try (Stream<Object> s = h.parser("sample.Note").parse(AptTestHarness.xml(xml))) {
                List<Object> out = s.toList();
                assertEquals("en", component(out.get(0), "lang"));
                assertEquals("fr", component(out.get(1), "lang"));
            }
        }
    }

    @Test
    void nestedRecordInheritsParentNamespaceBindings() throws Exception {
        // Inner has no @XmlNs of its own — must inherit the 'atom' binding from Doc to resolve
        // the 'atom:title' path inside its component.
        String src = """
                package sample;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlNs;
                import xmlfluss.XmlRecord;
                record Author(@XmlChild(path = "atom:name") String name) {}
                @XmlRecord(path = "//atom:entry")
                @XmlNs(prefix = "atom", uri = "http://www.w3.org/2005/Atom")
                public record Entry(
                    @XmlAttr String id,
                    @XmlChild(path = "atom:author") Author author
                ) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = """
                    <feed xmlns:atom="http://www.w3.org/2005/Atom">
                      <atom:entry id="e1">
                        <atom:author><atom:name>Ada</atom:name></atom:author>
                      </atom:entry>
                    </feed>
                    """;
            try (Stream<Object> s = h.parser("sample.Entry").parse(AptTestHarness.xml(xml))) {
                Object only = s.toList().get(0);
                Object author = component(only, "author");
                assertNotNull(author);
                assertEquals("Ada", component(author, "name"));
            }
        }
    }

    @Test
    void nestedRecordAddsNewPrefixWithoutConflict() throws Exception {
        // Doc declares 'atom'; Inner adds 'dc' on top — both must work.
        String src = """
                package sample;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlNs;
                import xmlfluss.XmlRecord;
                @XmlNs(prefix = "dc", uri = "http://purl.org/dc/elements/1.1/")
                record Meta(@XmlChild(path = "dc:creator") String creator,
                            @XmlChild(path = "atom:title") String title) {}
                @XmlRecord(path = "//atom:entry")
                @XmlNs(prefix = "atom", uri = "http://www.w3.org/2005/Atom")
                public record Entry(
                    @XmlChild(path = "atom:meta") Meta meta
                ) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = """
                    <feed xmlns:atom="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/elements/1.1/">
                      <atom:entry>
                        <atom:meta>
                          <dc:creator>Ada</dc:creator>
                          <atom:title>T</atom:title>
                        </atom:meta>
                      </atom:entry>
                    </feed>
                    """;
            try (Stream<Object> s = h.parser("sample.Entry").parse(AptTestHarness.xml(xml))) {
                Object only = s.toList().get(0);
                Object meta = component(only, "meta");
                assertEquals("Ada", component(meta, "creator"));
                assertEquals("T", component(meta, "title"));
            }
        }
    }

    @Test
    void nestedRecordRedeclaresSamePrefixToSameUriIsAccepted() throws Exception {
        // The nested record repeats 'atom' with the identical URI — allowed (no-op). Tests
        // that the conflict checker only fires on real disagreements.
        String src = """
                package sample;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlNs;
                import xmlfluss.XmlRecord;
                @XmlNs(prefix = "atom", uri = "http://www.w3.org/2005/Atom")
                record Inner(@XmlChild(path = "atom:title") String title) {}
                @XmlRecord(path = "//atom:entry")
                @XmlNs(prefix = "atom", uri = "http://www.w3.org/2005/Atom")
                public record Entry(@XmlChild(path = "atom:meta") Inner meta) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = """
                    <feed xmlns:atom="http://www.w3.org/2005/Atom">
                      <atom:entry>
                        <atom:meta><atom:title>T</atom:title></atom:meta>
                      </atom:entry>
                    </feed>
                    """;
            try (Stream<Object> s = h.parser("sample.Entry").parse(AptTestHarness.xml(xml))) {
                Object only = s.toList().get(0);
                Object meta = component(only, "meta");
                assertEquals("T", component(meta, "title"));
            }
        }
    }

    @Test
    void ignoreNamespaceOverloadMatchesNamespacedDocumentEvenWithPrefixedPaths() throws Exception {
        // Records the documented contract: ignoreNamespace=true matches by local name only,
        // even when paths carry namespaces declared via @XmlNs.
        String src = """
                package sample;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlNs;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//atom:entry")
                @XmlNs(prefix = "atom", uri = "http://www.w3.org/2005/Atom")
                public record Entry(
                    @XmlAttr String id,
                    @XmlChild(path = "atom:title") String title
                ) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            // Document is in a *different* namespace than the one declared on the record. With
            // ignoreNamespace=true we still match because only local names are compared.
            String xml = """
                    <feed xmlns="urn:other-namespace">
                      <entry id="e1"><title>One</title></entry>
                    </feed>
                    """;
            try (Stream<Object> s = h.parser("sample.Entry").parse(AptTestHarness.xml(xml), true)) {
                Object only = s.toList().get(0);
                assertEquals("e1", component(only, "id"));
                assertEquals("One", component(only, "title"));
            }
        }
    }

    @Test
    void clarkNotationWildcardNamespaceMatchesAnyNamespace() throws Exception {
        // The runtime path grammar accepts //{*}local — namespace wildcard, local-name match.
        String src = """
                package sample;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//{*}author")
                public record Author(@XmlChild String name) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            // Three <author> elements in three different namespaces: should all match.
            String xml = """
                    <root xmlns:a="urn:a" xmlns:b="urn:b">
                      <a:author><name>Alpha</name></a:author>
                      <author><name>Bare</name></author>
                      <b:author><name>Beta</name></b:author>
                    </root>
                    """;
            try (Stream<Object> s = h.parser("sample.Author").parse(AptTestHarness.xml(xml))) {
                List<Object> out = s.toList();
                assertEquals(3, out.size());
                assertEquals("Alpha", component(out.get(0), "name"));
                assertEquals("Bare", component(out.get(1), "name"));
                assertEquals("Beta", component(out.get(2), "name"));
            }
        }
    }

    @Test
    void clarkNotationExplicitUriResolvesByUriNotPrefix() throws Exception {
        // Same URI, different prefixes in the document — Clark notation matches them all.
        String src = """
                package sample;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//{http://www.w3.org/2005/Atom}entry")
                public record Entry(@XmlAttr String id) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = """
                    <root xmlns:a="http://www.w3.org/2005/Atom" xmlns:atom="http://www.w3.org/2005/Atom">
                      <a:entry id="e1"/>
                      <atom:entry id="e2"/>
                    </root>
                    """;
            try (Stream<Object> s = h.parser("sample.Entry").parse(AptTestHarness.xml(xml))) {
                List<Object> out = s.toList();
                assertEquals(2, out.size());
                assertEquals("e1", component(out.get(0), "id"));
                assertEquals("e2", component(out.get(1), "id"));
            }
        }
    }

    @Test
    void multipleXmlNsViaContainerRepeatableSyntax() throws Exception {
        // Two @XmlNs on the same type — the Java compiler synthesises an @XmlNamespaces
        // container; the classifier must read both bindings.
        String src = """
                package sample;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlNs;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//atom:entry")
                @XmlNs(prefix = "atom", uri = "http://www.w3.org/2005/Atom")
                @XmlNs(prefix = "dc", uri = "http://purl.org/dc/elements/1.1/")
                public record Entry(
                    @XmlChild(path = "atom:title") String title,
                    @XmlChild(path = "dc:creator") String creator
                ) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = """
                    <root xmlns:atom="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/elements/1.1/">
                      <atom:entry>
                        <atom:title>T</atom:title>
                        <dc:creator>Ada</dc:creator>
                      </atom:entry>
                    </root>
                    """;
            try (Stream<Object> s = h.parser("sample.Entry").parse(AptTestHarness.xml(xml))) {
                Object only = s.toList().get(0);
                assertEquals("T", component(only, "title"));
                assertEquals("Ada", component(only, "creator"));
            }
        }
    }

    @Test
    void prefixedChildDoesNotMatchNullNamespaceElement() throws Exception {
        // A namespaced child must not match a bare element of the same local name in null NS.
        String src = """
                package sample;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlNs;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                @XmlNs(prefix = "atom", uri = "http://www.w3.org/2005/Atom")
                public record Doc(@XmlChild(path = "atom:title") String title) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = "<root><doc><title>BareNoMatch</title></doc></root>";
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                Object only = s.toList().get(0);
                // Title is nullable (boxed String, no @NonNull) and the path didn't match.
                assertNull(component(only, "title"));
            }
        }
    }
}
