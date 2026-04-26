package xmlfluss.apt;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage for the Phase 5 {@code @XmlConverter} surface on xml-fluss-apt.
 *
 * <p>Tests cover happy-path conversion through a custom {@code Converter<T>}, the contract that
 * the converter is materialised exactly once per parser class (as a {@code static final} field),
 * and the validation rejections (no public no-arg constructor; output type incompatible with
 * the field type).
 *
 * <p>Each test assembles the record + converter + value-type as separate top-level public
 * sources in the same package. Keeping them flat (instead of nested in a single Holder type)
 * keeps the generated parser's package-relative class name short and matches the
 * {@link AptTestHarness} {@code parser(fqn)} contract.
 */
final class ConverterTest {

    private static final String MONEY_SRC = """
            package sample;
            public record Money(String currency, String amount) {}
            """;

    private static final String MONEY_CONVERTER_SRC = """
            package sample;
            import xmlfluss.Converter;
            import xmlfluss.Location;
            public class MoneyConverter implements Converter<Money> {
                public static int instantiations = 0;
                public static int calls = 0;
                public MoneyConverter() { instantiations++; }
                @Override public Money convert(String raw, Location loc) {
                    calls++;
                    String[] parts = raw.trim().split("\\s+");
                    return new Money(parts[0], parts[1]);
                }
            }
            """;

    private static final String ITEM_SRC = """
            package sample;
            import xmlfluss.XmlChild;
            import xmlfluss.XmlConverter;
            import xmlfluss.XmlRecord;
            @XmlRecord(path = "//item")
            public record Item(
                @XmlChild @XmlConverter(cls = MoneyConverter.class) Money price
            ) {}
            """;

    private static Object component(Object record) throws Exception {
        Method m = record.getClass().getDeclaredMethod("price");
        m.setAccessible(true);
        return m.invoke(record);
    }

    @Test
    void parsesValueThroughCustomConverterAndInstantiatesItOnce() throws Exception {
        try (var h = AptTestHarness.compile(MONEY_SRC, MONEY_CONVERTER_SRC, ITEM_SRC)) {
            String xml = """
                    <root>
                      <item><price>USD 12.50</price></item>
                      <item><price>EUR 9.99</price></item>
                      <item><price>GBP 7.10</price></item>
                    </root>
                    """;
            try (Stream<Object> s = h.parser("sample.Item").parse(AptTestHarness.xml(xml))) {
                List<Object> out = s.toList();
                assertEquals(3, out.size());
                Object price0 = component(out.get(0));
                assertNotNull(price0);
                Method ccy = price0.getClass().getDeclaredMethod("currency");
                Method amt = price0.getClass().getDeclaredMethod("amount");
                assertEquals("USD", ccy.invoke(price0));
                assertEquals("12.50", amt.invoke(price0));
                assertEquals("EUR", ccy.invoke(component(out.get(1))));
                assertEquals("GBP", ccy.invoke(component(out.get(2))));
            }

            // After parsing, inspect the converter's static counters via the same classloader.
            // Static-final init runs once (per JLS §12.4) so any positive number of records
            // exercises the same instance.
            Class<?> conv = h.parser("sample.Item").parserClass()
                    .getClassLoader().loadClass("sample.MoneyConverter");
            Field instCount = conv.getDeclaredField("instantiations");
            Field callCount = conv.getDeclaredField("calls");
            instCount.setAccessible(true);
            callCount.setAccessible(true);
            assertEquals(1, instCount.getInt(null),
                    "converter must be instantiated exactly once across the parse stream");
            assertEquals(3, callCount.getInt(null),
                    "converter#convert must be invoked once per record");
        }
    }

    @Test
    void converterIsExposedAsStaticFinalFieldOnGeneratedParser() throws Exception {
        try (var h = AptTestHarness.compile(MONEY_SRC, MONEY_CONVERTER_SRC, ITEM_SRC)) {
            Class<?> parser = h.parser("sample.Item").parserClass();
            boolean found = false;
            for (Field f : parser.getDeclaredFields()) {
                if (!"sample.MoneyConverter".equals(f.getType().getName())) continue;
                int mods = f.getModifiers();
                assertTrue(Modifier.isStatic(mods), "converter field must be static");
                assertTrue(Modifier.isFinal(mods), "converter field must be final");
                found = true;
            }
            assertTrue(found,
                    "generated parser missing static final converter field of type MoneyConverter");
        }
    }

