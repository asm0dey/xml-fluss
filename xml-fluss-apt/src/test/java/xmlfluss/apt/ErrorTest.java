package xmlfluss.apt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validation regressions for {@link XmlDslProcessor}. Each test feeds the processor a source
 * shape that the MVP's {@code Classifier} must reject and asserts:
 *
 * <ol>
 *   <li>compilation fails (or {@code -proc:only} produces ERROR diagnostics), and</li>
 *   <li>the diagnostic stream contains the exact substring that documents the rule.</li>
 * </ol>
 *
 * <p>Bug surface here is high — silently accepting an unsupported annotation would produce a
 * parser that drops the user's intent without warning. Coverage targets every entry in
 * {@code Classifier.UNSUPPORTED_ANNOTATIONS} plus each distinct {@code error(...)} site that
 * an MVP-shaped input can legitimately hit.
 */
final class ErrorTest {

    private static void assertRejected(String needle, String source) {
        AptTestHarness.CompileResult r = AptTestHarness.procOnly(source);
        assertFalse(r.ok(), "expected compilation failure but processor accepted source:\n" + r.joined());
        assertTrue(r.hasError(needle),
                "expected diagnostic containing '" + needle + "', got:\n" + r.joined());
    }

    @Test
    void rejectsAttributeLeafAtPathRoot() {
        // '@' at the root of a child path is a configuration error; use @XmlAttr for record-level
        // attributes. Nested '@attr' segments are accepted.
        String src = """
                package sample;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(@XmlChild(path = "@id") String s) {}
                """;
        assertRejected("@XmlAttr for record-level attributes", src);
    }

    @Test
    void rejectsXmlRecordOnClassNotRecord() {
        String src = """
                package sample;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public class Doc {
                    @XmlAttr String id;
                }
                """;
        assertRejected("requires a record type", src);
    }

    @Test
    void rejectsMultipleXmlTextOnSameRecord() {
        String src = """
                package sample;
                import xmlfluss.XmlRecord;
                import xmlfluss.XmlText;
                @XmlRecord(path = "//doc")
                public record Doc(@XmlText String a, @XmlText String b) {}
                """;
        assertRejected("@XmlText may appear at most once", src);
    }

    @Test
    void rejectsXmlAttrAndXmlChildOnSameComponent() {
        String src = """
                package sample;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(@XmlAttr @XmlChild String s) {}
                """;
        assertRejected("mutually exclusive", src);
    }

    @Test
    void rejectsXmlAttrAndXmlTextOnSameComponent() {
        String src = """
                package sample;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlRecord;
                import xmlfluss.XmlText;
                @XmlRecord(path = "//doc")
                public record Doc(@XmlAttr @XmlText String s) {}
                """;
        assertRejected("mutually exclusive", src);
    }

    @Test
    void rejectsXmlChildAndXmlTextOnSameComponent() {
        String src = """
                package sample;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                import xmlfluss.XmlText;
                @XmlRecord(path = "//doc")
                public record Doc(@XmlChild @XmlText String s) {}
                """;
        assertRejected("mutually exclusive", src);
    }

    @Test
    void rejectsUnsupportedFieldType() {
        String src = """
                package sample;
                import java.util.UUID;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(@XmlChild UUID id) {}
                """;
        assertRejected("Unsupported field type", src);
    }

    @Test
    void rejectsAttrOnList() {
        String src = """
                package sample;
                import java.util.List;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(@XmlAttr List<String> tags) {}
                """;
        assertRejected("@XmlAttr does not support List", src);
    }

    @Test
    void rejectsTextOnList() {
        String src = """
                package sample;
                import java.util.List;
                import xmlfluss.XmlRecord;
                import xmlfluss.XmlText;
                @XmlRecord(path = "//doc")
                public record Doc(@XmlText List<String> tags) {}
                """;
        assertRejected("@XmlText is not supported on List", src);
    }

    @Test
    void rejectsListOfList() {
        String src = """
                package sample;
                import java.util.List;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(@XmlChild List<List<String>> nested) {}
                """;
        assertRejected("List<List<T>>", src);
    }

