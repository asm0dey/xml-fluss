package xmlfluss.codegen.model;

import java.util.List;
import java.util.Objects;

/**
 * Neutral type reference used throughout the codegen core in place of JavaPoet
 * {@code TypeName} or KotlinPoet {@code TypeName}. Each emitter converts {@code TypeRef}
 * to its native representation at emit time.
 *
 * @param packageName fully-qualified package; empty string for primitives
 * @param simpleName  simple type name (e.g. {@code "List"}, {@code "int"})
 * @param typeArguments parameterized arguments; empty list for non-parameterized
 * @param primitive   {@code true} for Java primitive types; package must be empty
 * @param nullable    {@code true} when the type is declared nullable at the use site
 */
public record TypeRef(
        String packageName,
        String simpleName,
        List<TypeRef> typeArguments,
        boolean primitive,
        boolean nullable
) {
    public TypeRef {
        Objects.requireNonNull(packageName, "packageName");
        Objects.requireNonNull(simpleName, "simpleName");
        Objects.requireNonNull(typeArguments, "typeArguments");
        if (primitive && !packageName.isEmpty()) {
            throw new IllegalArgumentException("primitive types must have empty package");
        }
        typeArguments = List.copyOf(typeArguments);
    }

    public static TypeRef of(String packageName, String simpleName) {
        return new TypeRef(packageName, simpleName, List.of(), false, false);
    }

    public static TypeRef ofPrimitive(String name) {
        return new TypeRef("", name, List.of(), true, false);
    }

    public static TypeRef parameterized(String packageName, String simpleName, List<TypeRef> args) {
        return new TypeRef(packageName, simpleName, args, false, false);
    }

    public String qualifiedName() {
        return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }

    public TypeRef asNullable() {
        return nullable ? this : new TypeRef(packageName, simpleName, typeArguments, primitive, true);
    }
}