    @Test
    void rejectsConverterWithoutPublicNoArgConstructor() {
        String value = """
                package sample;
                public record Money(String s) {}
                """;
        String converter = """
                package sample;
                import xmlfluss.Converter;
                import xmlfluss.Location;
                public class C implements Converter<Money> {
                    private C() {}
                    @Override public Money convert(String raw, Location loc) { return new Money(raw); }
                }
                """;
        String item = """
                package sample;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlConverter;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//item")
                public record Item(@XmlChild @XmlConverter(cls = C.class) Money price) {}
                """;
        AptTestHarness.CompileResult r = AptTestHarness.procOnly(value, converter, item);
        assertFalse(r.ok(),
                "expected compile failure for converter without public no-arg constructor");
        assertTrue(r.hasError("public no-arg constructor"),
                "expected diagnostic about no-arg constructor, got:\n" + r.joined());
    }

    @Test
    void rejectsConverterWithExplicitArgConstructor() {
        // No no-arg ctor at all — only a non-default constructor exists.
        String value = """
                package sample;
                public record Money(String s) {}
                """;
        String converter = """
                package sample;
                import xmlfluss.Converter;
                import xmlfluss.Location;
                public class C implements Converter<Money> {
                    public C(String unused) {}
                    @Override public Money convert(String raw, Location loc) { return new Money(raw); }
                }
                """;
        String item = """
                package sample;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlConverter;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//item")
                public record Item(@XmlChild @XmlConverter(cls = C.class) Money price) {}
                """;
        AptTestHarness.CompileResult r = AptTestHarness.procOnly(value, converter, item);
        assertFalse(r.ok(), "expected compile failure for converter without no-arg ctor");
        assertTrue(r.hasError("public no-arg constructor"),
                "expected diagnostic about no-arg constructor, got:\n" + r.joined());
    }

    @Test
    void rejectsConverterWhoseOutputIsNotAssignableToFieldType() {
        // Converter<Integer> wired into a String field — the produced type is not assignable.
        String converter = """
                package sample;
                import xmlfluss.Converter;
                import xmlfluss.Location;
                public class IntC implements Converter<Integer> {
                    public IntC() {}
                    @Override public Integer convert(String raw, Location loc) { return Integer.parseInt(raw); }
                }
                """;
        String item = """
                package sample;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlConverter;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//item")
                public record Item(@XmlChild @XmlConverter(cls = IntC.class) String value) {}
                """;
        AptTestHarness.CompileResult r = AptTestHarness.procOnly(converter, item);
        assertFalse(r.ok(), "expected compile failure for converter type mismatch");
        assertTrue(r.hasError("not assignable"),
                "expected 'not assignable' diagnostic, got:\n" + r.joined());
    }

    @Test
    void converterMaterialisedOncePerDistinctConverterAcrossFields() throws Exception {
        // Two fields share the same converter class — the parser should hold a single
        // static final field, not two.
        String box = """
                package sample;
                public record Box(String s) {}
                """;
        String boxConverter = """
                package sample;
                import xmlfluss.Converter;
                import xmlfluss.Location;
                public class BoxC implements Converter<Box> {
                    public BoxC() {}
                    @Override public Box convert(String raw, Location loc) { return new Box(raw); }
                }
                """;
        String item = """
                package sample;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlConverter;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//item")
                public record Item(
                    @XmlChild @XmlConverter(cls = BoxC.class) Box first,
                    @XmlChild @XmlConverter(cls = BoxC.class) Box second
                ) {}
                """;
        try (var h = AptTestHarness.compile(box, boxConverter, item)) {
            Class<?> parser = h.parser("sample.Item").parserClass();
            int fieldCount = 0;
            for (Field f : parser.getDeclaredFields()) {
                if ("sample.BoxC".equals(f.getType().getName())) fieldCount++;
            }
            assertEquals(1, fieldCount,
                    "two field-uses of the same converter class must share a single static field");
        }
    }
}
