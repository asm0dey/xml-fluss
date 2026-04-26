package xmlfluss.apt;

import org.junit.jupiter.api.Test;
import xmlfluss.XmlParseException;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Branch coverage for the required-vs-nullable field semantics. The contract:
 *
 * <ul>
 *   <li>Primitives (always {@code required}) — missing input throws
 *       {@link XmlParseException.Missing}.</li>
 *   <li>{@code @org.jspecify.annotations.NonNull} components — required, missing throws.</li>
 *   <li>Bare boxed types and objects — nullable, missing returns {@code null}.</li>
 *   <li>{@code List<T>} — always non-null; missing returns the empty list.</li>
 * </ul>
 */
final class MissingFieldTest {

    private static Object component(Object record, String name) throws Exception {
        Method m = record.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(record);
    }

    @Test
    void throwsMissingWhenRequiredAttrAbsent() throws Exception {
        // @NonNull on a String attr — required, must throw.
        String src = """
                package sample;
                import org.jspecify.annotations.NonNull;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(@XmlAttr @NonNull String id) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = "<root><doc/></root>";
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                XmlParseException.Missing ex = assertThrows(XmlParseException.Missing.class, s::toList);
                assertEquals("id", ex.getField());
            }
        }
    }

    @Test
    void throwsMissingWhenPrimitiveChildAbsent() throws Exception {
        // primitive int — implicitly required.
        String src = """
                package sample;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(@XmlChild int n) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = "<root><doc/></root>";
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                XmlParseException.Missing ex = assertThrows(XmlParseException.Missing.class, s::toList);
                assertEquals("n", ex.getField());
            }
        }
    }

    @Test
    void throwsMissingWhenRequiredChildRecordAbsent() throws Exception {
        String src = """
                package sample;
                import org.jspecify.annotations.NonNull;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                record Inner(@XmlAttr String k) {}
                @XmlRecord(path = "//doc")
                public record Doc(@XmlChild @NonNull Inner inner) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = "<root><doc/></root>";
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                XmlParseException.Missing ex = assertThrows(XmlParseException.Missing.class, s::toList);
                assertEquals("inner", ex.getField());
            }
        }
    }

    @Test
    void returnsNullWhenNullableAttrAbsent() throws Exception {
        String src = """
                package sample;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(@XmlAttr String id, @XmlAttr Integer count) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = "<root><doc/></root>";
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                Object only = s.toList().get(0);
                assertNull(component(only, "id"));
                assertNull(component(only, "count"));
            }
        }
    }

    @Test
    void returnsNullWhenNullableChildAbsent() throws Exception {
        String src = """
                package sample;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(@XmlChild String name, @XmlChild Integer count) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = "<root><doc/></root>";
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                Object only = s.toList().get(0);
                assertNull(component(only, "name"));
                assertNull(component(only, "count"));
            }
        }
    }

    @Test
    void returnsNullWhenNullableNestedRecordAbsent() throws Exception {
        String src = """
                package sample;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                record Inner(@XmlAttr String k) {}
                @XmlRecord(path = "//doc")
                public record Doc(@XmlChild Inner inner) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = "<root><doc/></root>";
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                Object only = s.toList().get(0);
                assertNull(component(only, "inner"));
            }
        }
    }

    @Test
    void returnsEmptyListWhenListChildAbsent() throws Exception {
        String src = """
                package sample;
                import java.util.List;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(@XmlChild(path = "tag") List<String> tags) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = "<root><doc/></root>";
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                Object only = s.toList().get(0);
                Object list = component(only, "tags");
                assertNotNull(list);
                assertInstanceOf(List.class, list);
                assertEquals(0, ((List<?>) list).size());
            }
        }
    }

    @Test
    void returnsEmptyListWhenListOfNestedRecordAbsent() throws Exception {
        String src = """
                package sample;
                import java.util.List;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                record Item(@XmlAttr String id) {}
                @XmlRecord(path = "//doc")
                public record Doc(@XmlChild(path = "item") List<Item> items) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = "<root><doc/></root>";
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                Object only = s.toList().get(0);
                Object list = component(only, "items");
                assertNotNull(list);
                assertEquals(0, ((List<?>) list).size());
            }
        }
    }

    @Test
    void mixesRequiredAndNullableInOneRecord() throws Exception {
        // Verifies the field-by-field decision: req present, nullable absent, list absent.
        String src = """
                package sample;
                import java.util.List;
                import org.jspecify.annotations.NonNull;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(
                    @XmlAttr @NonNull String id,
                    @XmlAttr String href,
                    @XmlChild(path = "tag") List<String> tags
                ) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = """
                    <root>
                      <doc id="d1"/>
                    </root>
                    """;
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                Object only = s.toList().get(0);
                assertEquals("d1", component(only, "id"));
                assertNull(component(only, "href"));
                assertEquals(List.of(), component(only, "tags"));
            }
        }
    }
}
