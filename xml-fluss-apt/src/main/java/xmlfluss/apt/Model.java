package xmlfluss.apt;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.TypeName;
import xmlfluss.path.Predicate;

import javax.lang.model.element.TypeElement;
import java.util.*;

/**
 * Internal data model for the APT processor. All types here are package-private and
 * shared between {@link Classifier} and {@link Emitter}.
 */
final class Model {

    private Model() {}

    enum ScalarKind { STRING, INT, LONG, DOUBLE, BOOLEAN, BIG_DECIMAL, LOCAL_DATE, LOCAL_DATE_TIME, INSTANT }

    /** A single segment of a multi-segment {@code @XmlChild} path. */
    sealed interface PathSeg permits PathSeg.Element, PathSeg.AttrLeaf {
        record Element(String ns, String name, List<Predicate> brackets) implements PathSeg {
            public Element(String ns, String name) { this(ns, name, List.of()); }
            /** Folded view for callers that don't care about bracket order yet. */
            public Predicate foldedPredicate() {
                if (brackets.isEmpty()) return null;
                Predicate acc = brackets.get(0);
                for (int i = 1; i < brackets.size(); i++) acc = new Predicate.And(acc, brackets.get(i));
                return acc;
            }
        }
        record AttrLeaf(String ns, String name) implements PathSeg {}
    }

    /** Composite trie key including an optional predicate to disambiguate sibling branches. */
    record EdgeKey(QKey qkey, Predicate predicate) {}

    /** What XML thing the field reads from. {@code ns} is the resolved namespace URI or {@code null}. */
    sealed interface Source permits Source.Attr, Source.Child, Source.Text, Source.MapEntry, Source.PolyChild {

        /** Attribute. Bare attribute names live in the null namespace per XML spec. */
        record Attr(String ns, String name) implements Source {}

        /** Element child path: list of segments, optional descendant axis on the head. */
        record Child(List<PathSeg> segments, boolean descendant) implements Source {
            /** First segment as Element (head). All Child paths begin with an Element segment. */
            @SuppressWarnings("unused")
            PathSeg.Element head() { return (PathSeg.Element) segments.get(0); }
        }

        record Text(boolean preserveWhitespace) implements Source {}

        /** {@code @XmlMap} repeating entry element. */
        record MapEntry(String entryNs, String entryLocal) implements Source {}

        /** {@code @XmlPolymorphic} sealed-parent dispatch. */
        record PolyChild(PolyDispatch dispatch) implements Source {}
    }

    sealed interface PolyDispatch permits PolyDispatch.Tag, PolyDispatch.Attr {
        record Tag(List<TagVariant> variants) implements PolyDispatch {}
        record Attr(String wrapNs, String wrapLocal, String attrNs, String attrLocal,
                    List<AttrVariant> variants) implements PolyDispatch {}
    }

    record TagVariant(String ns, String local, String subtypeFq) {}
    record AttrVariant(String value, String subtypeFq) {}

    /** How the raw text is converted to the field type. */
    sealed interface Coerce permits Coerce.AsString, Coerce.Scalar, Coerce.Temporal, Coerce.Decimal,
            Coerce.Custom, Coerce.Nested, Coerce.MapAggregate {

        record AsString() implements Coerce {}

        /** Numeric or boolean primitive coercion (no pattern). */
        record Scalar(ScalarKind kind) implements Coerce {}

        /** {@link java.time.LocalDate}/{@link java.time.LocalDateTime}/{@link java.time.Instant}. */
        record Temporal(ScalarKind kind, String pattern) implements Coerce {}

        /** {@link java.math.BigDecimal}. */
        record Decimal(String pattern) implements Coerce {}

        /**
         * Custom {@link xmlfluss.Converter}. {@code converterClass} is the implementation class;
         * the emitter materialises it as a static-final field on the parser.
         */
        record Custom(ClassName converterClass, String converterFq) implements Coerce {}

        /** Nested record. */
        record Nested(String typeFq) implements Coerce {}

        /** {@code @XmlMap} aggregate. The field's key/value coercions live in the synthetic specs. */
        record MapAggregate() implements Coerce {}
    }

    /**
     * One record component classified for code generation.
     *
     * @param name component name in the source record
     * @param required true when the component is non-null (primitive or {@code @NonNull})
     * @param isList true when the component is {@code List<E>}
     * @param boxedType the boxed Java type of the field for use in generated code
     *                  (e.g. {@code Integer} for {@code int}, used for nullable holders)
     * @param fieldType the actual declared type of the field (e.g. {@code int} or
     *                  {@code List<String>})
     * @param elemTypeName the element type, ignoring any {@code List} wrapper
     * @param mapKeyField synthetic key-side spec for {@code @XmlMap} fields, otherwise null
     * @param mapValueField synthetic value-side spec for {@code @XmlMap} fields, otherwise null
     */
    record FieldSpec(
            String name,
            boolean required,
            boolean isList,
            TypeName boxedType,
            TypeName fieldType,
            TypeName elemTypeName,
            String elemTypeFq,
            Source source,
            Coerce coerce,
            FieldSpec mapKeyField,
            FieldSpec mapValueField
    ) {
        /** Convenience constructor for non-map fields. */
        FieldSpec(String name, boolean required, boolean isList, TypeName boxedType, TypeName fieldType,
                  TypeName elemTypeName, String elemTypeFq, Source source, Coerce coerce) {
            this(name, required, isList, boxedType, fieldType, elemTypeName, elemTypeFq, source, coerce, null, null);
        }
    }

    /**
     * Full description of a record type to be emitted.
     *
     * @param fields ordered as declared in the record (canonical-constructor order)
     * @param nsMap effective prefix→URI map (parent merged with own {@code @XmlNs}); empty when
     *              there are no namespace declarations
     */
    record RecordSpec(
            TypeElement element,
            String packageName,
            String simpleName,
            String recordPath,
            Map<String, String> nsMap,
            List<FieldSpec> fields
    ) {}

    /**
     * Registry of nested record types encountered while classifying a top-level record.
     * The emitter generates one private static helper method per nested type.
     */
    static final class NestedRegistry {
        final Map<String, RecordSpec> byFq = new LinkedHashMap<>();
        final Map<String, String> helperByFq = new LinkedHashMap<>();
        /** Cycle-detection set: types mid-classification. */
        final Set<String> inProgress = new HashSet<>();

        @SuppressWarnings("UnusedReturnValue")
        String helperName(String fq) {
            return helperByFq.computeIfAbsent(fq, k -> {
                int dot = k.lastIndexOf('.');
                String simple = dot < 0 ? k : k.substring(dot + 1);
                String base = "__parseNested_" + simple;
                if (!helperByFq.containsValue(base)) return base;
                int n = 1;
                while (helperByFq.containsValue(base + "_" + n)) n++;
                return base + "_" + n;
            });
        }
    }

    /** Lightweight key for collision/trie maps. */
    record QKey(String ns, String local) {}
}
