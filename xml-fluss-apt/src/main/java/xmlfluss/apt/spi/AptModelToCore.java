package xmlfluss.apt.spi;

import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import xmlfluss.codegen.model.TypeRef;

import java.util.ArrayList;
import java.util.List;

/**
 * Sole {@link TypeName} {@code -> } {@link TypeRef} adapter for the APT SPI.
 *
 * <p>This is the only place in {@code xml-fluss-apt} that converts JavaPoet's
 * {@link TypeName} into the neutral {@link TypeRef} carried by
 * {@code xmlfluss.codegen.model.*}. The three SPI views
 * ({@link AptAnnotationView}, {@link AptComponentSymbol}, {@link AptTypeSymbol})
 * route every {@code TypeName} they expose through {@link #toTypeRef(TypeName)}
 * so neutral consumers (CoreClassifier, CodeBuilder) never see a JavaPoet type.
 *
 * <p>Mirrors {@code xmlfluss.ksp.spi.KspModelToCore} on the KSP side.
 */
final class AptModelToCore {

    private AptModelToCore() {}

    static TypeRef toTypeRef(TypeName tn) {
        if (tn.isPrimitive()) {
            return TypeRef.ofPrimitive(tn.toString());
        }
        if (tn instanceof ArrayTypeName) {
            // Project does not currently use raw arrays in classified types; if we hit one
            // it represents an internal invariant violation we want to see.
            throw new IllegalArgumentException("array types not supported in TypeRef bridge: " + tn);
        }
        if (tn instanceof ParameterizedTypeName p) {
            ClassName raw = p.rawType();
            List<TypeRef> args = new ArrayList<>(p.typeArguments().size());
            for (TypeName a : p.typeArguments()) args.add(toTypeRef(a));
            return TypeRef.parameterized(raw.packageName(), nestedSimpleName(raw), args);
        }
        if (tn instanceof ClassName c) {
            return TypeRef.of(c.packageName(), nestedSimpleName(c));
        }
        throw new IllegalArgumentException("unsupported TypeName: " + tn);
    }

    /**
     * Encode nested class membership as a dotted simple name (e.g. {@code "Outer.Inner"}).
     * The KSP-side {@code KspModelToCore} mirrors this convention so both adapters emit
     * the same {@link TypeRef} shape for nested classes.
     */
    private static String nestedSimpleName(ClassName c) {
        return String.join(".", c.simpleNames());
    }
}
