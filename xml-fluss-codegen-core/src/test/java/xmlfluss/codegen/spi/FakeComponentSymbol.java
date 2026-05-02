package xmlfluss.codegen.spi;

import org.jspecify.annotations.Nullable;
import xmlfluss.codegen.model.TypeRef;

public record FakeComponentSymbol(
        String name,
        TypeRef type,
        boolean nullable,
        boolean isList,
        TypeRef elementType,
        @Nullable RecordSymbol asNestedRecord,
        AnnotationView annotations,
        @Nullable Object nativeHandle
) implements ComponentSymbol {

    /** Convenience: non-list, non-nested. */
    public static FakeComponentSymbol scalar(String name, TypeRef type, boolean nullable,
                                             AnnotationView annotations) {
        return new FakeComponentSymbol(name, type, nullable, false, type, null, annotations, null);
    }
}
