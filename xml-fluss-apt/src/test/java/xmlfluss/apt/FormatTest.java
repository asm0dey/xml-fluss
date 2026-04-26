package xmlfluss.apt;

import org.junit.jupiter.api.Test;
import xmlfluss.XmlParseException;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Coverage for the Phase 5 {@code @XmlFormat} surface on xml-fluss-apt.
 *
 * <p>Verifies that the format pattern threads through to the runtime coercion for
 * {@code LocalDate}, {@code LocalDateTime}, {@code Instant}, and {@code BigDecimal}.
 * Also covers the regression case (no {@code @XmlFormat} → ISO defaults) and the runtime
 * behaviour of an invalid pattern (compile-time-accepted, surfaces as a coercion error
 * at parse time).
 */
final class FormatTest {

    private static Object component(Object record, String name) throws Exception {
        Method m = record.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(record);
    }

    @Test
    void parsesLocalDateWithCustomPattern() throws Exception {
        String src = """
                package sample;
                import java.time.LocalDate;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlFormat;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(
                    @XmlChild @XmlFormat(pattern = "dd/MM/yyyy") LocalDate published
                ) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = "<root><doc><published>15/01/2026</published></doc></root>";
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                Object only = s.toList().get(0);
                assertEquals(LocalDate.of(2026, 1, 15), component(only, "published"));
            }
        }
    }

    @Test
    void parsesLocalDateTimeWithCustomPattern() throws Exception {
        String src = """
                package sample;
                import java.time.LocalDateTime;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlFormat;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(
                    @XmlChild @XmlFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime updatedAt
                ) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = "<root><doc><updatedAt>2026-01-15 10:30:45</updatedAt></doc></root>";
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                Object only = s.toList().get(0);
                assertEquals(LocalDateTime.of(2026, 1, 15, 10, 30, 45), component(only, "updatedAt"));
            }
        }
    }

    @Test
    void parsesInstantWithCustomPattern() throws Exception {
        String src = """
                package sample;
                import java.time.Instant;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlFormat;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(
                    @XmlChild @XmlFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX") Instant ts
                ) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = "<root><doc><ts>2026-01-15T10:30:45.123Z</ts></doc></root>";
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                Object only = s.toList().get(0);
                assertEquals(Instant.parse("2026-01-15T10:30:45.123Z"), component(only, "ts"));
            }
        }
    }

    @Test
    void parsesBigDecimalWithDecimalFormatPattern() throws Exception {
        String src = """
                package sample;
                import java.math.BigDecimal;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlFormat;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(
                    @XmlChild @XmlFormat(pattern = "#,##0.00") BigDecimal amount
                ) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = "<root><doc><amount>1,234.56</amount></doc></root>";
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                Object only = s.toList().get(0);
                assertEquals(new BigDecimal("1234.56"), component(only, "amount"));
            }
        }
    }

    @Test
    void absentXmlFormatFallsBackToIsoDefaults() throws Exception {
        // Regression: temporal/decimal fields without @XmlFormat keep working through ISO/default.
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
                    @XmlChild LocalDate ld,
                    @XmlChild LocalDateTime ldt,
                    @XmlChild Instant inst,
                    @XmlChild BigDecimal amount
                ) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = """
                    <root>
                      <doc>
                        <ld>2026-01-15</ld>
                        <ldt>2026-01-15T10:30:00</ldt>
                        <inst>2026-01-15T10:30:00Z</inst>
                        <amount>123.45</amount>
                      </doc>
                    </root>
                    """;
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                Object only = s.toList().get(0);
                assertEquals(LocalDate.of(2026, 1, 15), component(only, "ld"));
                assertEquals(LocalDateTime.of(2026, 1, 15, 10, 30, 0), component(only, "ldt"));
                assertEquals(Instant.parse("2026-01-15T10:30:00Z"), component(only, "inst"));
                assertEquals(new BigDecimal("123.45"), component(only, "amount"));
            }
        }
    }

    @Test
    void invalidDateFormatPatternSurfacesAsRuntimeCoercionFailure() throws Exception {
        // Patterns are passed verbatim to DateTimeFormatter.ofPattern at runtime. A syntactically
        // invalid pattern compiles fine; the failure surfaces when the parser tries to coerce.
        String src = """
                package sample;
                import java.time.LocalDate;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlFormat;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(
                    @XmlChild @XmlFormat(pattern = "QQQQQQQ") LocalDate ld
                ) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = "<root><doc><ld>2026-01-15</ld></doc></root>";
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                // Could be Coercion (most likely — bad pattern at parse-time) or a wrapped
                // RuntimeException; either way we do not get a clean record back.
                assertThrows(RuntimeException.class, s::toList);
            }
        }
    }

    @Test
    void valueNotMatchingFormatPatternThrowsCoercionWithFieldName() throws Exception {
        // Valid pattern, value doesn't match — must throw Coercion and tag the field name.
        String src = """
                package sample;
                import java.time.LocalDate;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlFormat;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(
                    @XmlChild @XmlFormat(pattern = "dd/MM/yyyy") LocalDate published
                ) {}
                """;
        try (var h = AptTestHarness.compile(src)) {
            String xml = "<root><doc><published>2026-01-15</published></doc></root>";
            try (Stream<Object> s = h.parser("sample.Doc").parse(AptTestHarness.xml(xml))) {
                XmlParseException.Coercion ex = assertThrows(XmlParseException.Coercion.class,
                        s::toList);
                assertEquals("published", ex.getField());
                assertEquals("2026-01-15", ex.getRaw());
            }
        }
    }

    @Test
    void rejectsXmlFormatOnString() {
        // @XmlFormat is only meaningful for temporals + BigDecimal; on String it's a compile error.
        String src = """
                package sample;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlFormat;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(@XmlChild @XmlFormat(pattern = "yyyy-MM-dd") String s) {}
                """;
        AptTestHarness.CompileResult r = AptTestHarness.procOnly(src);
        org.junit.jupiter.api.Assertions.assertFalse(r.ok(),
                "expected compile failure for @XmlFormat on String");
        org.junit.jupiter.api.Assertions.assertTrue(r.hasError("@XmlFormat"),
                "expected diagnostic mentioning @XmlFormat, got:\n" + r.joined());
    }

    @Test
    void rejectsXmlFormatOnIntegerScalar() {
        // Numeric fields don't go through DateTimeFormatter or DecimalFormat — also rejected.
        String src = """
                package sample;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlFormat;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(@XmlChild @XmlFormat(pattern = "0000") int n) {}
                """;
        AptTestHarness.CompileResult r = AptTestHarness.procOnly(src);
        org.junit.jupiter.api.Assertions.assertFalse(r.ok(),
                "expected compile failure for @XmlFormat on int");
        org.junit.jupiter.api.Assertions.assertTrue(r.hasError("@XmlFormat"),
                "expected diagnostic mentioning @XmlFormat, got:\n" + r.joined());
    }

    @Test
    void rejectsXmlFormatAndXmlConverterOnSameComponent() {
        // The two are mutually exclusive — pick exactly one path for value transformation.
        String src = """
                package sample;
                import java.math.BigDecimal;
                import xmlfluss.Converter;
                import xmlfluss.Location;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlConverter;
                import xmlfluss.XmlFormat;
                import xmlfluss.XmlRecord;
                public final class FormatConverterClash {
                    public static class C implements Converter<BigDecimal> {
                        public C() {}
                        @Override public BigDecimal convert(String raw, Location loc) { return new BigDecimal(raw); }
                    }
                    @XmlRecord(path = "//doc")
                    public record Doc(
                        @XmlChild @XmlFormat(pattern = "0.00") @XmlConverter(cls = C.class) BigDecimal amt
                    ) {}
                }
                """;
        AptTestHarness.CompileResult r = AptTestHarness.procOnly(src);
        org.junit.jupiter.api.Assertions.assertFalse(r.ok(),
                "expected compile failure for @XmlFormat + @XmlConverter on same component");
        org.junit.jupiter.api.Assertions.assertTrue(r.hasError("mutually exclusive"),
                "expected 'mutually exclusive' diagnostic, got:\n" + r.joined());
    }
}
