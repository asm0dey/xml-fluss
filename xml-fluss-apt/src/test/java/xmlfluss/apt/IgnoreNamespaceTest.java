package xmlfluss.apt;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Coverage for the {@code parse(InputStream, boolean ignoreNamespace)} overload — the only
 * namespace knob the MVP exposes.
 *
 * <p>{@code ignoreNamespace=true} causes the runtime cursor to treat every element and
 * attribute as if it were in the null namespace, which is the only way an MVP-shaped record
 * (no {@code @XmlNs}, single-segment paths) can pull data out of a namespaced document.
 *
 * <p>{@code ignoreNamespace=false} (the default) leaves namespaces in place; namespaced
 * elements then don't match the null-namespace child paths the MVP emits and the matching
 * fields stay null / empty.
 */
final class IgnoreNamespaceTest {

    private static final String DOC_SRC = """
            package sample;
            import xmlfluss.XmlAttr;
            import xmlfluss.XmlChild;
            import xmlfluss.XmlRecord;
            @XmlRecord(path = "//doc")
            public record Doc(
                @XmlAttr String id,
                @XmlChild String title,
                @XmlChild String body
            ) {}
            """;

    private static Object component(Object record, String name) throws Exception {
        Method m = record.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(record);
    }

    @Test
    void ignoreNamespaceTrueMatchesElementsAcrossDeclaredNamespaces() throws Exception {
        try (var h = AptTestHarness.compile(DOC_SRC)) {
            // A document fully inside the urn:x namespace. Without ignoreNamespace, none of
            // the bare-name child paths would match.
            String xml = """
                    <x:root xmlns:x="urn:x">
                      <x:doc id="d1">
                        <x:title>T</x:title>
                        <x:body>B</x:body>
                      </x:doc>
                    </x:root>
                    """;
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml), true)) {
                List<Object> out = s.toList();
                assertEquals(1, out.size());
                assertEquals("d1", component(out.get(0), "id"));
                assertEquals("T", component(out.get(0), "title"));
                assertEquals("B", component(out.get(0), "body"));
            }
        }
    }

    @Test
    void ignoreNamespaceFalseLeavesNamespacedChildrenUnmatched() throws Exception {
        try (var h = AptTestHarness.compile(DOC_SRC)) {
            // doc element itself is null-NS (descendant axis matches), but its children live
            // in urn:x. Bare-name paths in the MVP only see the null namespace.
            String xml = """
                    <root xmlns:x="urn:x">
                      <doc id="d1">
                        <x:title>T</x:title>
                        <x:body>B</x:body>
                      </doc>
                    </root>
                    """;
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml), false)) {
                List<Object> out = s.toList();
                assertEquals(1, out.size());
                // id matches because attributes default to null-NS in XML.
                assertEquals("d1", component(out.get(0), "id"));
                // namespaced children are skipped.
                assertNull(component(out.get(0), "title"));
                assertNull(component(out.get(0), "body"));
            }
        }
    }

    @Test
    void defaultOverloadDelegatesToIgnoreNamespaceFalse() throws Exception {
        try (var h = AptTestHarness.compile(DOC_SRC)) {
            // Same input as above, but call the single-arg overload to lock in the contract
            // that parse(InputStream) is exactly parse(InputStream, false).
            String xml = """
                    <root xmlns:x="urn:x">
                      <doc id="d1">
                        <x:title>T</x:title>
                      </doc>
                    </root>
                    """;
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                Object only = s.toList().get(0);
                assertEquals("d1", component(only, "id"));
                assertNull(component(only, "title"));
            }
        }
    }

    @Test
    void ignoreNamespaceTrueWorksOnPlainNullNamespaceDocument() throws Exception {
        try (var h = AptTestHarness.compile(DOC_SRC)) {
            // Plain document — ignoreNamespace=true should be a no-op on null-NS input.
            String xml = """
                    <root>
                      <doc id="d1">
                        <title>T</title>
                        <body>B</body>
                      </doc>
                    </root>
                    """;
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml), true)) {
                Object only = s.toList().get(0);
                assertEquals("d1", component(only, "id"));
                assertEquals("T", component(only, "title"));
                assertEquals("B", component(only, "body"));
            }
        }
    }
}
