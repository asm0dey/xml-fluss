package xmlfluss.apt;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import xmlfluss.codegen.model.TypeRef;

import java.util.ArrayList;
import java.util.List;

/**
 * Translation between the neutral {@link TypeRef} carried by core specs and JavaPoet
 * {@link TypeName} the APT emitter renders. Sole place in xml-fluss-apt that bridges
 * the two type systems; called on demand at emit sites.
 */
final class TypeRefs {
    private TypeRefs() {}

    static TypeName toTypeName(TypeRef ref) {
        if (ref.primitive()) {
            return switch (ref.simpleName()) {
                case "int" -> TypeName.INT;
                case "long" -> TypeName.LONG;
                case "double" -> TypeName.DOUBLE;
                case "boolean" -> TypeName.BOOLEAN;
                default -> throw new IllegalArgumentException("unsupported primitive: " + ref.simpleName());
            };
        }
        if (!ref.typeArguments().isEmpty()) {
            List<TypeName> args = new ArrayList<>(ref.typeArguments().size());
            for (TypeRef a : ref.typeArguments()) args.add(toTypeName(a));
            return ParameterizedTypeName.get(toClassName(ref), args.toArray(new TypeName[0]));
        }
        return toClassName(ref);
    }

    /**
     * Decodes a possibly dotted simple-name (e.g. {@code "Outer.Inner"}) into a nested
     * {@link ClassName} so emitted code addresses inner records via their enclosing type.
     */
    static ClassName toClassName(TypeRef ref) {
        String simple = ref.simpleName();
        int dot = simple.indexOf('.');
        if (dot < 0) {
            return ClassName.get(ref.packageName(), simple);
        }
        String[] parts = simple.split("\\.");
        String[] rest = new String[parts.length - 1];
        System.arraycopy(parts, 1, rest, 0, rest.length);
        return ClassName.get(ref.packageName(), parts[0], rest);
    }
}
