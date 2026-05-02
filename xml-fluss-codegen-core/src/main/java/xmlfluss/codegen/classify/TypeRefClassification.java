package xmlfluss.codegen.classify;

import org.jspecify.annotations.Nullable;
import xmlfluss.codegen.model.ScalarKind;
import xmlfluss.codegen.model.TypeRef;

import java.util.Map;

/**
 * Pure functions over {@link TypeRef}. Mirrors the {@code Classifier} helpers
 * {@code isJavaUtilList}, {@code isScalarOrTemporal}, {@code scalarKind}, etc.
 */
public final class TypeRefClassification {

    private TypeRefClassification() {}

    private static final Map<String, ScalarKind> SCALAR_BY_FQN = Map.ofEntries(
            Map.entry("java.lang.String", ScalarKind.STRING),
            Map.entry("java.lang.Integer", ScalarKind.INT),
            Map.entry("java.lang.Long", ScalarKind.LONG),
            Map.entry("java.lang.Double", ScalarKind.DOUBLE),
            Map.entry("java.lang.Boolean", ScalarKind.BOOLEAN),
            Map.entry("java.math.BigDecimal", ScalarKind.BIG_DECIMAL),
            Map.entry("java.time.LocalDate", ScalarKind.LOCAL_DATE),
            Map.entry("java.time.LocalDateTime", ScalarKind.LOCAL_DATE_TIME),
            Map.entry("java.time.Instant", ScalarKind.INSTANT)
    );

    private static final Map<String, ScalarKind> SCALAR_BY_PRIMITIVE = Map.of(
            "int", ScalarKind.INT,
            "long", ScalarKind.LONG,
            "double", ScalarKind.DOUBLE,
            "boolean", ScalarKind.BOOLEAN
    );

    private static final Map<String, String> PRIMITIVE_TO_BOXED_FQN = Map.of(
            "int", "java.lang.Integer",
            "long", "java.lang.Long",
            "double", "java.lang.Double",
            "boolean", "java.lang.Boolean",
            "byte", "java.lang.Byte",
            "short", "java.lang.Short",
            "float", "java.lang.Float",
            "char", "java.lang.Character"
    );

    /**
     * If {@code fqn} names a Java primitive (e.g. {@code "int"}), returns the boxed
     * wrapper FQN ({@code "java.lang.Integer"}). For non-primitive names returns the
     * input unchanged. Used by {@code @XmlConverter} assignability checks to mirror
     * {@code javax.lang.model.util.Types#boxedClass} on the SPI side.
     */
    public static String boxIfPrimitiveFqn(String fqn) {
        String boxed = PRIMITIVE_TO_BOXED_FQN.get(fqn);
        return boxed == null ? fqn : boxed;
    }

    public static boolean isJavaUtilList(TypeRef t) {
        return t.qualifiedName().equals("java.util.List");
    }

    public static boolean isJavaUtilMap(TypeRef t) {
        return t.qualifiedName().equals("java.util.Map");
    }

    public static boolean isOptional(TypeRef t) {
        return t.qualifiedName().equals("java.util.Optional");
    }

    public static boolean isString(TypeRef t) {
        return t.qualifiedName().equals("java.lang.String");
    }

    public static @Nullable ScalarKind scalarKind(TypeRef t) {
        if (t.primitive()) return SCALAR_BY_PRIMITIVE.get(t.simpleName());
        return SCALAR_BY_FQN.get(t.qualifiedName());
    }

    public static boolean isScalarOrTemporal(TypeRef t) {
        return scalarKind(t) != null;
    }

    public static boolean isFormattableType(TypeRef t) {
        ScalarKind k = scalarKind(t);
        if (k == null) return false;
        return switch (k) {
            case BIG_DECIMAL, LOCAL_DATE, LOCAL_DATE_TIME, INSTANT -> true;
            default -> false;
        };
    }

    public static TypeRef boxedScalar(ScalarKind kind) {
        return switch (kind) {
            case STRING -> TypeRef.of("java.lang", "String");
            case INT -> TypeRef.of("java.lang", "Integer");
            case LONG -> TypeRef.of("java.lang", "Long");
            case DOUBLE -> TypeRef.of("java.lang", "Double");
            case BOOLEAN -> TypeRef.of("java.lang", "Boolean");
            case BIG_DECIMAL -> TypeRef.of("java.math", "BigDecimal");
            case LOCAL_DATE -> TypeRef.of("java.time", "LocalDate");
            case LOCAL_DATE_TIME -> TypeRef.of("java.time", "LocalDateTime");
            case INSTANT -> TypeRef.of("java.time", "Instant");
        };
    }
}
