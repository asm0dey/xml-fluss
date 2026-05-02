package xmlfluss.codegen.spi;

import org.jspecify.annotations.Nullable;
import xmlfluss.codegen.model.PolyDispatch;

import java.util.*;

public record FakeRecordSymbol(
        String packageName,
        String simpleName,
        List<ComponentSymbol> components,
        Map<String, String> declaredNamespaces,
        @Nullable String declaredPath,
        @Nullable PolyDispatch polymorphic,
        List<RecordSymbol> sealedSubtypes,
        AnnotationView annotations,
        @Nullable Object nativeHandle
) implements RecordSymbol {

    public FakeRecordSymbol {
        Objects.requireNonNull(packageName, "packageName");
        Objects.requireNonNull(simpleName, "simpleName");
        Objects.requireNonNull(components, "components");
        Objects.requireNonNull(declaredNamespaces, "declaredNamespaces");
        Objects.requireNonNull(sealedSubtypes, "sealedSubtypes");
        Objects.requireNonNull(annotations, "annotations");
        components = List.copyOf(components);
        declaredNamespaces = Collections.unmodifiableMap(new LinkedHashMap<>(declaredNamespaces));
        sealedSubtypes = List.copyOf(sealedSubtypes);
    }

    @Override
    public String qualifiedName() {
        return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }
}