    @Test
    void rejectsListOfOptional() {
        String src = """
                package sample;
                import java.util.List;
                import java.util.Optional;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(@XmlChild List<Optional<String>> opts) {}
                """;
        assertRejected("List<Optional<T>>", src);
    }

    @Test
    void rejectsRawList() {
        String src = """
                package sample;
                import java.util.List;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(@XmlChild @SuppressWarnings("rawtypes") List items) {}
                """;
        assertRejected("Raw List is not supported", src);
    }

    @Test
    void rejectsAttrOnNonScalarType() {
        // @XmlAttr only supports scalars/temporals; a nested record on attr is invalid.
        String src = """
                package sample;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlRecord;
                record Inner(@XmlAttr String x) {}
                @XmlRecord(path = "//doc")
                public record Doc(@XmlAttr Inner inner) {}
                """;
        assertRejected("@XmlAttr requires a scalar type", src);
    }

    @Test
    void rejectsDuplicateAttrName() {
        String src = """
                package sample;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(
                    @XmlAttr(name = "id") String a,
                    @XmlAttr(name = "id") String b
                ) {}
                """;
        assertRejected("duplicate @XmlAttr name 'id'", src);
    }

    // ----- Phase 5: namespace, format, converter, and path-validation rejections -----

    @Test
    void rejectsNestedRecordRedeclaringPrefixToDifferentUri() {
        // Same prefix, conflicting URIs across the parent/nested boundary — must fail.
        String src = """
                package sample;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlNs;
                import xmlfluss.XmlRecord;
                @XmlNs(prefix = "x", uri = "urn:other")
                record Inner(@XmlChild(path = "x:title") String title) {}
                @XmlRecord(path = "//doc")
                @XmlNs(prefix = "x", uri = "urn:parent")
                public record Doc(@XmlChild(path = "x:inner") Inner inner) {}
                """;
        assertRejected("redeclares @XmlNs prefix 'x'", src);
    }

    @Test
    void rejectsXmlNsConflictingPrefixOnSameType() {
        // Two @XmlNs on the same record bind 'x' to two different URIs.
        String src = """
                package sample;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlNs;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                @XmlNs(prefix = "x", uri = "urn:a")
                @XmlNs(prefix = "x", uri = "urn:b")
                public record Doc(@XmlAttr String id) {}
                """;
        assertRejected("bound to two URIs", src);
    }

    @Test
    void rejectsUnboundPrefixInChildPath() {
        // 'foo:' is referenced but never bound by any @XmlNs.
        String src = """
                package sample;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(@XmlChild(path = "foo:title") String title) {}
                """;
        assertRejected("unbound NS prefix 'foo'", src);
    }

    @Test
    void rejectsUnboundPrefixInAttrName() {
        String src = """
                package sample;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                public record Doc(@XmlAttr(name = "foo:lang") String lang) {}
                """;
        assertRejected("unbound NS prefix 'foo'", src);
    }

    @Test
    void rejectsRecordPathWithMalformedPredicate() {
        // The runtime Paths.compile chokes on the unterminated predicate; the processor must
        // surface that as an ERROR diagnostic citing the offending path string.
        String src = """
                package sample;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc[@id=")
                public record Doc(@XmlAttr String id) {}
                """;
        assertRejected("//doc[@id=", src);
    }

    @Test
    void rejectsRecordPathWithUnterminatedClarkBrace() {
        String src = """
                package sample;
                import xmlfluss.XmlAttr;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//{urn:a")
                public record Doc(@XmlAttr String id) {}
                """;
        assertRejected("//{urn:a", src);
    }

    @Test
    void rejectsChildPathWithMalformedQname() {
        // 'foo:' (empty local) is malformed regardless of whether the prefix is bound.
        String src = """
                package sample;
                import xmlfluss.XmlChild;
                import xmlfluss.XmlNs;
                import xmlfluss.XmlRecord;
                @XmlRecord(path = "//doc")
                @XmlNs(prefix = "foo", uri = "urn:foo")
                public record Doc(@XmlChild(path = "foo:") String s) {}
                """;
        assertRejected("malformed", src);
    }
}
