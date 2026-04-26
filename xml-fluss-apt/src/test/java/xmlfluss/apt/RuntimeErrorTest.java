package xmlfluss.apt;

import org.junit.jupiter.api.Test;
import xmlfluss.Location;
import xmlfluss.XmlParseException;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runtime-error coverage for the generated parser. These exercises the runtime contract that
 * malformed XML produces {@link XmlParseException.Malformed}, type mismatches produce
 * {@link XmlParseException.Coercion}, missing required fields produce
 * {@link XmlParseException.Missing}, and that {@link Stream#close} runs cleanly even after a
 * parse-time exception (no resource leak).
 */
final class RuntimeErrorTest {

    private static final String DOC_SRC = """
            package sample;
            import org.jspecify.annotations.NonNull;
            import xmlfluss.XmlAttr;
            import xmlfluss.XmlChild;
            import xmlfluss.XmlRecord;
            @XmlRecord(path = "//doc")
            public record Doc(@XmlAttr @NonNull String id, @XmlChild int n) {}
            """;

    @Test
    void truncatedXmlThrowsMalformed() throws Exception {
        try (var h = AptTestHarness.compile(DOC_SRC)) {
            // Truncated mid-element. StAX should fail.
            String xml = "<root><doc id=\"d1\"><n>1</n></doc><doc id=\"d2\"><n>";
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                assertThrows(XmlParseException.Malformed.class, s::toList);
            }
        }
    }

    @Test
    void mismatchedTagsThrowsMalformed() throws Exception {
        try (var h = AptTestHarness.compile(DOC_SRC)) {
            String xml = "<root><doc id=\"x\"><n>1</a></doc></root>";
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                assertThrows(XmlParseException.Malformed.class, s::toList);
            }
        }
    }

    @Test
    void coercionFailureOnIntFieldIncludesFieldNameAndLocation() throws Exception {
        try (var h = AptTestHarness.compile(DOC_SRC)) {
            String xml = "<root>\n  <doc id=\"x\">\n    <n>not-a-number</n>\n  </doc>\n</root>";
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                XmlParseException.Coercion ex = assertThrows(XmlParseException.Coercion.class,
                        s::toList);
                assertEquals("n", ex.getField());
                assertEquals("not-a-number", ex.getRaw());
                Location loc = ex.getLoc();
                assertNotNull(loc);
                // line/col are one-based and within the snippet above.
                assertTrue(loc.getLine() > 0, "line must be one-based");
                assertTrue(loc.getCol() > 0, "col must be one-based");
                // path breadcrumb should reference 'n'.
                assertTrue(loc.getPath().contains("n"),
                        "expected path breadcrumb to mention 'n', got: " + loc.getPath());
            }
        }
    }

    @Test
    void coercionFailureOnLocalDateIncludesFieldName() throws Exception {
        String src = """
                package sample;
                import java.time.LocalDate;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(@XmlChild LocalDate d) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = "<root><doc><d>2026-13-99</d></doc></root>";
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                XmlParseException.Coercion ex = assertThrows(XmlParseException.Coercion.class,
                        s::toList);
                assertEquals("d", ex.getField());
                assertEquals("2026-13-99", ex.getRaw());
                assertEquals("LocalDate", ex.getType());
            }
        }
    }

    @Test
    void missingRequiredFieldMidStreamReportsLocation() throws Exception {
        // Two well-formed records followed by a record missing the required attr — the third
        // record's location is what we expect to surface.
        try (var h = AptTestHarness.compile(DOC_SRC)) {
            String xml = """
                    <root>
                      <doc id="a"><n>1</n></doc>
                      <doc id="b"><n>2</n></doc>
                      <doc><n>3</n></doc>
                    </root>
                    """;
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                XmlParseException.Missing ex = assertThrows(XmlParseException.Missing.class,
                        s::toList);
                assertEquals("id", ex.getField());
                Location loc = ex.getLoc();
                assertNotNull(loc);
                assertTrue(loc.getLine() > 0, "line must be one-based");
            }
        }
    }

    @Test
    void streamCloseRunsOnceAfterParseTimeException() throws Exception {
        // Even if iteration aborts because of a coercion failure, the user's onClose handler
        // should still fire exactly once when the try-with-resources block closes the stream.
        try (var h = AptTestHarness.compile(DOC_SRC)) {
            String xml = "<root><doc id=\"x\"><n>nope</n></doc></root>";
            AtomicInteger closes = new AtomicInteger();
            Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))
                    .onClose(closes::incrementAndGet);
            try (Stream<Object> ignored = s) {
                assertThrows(XmlParseException.Coercion.class, s::toList);
            }
            assertEquals(1, closes.get(), "onClose must fire exactly once after exception + close");
        }
    }

    @Test
    void emptyInputProducesEmptyStreamWithoutThrowing() throws Exception {
        try (var h = AptTestHarness.compile(DOC_SRC)) {
            // A valid empty document — no <doc> element matches; toList is empty, no exception.
            String xml = "<root/>";
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                assertEquals(0, s.toList().size());
            }
        }
    }

    @Test
    void completelyInvalidXmlThrowsMalformedNotGenericException() throws Exception {
        try (var h = AptTestHarness.compile(DOC_SRC)) {
            // No tags at all — StAX should fail on the first event.
            String xml = "this is not xml";
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                assertThrows(XmlParseException.Malformed.class, s::toList);
            }
        }
    }
}
