package xmlfluss.apt;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end coverage for the parity features: multi-segment / descendant / attribute-leaf
 * child paths, {@code @XmlMap}, {@code @XmlPolymorphic} (tag and attribute modes),
 * compile-time head-collision detection, and JSpecify nullability ({@code @NullMarked} +
 * {@code @Nullable}).
 */
final class NewFeaturesTest {

    // ---- multi-segment / descendant / attribute leaf -------------------------

    @Test
    void multiSegmentChildPath() throws Exception {
        String src = """
                package sample;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(@XmlChild(path = "wrapper/leaf") String s) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            var p = h.parser("sample.Doc");
            try (var s = p.parse(AptTestHarness.xml(
                    "<root><doc><wrapper><leaf>hi</leaf></wrapper></doc></root>"))) {
                Object first = s.findFirst().orElseThrow();
                assertEquals("hi", reflectGet(first, "s"));
            }
        }
    }

    @Test
    void descendantAxisChildPath() throws Exception {
        String src = """
                package sample;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(@XmlChild(path = "//deep") String d) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            try (var s = h.parser("sample.Doc").parse(AptTestHarness.xml(
                    "<doc><a><b><c><deep>found</deep></c></b></a></doc>"))) {
                assertEquals("found", reflectGet(s.findFirst().orElseThrow(), "d"));
            }
        }
    }

    @Test
    void descendantAxisWithTail() throws Exception {
        String src = """
                package sample;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(@XmlChild(path = "//head/leaf") String x) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            try (var s = h.parser("sample.Doc").parse(AptTestHarness.xml(
                    "<doc><a><head><leaf>v</leaf></head></a></doc>"))) {
                assertEquals("v", reflectGet(s.findFirst().orElseThrow(), "x"));
            }
        }
    }

    @Test
    void attributeLeafOnNestedChild() throws Exception {
        String src = """
                package sample;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(@XmlChild(path = "bio/@lang") String lang) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            try (var s = h.parser("sample.Doc").parse(AptTestHarness.xml(
                    "<doc><bio lang=\"en\">x</bio></doc>"))) {
                assertEquals("en", reflectGet(s.findFirst().orElseThrow(), "lang"));
            }
        }
    }

    @Test
    void descendantHeadCollidesWithDirect() {
        String src = """
                package sample;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(
                        @XmlChild(path = "bio") String a,
                        @XmlChild(path = "//bio") String b) {}
                """;
        AptTestHarness.CompileResult r = AptTestHarness.procOnly(src);
        assertFalse(r.ok(), "expected head collision diagnostic");
        assertTrue(r.hasError("target the same head element"), r.joined());
    }

    // ---- @XmlMap -------------------------------------------------------------

    @Test
    void mapAttrKeyTextValue() throws Exception {
        String src = """
                package sample;
                import java.util.Map;
                import xmlfluss.XmlMap;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(@XmlMap(entry = "score", key = "@who", value = "v") Map<String,Integer> scores) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            try (var s = h.parser("sample.Doc").parse(AptTestHarness.xml(
                    "<doc>"
                            + "<score who=\"alice\"><v>9</v></score>"
                            + "<score who=\"bob\"><v>7</v></score>"
                            + "</doc>"))) {
                @SuppressWarnings("unchecked")
                Map<String, Integer> m = (Map<String, Integer>) reflectGet(s.findFirst().orElseThrow(), "scores");
                assertEquals(2, m.size());
                assertEquals(9, m.get("alice"));
                assertEquals(7, m.get("bob"));
            }
        }
    }

    @Test
    void mapMultiSegmentValuePath() throws Exception {
        String src = """
                package sample;
                import java.util.Map;
                import xmlfluss.XmlMap;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(@XmlMap(entry = "e", key = "@k", value = "wrap/inner") Map<String,String> m) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            try (var s = h.parser("sample.Doc").parse(AptTestHarness.xml(
                    "<doc>"
                            + "<e k=\"a\"><wrap><inner>1</inner></wrap></e>"
                            + "<e k=\"b\"><wrap><inner>2</inner></wrap></e>"
                            + "</doc>"))) {
                @SuppressWarnings("unchecked")
                Map<String, String> m = (Map<String, String>) reflectGet(s.findFirst().orElseThrow(), "m");
                assertEquals("1", m.get("a"));
                assertEquals("2", m.get("b"));
            }
        }
    }

    @Test
    void mapDescendantValue() throws Exception {
        String src = """
                package sample;
                import java.util.Map;
                import xmlfluss.XmlMap;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(@XmlMap(entry = "e", key = "@k", value = "//deep") Map<String,String> m) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            try (var s = h.parser("sample.Doc").parse(AptTestHarness.xml(
                    "<doc>"
                            + "<e k=\"a\"><x><deep>D1</deep></x></e>"
                            + "<e k=\"b\"><y><z><deep>D2</deep></z></y></e>"
                            + "</doc>"))) {
                @SuppressWarnings("unchecked")
                Map<String, String> m = (Map<String, String>) reflectGet(s.findFirst().orElseThrow(), "m");
                assertEquals("D1", m.get("a"));
                assertEquals("D2", m.get("b"));
            }
        }
    }

    @Test
    void mapEntryClashesWithChild() {
        String src = """
                package sample;
                import java.util.Map;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlMap;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(
                        @XmlChild(path = "e") String s,
                        @XmlMap(entry = "e", key = "@k", value = "v") Map<String,String> m) {}
                """;
        AptTestHarness.CompileResult r = AptTestHarness.procOnly(src);
        assertFalse(r.ok());
        assertTrue(r.hasError("clashes with another @XmlChild"), r.joined());
    }

    // ---- @XmlPolymorphic ----------------------------------------------------

    @Test
    void polymorphicTagMode() throws Exception {
        String shape = """
                package sample;
                import xmlfluss.XmlPolymorphic;
                import xmlfluss.XmlSubtype;
                @XmlPolymorphic
                public sealed interface Shape permits Circle, Square {}
                """;
        String circle = """
                package sample;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlSubtype;
                @XmlSubtype(name = "circle")
                public record Circle(@XmlAttr int r) implements Shape {}
                """;
        String square = """
                package sample;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlSubtype;
                @XmlSubtype(name = "square")
                public record Square(@XmlAttr int side) implements Shape {}
                """;
        String drawing = """
                package sample;
                import java.util.List;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//drawing")
                public record Drawing(@XmlChild List<Shape> shapes) {}
                """;
        try (var h = AptTestHarness.compile(shape, circle, square, drawing)) {
            try (var s = h.parser("sample.Drawing").parse(AptTestHarness.xml(
                    "<drawing><circle r=\"3\"/><square side=\"4\"/><circle r=\"5\"/></drawing>"))) {
                Object d = s.findFirst().orElseThrow();
                @SuppressWarnings("unchecked")
                List<Object> shapes = (List<Object>) reflectGet(d, "shapes");
                assertEquals(3, shapes.size());
                assertEquals("Circle", shapes.get(0).getClass().getSimpleName());
                assertEquals(3, reflectGet(shapes.get(0), "r"));
                assertEquals("Square", shapes.get(1).getClass().getSimpleName());
                assertEquals(4, reflectGet(shapes.get(1), "side"));
            }
        }
    }

    @Test
    void polymorphicAttrMode() throws Exception {
        String evt = """
                package sample;
                import xmlfluss.XmlPolymorphic;
                @XmlPolymorphic(discriminator = "@kind")
                public sealed interface Evt permits Click, Hover {}
                """;
        String click = """
                package sample;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlSubtype;
                @XmlSubtype(name = "click")
                public record Click(@XmlAttr int x) implements Evt {}
                """;
        String hover = """
                package sample;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlSubtype;
                @XmlSubtype(name = "hover")
                public record Hover(@XmlAttr int t) implements Evt {}
                """;
        String log = """
                package sample;
                import java.util.List;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//log")
                public record Log(@XmlChild(path = "evt") List<Evt> events) {}
                """;
        try (var h = AptTestHarness.compile(evt, click, hover, log)) {
            try (var s = h.parser("sample.Log").parse(AptTestHarness.xml(
                    "<log>"
                            + "<evt kind=\"click\" x=\"1\"/>"
                            + "<evt kind=\"hover\" t=\"99\"/>"
                            + "<evt kind=\"unknown\"/>"
                            + "</log>"))) {
                Object l = s.findFirst().orElseThrow();
                @SuppressWarnings("unchecked")
                List<Object> events = (List<Object>) reflectGet(l, "events");
                // unknown discriminator → skip; only two events remain.
                assertEquals(2, events.size());
                assertEquals("Click", events.get(0).getClass().getSimpleName());
                assertEquals(1, reflectGet(events.get(0), "x"));
                assertEquals("Hover", events.get(1).getClass().getSimpleName());
            }
        }
    }

    @Test
    void polymorphicSubtypeMissingAnnotation() {
        String shape = """
                package sample;
                import xmlfluss.XmlPolymorphic;
                @XmlPolymorphic
                public sealed interface Shape permits BadCircle {}
                """;
        String circle = """
                package sample;
                public record BadCircle() implements Shape {}
                """;
        String drawing = """
                package sample;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//drawing")
                public record Drawing(@XmlChild Shape s) {}
                """;
        AptTestHarness.CompileResult r = AptTestHarness.procOnly(shape, circle, drawing);
        assertFalse(r.ok());
        assertTrue(r.hasError("missing @XmlSubtype"), r.joined());
    }

    // ---- JSpecify nullability ------------------------------------------------

    @Test
    void nullMarkedTypeRequiresField() throws Exception {
        String src = """
                package sample;
                import org.jspecify.annotations.NullMarked;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                @NullMarked
                @XmlRecord(path = "//doc")
                public record Doc(@XmlChild String s) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            try (var stream = h.parser("sample.Doc").parse(AptTestHarness.xml("<doc/>"))) {
                Object first = stream.findFirst().orElse(null);
                if (first != null) {
                    // Required: missing field should have thrown. If we got here, fail the test.
                    throw new AssertionError("expected missing-field exception, got " + first);
                }
            } catch (RuntimeException expected) {
                assertTrue(expected.getClass().getName().contains("Missing")
                                || expected.getMessage().contains("missing"),
                        "unexpected: " + expected);
            }
        }
    }

    @Test
    void nullMarkedTypeWithExplicitNullableIsNullable() throws Exception {
        String src = """
                package sample;
                import org.jspecify.annotations.NullMarked;
                import org.jspecify.annotations.Nullable;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                @NullMarked
                @XmlRecord(path = "//doc")
                public record Doc(@XmlChild @Nullable String s) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            try (var stream = h.parser("sample.Doc").parse(AptTestHarness.xml("<doc/>"))) {
                Object first = stream.findFirst().orElseThrow();
                assertNull(reflectGet(first, "s"));
            }
        }
    }

    // ---- positional @XmlChild brackets --------------------------------------

    @Test
    void leafPositionalSelectsSecondSibling() throws Exception {
        String item = """
                package sample;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//item")
                public record Item(@XmlAttr(name = "id") String id) {}
                """;
        String feed = """
                package sample;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//feed")
                public record FeedSecondItem(
                        @XmlChild(path = "item[2]") Item secondItem) {}
                """;
        try (var h = AptTestHarness.compile(item, feed)) {
            String xml = """
                    <feed>
                      <item id="1"/>
                      <item id="2"/>
                      <item id="3"/>
                    </feed>
                    """;
            try (var s = h.parser("sample.FeedSecondItem").parse(AptTestHarness.xml(xml))) {
                Object first = s.findFirst().orElseThrow();
                Object second = reflectGet(first, "secondItem");
                assertEquals("2", reflectGet(second, "id"));
            }
        }
    }

    // ---- helpers -------------------------------------------------------------

    private static Object reflectGet(Object o, String name) {
        try {
            Method m = o.getClass().getMethod(name);
            return m.invoke(o);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot read accessor '" + name + "' on " + o, e);
        }
    }
}
