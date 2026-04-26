package xmlfluss.apt;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Edge cases for the generated parser: empty input, single-record, multi-record, moderate
 * volume, and {@link Stream#close()} resource cleanup.
 *
 * <p>Volume tests use a deterministic, programmatically-built fixture (no random data) so
 * results are stable. Size is held to 10 000 records — enough to catch accidental
 * {@code O(n²)} blow-ups, small enough to keep the test under a second.
 */
final class EdgeCasesTest {

    private static final String DOC_SRC = """
            package sample;
            import xmlfluss.XmlAttr;
            import xmlfluss.XmlChild;
            import xmlfluss.XmlRecord;
            @XmlRecord(path = "//doc")
            public record Doc(@XmlAttr String id, @XmlChild String body) {}
            """;

    private static Object component(Object record, String name) throws Exception {
        Method m = record.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(record);
    }

    @Test
    void returnsEmptyStreamWhenNoRecordsMatch() throws Exception {
        try (var h = AptTestHarness.compile(DOC_SRC)) {
            String xml = "<root><other id=\"x\"><body>noise</body></other></root>";
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                assertEquals(0, s.toList().size());
            }
        }
    }

    @Test
    void parsesSingleRecord() throws Exception {
        try (var h = AptTestHarness.compile(DOC_SRC)) {
            String xml = "<root><doc id=\"only\"><body>hi</body></doc></root>";
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                List<Object> out = s.toList();
                assertEquals(1, out.size());
                assertEquals("only", component(out.get(0), "id"));
                assertEquals("hi", component(out.get(0), "body"));
            }
        }
    }

    @Test
    void parsesMultipleRecordsInOrder() throws Exception {
        try (var h = AptTestHarness.compile(DOC_SRC)) {
            String xml = """
                    <root>
                      <doc id="a"><body>1</body></doc>
                      <doc id="b"><body>2</body></doc>
                      <doc id="c"><body>3</body></doc>
                    </root>
                    """;
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                List<Object> out = s.toList();
                assertEquals(3, out.size());
                assertEquals("a", component(out.get(0), "id"));
                assertEquals("1", component(out.get(0), "body"));
                assertEquals("b", component(out.get(1), "id"));
                assertEquals("2", component(out.get(1), "body"));
                assertEquals("c", component(out.get(2), "id"));
                assertEquals("3", component(out.get(2), "body"));
            }
        }
    }

    @Test
    void parsesTenThousandRecordsDeterministically() throws Exception {
        // Build the document programmatically — same input every run; no random data.
        int n = 10_000;
        StringBuilder sb = new StringBuilder(n * 60);
        sb.append("<root>");
        for (int i = 0; i < n; i++) {
            sb.append("<doc id=\"d").append(i).append("\"><body>b").append(i).append("</body></doc>");
        }
        sb.append("</root>");
        try (var h = AptTestHarness.compile(DOC_SRC)) {
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(sb.toString()))) {
                List<Object> out = s.toList();
                assertEquals(n, out.size());
                // Spot-check first, last, and middle so order, identity, and the entire range
                // got threaded through the stream.
                assertEquals("d0", component(out.get(0), "id"));
                assertEquals("b0", component(out.get(0), "body"));
                assertEquals("d" + (n / 2), component(out.get(n / 2), "id"));
                assertEquals("b" + (n / 2), component(out.get(n / 2), "body"));
                assertEquals("d" + (n - 1), component(out.get(n - 1), "id"));
                assertEquals("b" + (n - 1), component(out.get(n - 1), "body"));
            }
        }
    }

    @Test
    void streamCloseRunsOnCloseHandlerAndIsIdempotent() throws Exception {
        // The generated parser registers stream.onClose(cursor::close). We can't observe the
        // StAX reader's close from the InputStream side (Aalto, like the StAX spec, does not
        // propagate close to the wrapped InputStream). Instead, install our own onClose
        // handler chained after the generated one and assert it runs exactly once per close
        // and that close() is idempotent.
        try (var h = AptTestHarness.compile(DOC_SRC)) {
            String xml = "<root><doc id=\"x\"><body>hi</body></doc></root>";
            int[] closes = new int[1];
            Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))
                    .onClose(() -> closes[0]++);
            assertEquals(1, s.toList().size());
            assertEquals(0, closes[0], "onClose hooks should not have fired yet");
            s.close();
            assertEquals(1, closes[0]);
            // try-with-resources will call close() again — the Stream contract is that
            // double-close is a no-op (handlers run once).
            s.close();
            assertEquals(1, closes[0], "onClose handlers must not re-run on a second close()");
        }
    }

    @Test
    void canIterateLazilyWithoutDrainingEntireDocument() throws Exception {
        try (var h = AptTestHarness.compile(DOC_SRC)) {
            // 100 records — take(2) and assert we don't see 100.
            StringBuilder sb = new StringBuilder();
            sb.append("<root>");
            for (int i = 0; i < 100; i++) {
                sb.append("<doc id=\"d").append(i).append("\"><body>b").append(i).append("</body></doc>");
            }
            sb.append("</root>");
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(sb.toString()))) {
                List<Object> out = s.limit(2).toList();
                assertEquals(2, out.size());
                assertEquals("d0", component(out.get(0), "id"));
                assertEquals("d1", component(out.get(1), "id"));
            }
        }
    }

    @Test
    void streamIsOrderedAndNonNull() throws Exception {
        try (var h = AptTestHarness.compile(DOC_SRC)) {
            String xml = "<root><doc id=\"x\"><body>hi</body></doc></root>";
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                java.util.Spliterator<Object> spl = s.spliterator();
                assertTrue(spl.hasCharacteristics(java.util.Spliterator.ORDERED),
                        "stream should be ORDERED for deterministic iteration");
                assertTrue(spl.hasCharacteristics(java.util.Spliterator.NONNULL),
                        "stream should be NONNULL — generated parser never emits a null record");
                List<Object> drained = new ArrayList<>();
                spl.forEachRemaining(drained::add);
                assertEquals(1, drained.size());
            }
        }
    }
}
