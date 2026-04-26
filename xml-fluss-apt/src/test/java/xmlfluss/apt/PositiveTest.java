package xmlfluss.apt;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end happy-path coverage for xml-fluss-apt's MVP. Each test compiles a synthetic
 * record source with the processor, parses a fixture document, and asserts every emitted
 * field against an exact expected value.
 *
 * <p>Records are reflected through {@link AptTestHarness}'s classloader; the helpers below pull
 * named record components by reflection so tests stay readable without holding a compile-time
 * reference to the dynamically generated types.
 */
final class PositiveTest {

    private static Object component(Object record, String name) throws Exception {
        Method m = record.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(record);
    }

    @Test
    void parsesAttributesWithExplicitAndDefaultNames() throws Exception {
        String src = """
                package sample;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(
                    @XmlAttr(name = "id") String docId,
                    @XmlAttr String href
                ) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = """
                    <root>
                      <doc id="d1" href="https://example.com/a"/>
                      <doc id="d2" href="https://example.com/b"/>
                    </root>
                    """;
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                List<Object> out = s.toList();
                assertEquals(2, out.size());
                assertEquals("d1", component(out.get(0), "docId"));
                assertEquals("https://example.com/a", component(out.get(0), "href"));
                assertEquals("d2", component(out.get(1), "docId"));
                assertEquals("https://example.com/b", component(out.get(1), "href"));
            }
        }
    }

    @Test
    void parsesChildElementsWithExplicitAndDefaultPaths() throws Exception {
        String src = """
                package sample;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(
                    @XmlChild(path = "title") String title,
                    @XmlChild String body
                ) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = """
                    <root>
                      <doc>
                        <title>T1</title>
                        <body>B1</body>
                      </doc>
                    </root>
                    """;
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                Object only = s.toList().get(0);
                assertEquals("T1", component(only, "title"));
                assertEquals("B1", component(only, "body"));
            }
        }
    }

    @Test
    void parsesXmlTextWithDefaultWhitespaceTrimmed() throws Exception {
        String src = """
                package sample;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlRecord;
                import xmlfluss.XmlText;
                @XmlRecord(path = "//doc")
                public record Doc(
                    @XmlAttr String id,
                    @XmlText String body
                ) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = """
                    <root>
                      <doc id="d1">   hello world   </doc>
                    </root>
                    """;
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                Object only = s.toList().get(0);
                assertEquals("d1", component(only, "id"));
                assertEquals("hello world", component(only, "body"));
            }
        }
    }

    @Test
    void parsesXmlTextWithPreserveWhitespace() throws Exception {
        String src = """
                package sample;
                import xmlfluss.XmlRecord;
                import xmlfluss.XmlText;
                @XmlRecord(path = "//doc")
                public record Doc(
                    @XmlText(preserveWhitespace = true) String body
                ) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = "<root><doc>   hello   world   </doc></root>";
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                Object only = s.toList().get(0);
                assertEquals("   hello   world   ", component(only, "body"));
            }
        }
    }

    @Test
    void parsesAllScalarAndTemporalTypes() throws Exception {
        String src = """
                package sample;
                import java.math.BigDecimal;
                import java.time.Instant;
                import java.time.LocalDate;
                import java.time.LocalDateTime;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(
                    @XmlChild int i,
                    @XmlChild long l,
                    @XmlChild double d,
                    @XmlChild boolean b,
                    @XmlChild Integer iBoxed,
                    @XmlChild Long lBoxed,
                    @XmlChild Double dBoxed,
                    @XmlChild Boolean bBoxed,
                    @XmlChild BigDecimal amt,
                    @XmlChild LocalDate ld,
                    @XmlChild LocalDateTime ldt,
                    @XmlChild Instant inst
                ) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = """
                    <root>
                      <doc>
                        <i>42</i>
                        <l>9999999999</l>
                        <d>3.14</d>
                        <b>true</b>
                        <iBoxed>7</iBoxed>
                        <lBoxed>8</lBoxed>
                        <dBoxed>2.5</dBoxed>
                        <bBoxed>false</bBoxed>
                        <amt>123.45</amt>
                        <ld>2024-01-15</ld>
                        <ldt>2024-01-15T10:30:00</ldt>
                        <inst>2024-01-15T10:30:00Z</inst>
                      </doc>
                    </root>
                    """;
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                Object only = s.toList().get(0);
                assertEquals(42, component(only, "i"));
                assertEquals(9_999_999_999L, component(only, "l"));
                assertEquals(3.14, (double) component(only, "d"), 1e-9);
                assertEquals(true, component(only, "b"));
                assertEquals(7, component(only, "iBoxed"));
                assertEquals(8L, component(only, "lBoxed"));
                assertEquals(2.5, component(only, "dBoxed"));
                assertEquals(Boolean.FALSE, component(only, "bBoxed"));
                assertEquals(new BigDecimal("123.45"), component(only, "amt"));
                assertEquals(LocalDate.of(2024, 1, 15), component(only, "ld"));
                assertEquals(LocalDateTime.of(2024, 1, 15, 10, 30, 0), component(only, "ldt"));
                assertEquals(Instant.parse("2024-01-15T10:30:00Z"), component(only, "inst"));
            }
        }
    }

    @Test
    void parsesScalarListsInDocumentOrder() throws Exception {
        String src = """
                package sample;
                import java.util.List;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(
                    @XmlChild(path = "tag") List<String> tags,
                    @XmlChild(path = "n") List<Integer> ints
                ) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = """
                    <root>
                      <doc>
                        <tag>alpha</tag>
                        <tag>bravo</tag>
                        <tag>charlie</tag>
                        <n>1</n>
                        <n>2</n>
                        <n>3</n>
                      </doc>
                    </root>
                    """;
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                Object only = s.toList().get(0);
                assertEquals(List.of("alpha", "bravo", "charlie"), component(only, "tags"));
                assertEquals(List.of(1, 2, 3), component(only, "ints"));
            }
        }
    }

    @Test
    void parsesNestedRecordsWithAttrsAndText() throws Exception {
        String src = """
                package sample;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                import xmlfluss.XmlText;

                record Author(@XmlAttr String name, @XmlText String bio) {}

                @XmlRecord(path = "//book")
                public record Book(
                    @XmlChild String title,
                    @XmlChild Author author
                ) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = """
                    <root>
                      <book>
                        <title>The Book</title>
                        <author name="Ada">A pioneer</author>
                      </book>
                    </root>
                    """;
            try (Stream<Object> s = h.parser("sample.Book").parse(AptTestHarness.xml(xml))) {
                Object book = s.toList().get(0);
                assertEquals("The Book", component(book, "title"));
                Object author = component(book, "author");
                assertNotNull(author);
                assertEquals("Ada", component(author, "name"));
                assertEquals("A pioneer", component(author, "bio"));
            }
        }
    }

    @Test
    void parsesNestedRecordListsInDocumentOrder() throws Exception {
        String src = """
                package sample;
                import java.util.List;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;

                record Author(@XmlAttr String name) {}

                @XmlRecord(path = "//book")
                public record Book(
                    @XmlChild(path = "author") List<Author> authors
                ) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = """
                    <root>
                      <book>
                        <author name="Ada"/>
                        <author name="Grace"/>
                        <author name="Hedy"/>
                      </book>
                    </root>
                    """;
            try (Stream<Object> s = h.parser("sample.Book").parse(AptTestHarness.xml(xml))) {
                Object book = s.toList().get(0);
                @SuppressWarnings("unchecked")
                List<Object> authors = (List<Object>) component(book, "authors");
                assertEquals(3, authors.size());
                assertEquals("Ada", component(authors.get(0), "name"));
                assertEquals("Grace", component(authors.get(1), "name"));
                assertEquals("Hedy", component(authors.get(2), "name"));
            }
        }
    }

    @Test
    void mixesAttrsChildrenAndNestedInOneRecord() throws Exception {
        String src = """
                package sample;
                import java.util.List;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                import xmlfluss.XmlText;

                record Tag(@XmlAttr String k, @XmlText String v) {}

                @XmlRecord(path = "//doc")
                public record Doc(
                    @XmlAttr String id,
                    @XmlAttr Integer version,
                    @XmlChild String name,
                    @XmlChild(path = "tag") List<Tag> tags
                ) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = """
                    <root>
                      <doc id="d1" version="3">
                        <name>thing</name>
                        <tag k="x">one</tag>
                        <tag k="y">two</tag>
                      </doc>
                    </root>
                    """;
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                Object doc = s.toList().get(0);
                assertEquals("d1", component(doc, "id"));
                assertEquals(3, component(doc, "version"));
                assertEquals("thing", component(doc, "name"));
                @SuppressWarnings("unchecked")
                List<Object> tags = (List<Object>) component(doc, "tags");
                assertEquals(2, tags.size());
                assertEquals("x", component(tags.get(0), "k"));
                assertEquals("one", component(tags.get(0), "v"));
                assertEquals("y", component(tags.get(1), "k"));
                assertEquals("two", component(tags.get(1), "v"));
            }
        }
    }

    @Test
    void parserClassIsPublicFinalAndGeneratedIntoSamePackage() throws Exception {
        String src = """
                package sample;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//x")
                public record X(@XmlAttr String id) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            Class<?> parser = h.parser("sample.X").parserClass();
            assertEquals("sample.XParser", parser.getName());
            int mods = parser.getModifiers();
            assertTrue(java.lang.reflect.Modifier.isPublic(mods), "parser should be public");
            assertTrue(java.lang.reflect.Modifier.isFinal(mods), "parser should be final");
            // PATH field exists, so we know the path was compiled in.
            Field path = parser.getDeclaredField("PATH");
            path.setAccessible(true);
            Object pv = path.get(null);
            assertNotNull(pv);
        }
    }

    @Test
    void parsesDeeplyNestedTwoLevelRecords() throws Exception {
        String src = """
                package sample;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                import xmlfluss.XmlText;

                record City(@XmlText String name) {}
                record Address(@XmlAttr String street, @XmlChild City city) {}

                @XmlRecord(path = "//person")
                public record Person(
                    @XmlAttr String id,
                    @XmlChild Address address
                ) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = """
                    <root>
                      <person id="p1">
                        <address street="Main">
                          <city>Springfield</city>
                        </address>
                      </person>
                    </root>
                    """;
            try (Stream<Object> s = h.parser("sample.Person").parse(AptTestHarness.xml(xml))) {
                Object p = s.toList().get(0);
                assertEquals("p1", component(p, "id"));
                Object addr = component(p, "address");
                assertEquals("Main", component(addr, "street"));
                Object city = component(addr, "city");
                assertEquals("Springfield", component(city, "name"));
            }
        }
    }

    @Test
    void emptyChildElementProducesEmptyStringText() throws Exception {
        String src = """
                package sample;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(@XmlChild String body) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            // Self-closing element: present, value is the empty string (not null, not missing).
            String xml = "<root><doc><body/></doc></root>";
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                Object only = s.toList().get(0);
                assertEquals("", component(only, "body"));
            }
        }
    }

    @Test
    void unknownChildrenAreSkippedNotInjected() throws Exception {
        String src = """
                package sample;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(@XmlChild String name) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = """
                    <root>
                      <doc>
                        <name>good</name>
                        <ignoreMe>noise</ignoreMe>
                        <alsoIgnore><deep>x</deep></alsoIgnore>
                      </doc>
                    </root>
                    """;
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                Object only = s.toList().get(0);
                assertEquals("good", component(only, "name"));
            }
        }
    }
}
