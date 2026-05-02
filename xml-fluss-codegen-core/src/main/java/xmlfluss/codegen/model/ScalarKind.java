package xmlfluss.codegen.model;

/**
 * Scalar value kinds used by {@link Coerce.Scalar}, {@link Coerce.Temporal},
 * and {@link Coerce.Decimal}. Exact mirror of the original APT enum.
 */
public enum ScalarKind {
    STRING,
    INT,
    LONG,
    DOUBLE,
    BOOLEAN,
    BIG_DECIMAL,
    LOCAL_DATE,
    LOCAL_DATE_TIME,
    INSTANT
}
