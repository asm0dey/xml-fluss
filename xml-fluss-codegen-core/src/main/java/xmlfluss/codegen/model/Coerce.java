package xmlfluss.codegen.model;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/** How the raw text or sub-tree of a record component is converted to its declared type. */
public sealed interface Coerce
        permits Coerce.AsString, Coerce.Scalar, Coerce.Temporal, Coerce.Decimal,
                Coerce.Custom, Coerce.Nested, Coerce.MapAggregate {

    /** No conversion — keep raw string. */
    record AsString() implements Coerce {}

    /** Numeric or boolean primitive coercion (no pattern). */
    record Scalar(ScalarKind kind) implements Coerce {
        public Scalar {
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** {@link java.time.LocalDate} / {@link java.time.LocalDateTime} / {@link java.time.Instant}. */
    record Temporal(ScalarKind kind, @Nullable String pattern) implements Coerce {
        public Temporal {
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** {@link java.math.BigDecimal} with optional decimal-format pattern. */
    record Decimal(@Nullable String pattern) implements Coerce {}

    /**
     * Custom {@link xmlfluss.Converter}.
     *
     * @param converterClass neutral type reference for the converter implementation
     * @param converterFq    fully-qualified name; convenience cache for emitters
     */
    record Custom(TypeRef converterClass, String converterFq) implements Coerce {
        public Custom {
            Objects.requireNonNull(converterClass, "converterClass");
            Objects.requireNonNull(converterFq, "converterFq");
        }
    }

    /** Nested record. */
    record Nested(String typeFq) implements Coerce {
        public Nested {
            Objects.requireNonNull(typeFq, "typeFq");
        }
    }

    /** {@code @XmlMap} aggregate. The field's key/value coercions live in synthetic specs. */
    record MapAggregate() implements Coerce {}
}
