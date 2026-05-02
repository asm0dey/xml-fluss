package xmlfluss.codegen.spi;

import org.jspecify.annotations.Nullable;
import xmlfluss.codegen.model.TypeRef;

import java.util.LinkedHashMap;
import java.util.Map;

public final class FakeTypeSymbol implements TypeSymbol {
    private final String fqn;
    private final boolean publicNoArgCtor;
    private final Map<String, TypeRef[]> typeArgsBySupertype;

    private FakeTypeSymbol(String fqn, boolean publicNoArgCtor, Map<String, TypeRef[]> typeArgsBySupertype) {
        this.fqn = fqn;
        this.publicNoArgCtor = publicNoArgCtor;
        this.typeArgsBySupertype = new LinkedHashMap<>(typeArgsBySupertype);
    }

    public static Builder builder(String fqn) { return new Builder(fqn); }

    @Override public String qualifiedName() { return fqn; }
    @Override public boolean hasPublicNoArgConstructor() { return publicNoArgCtor; }

    @Override public @Nullable TypeRef typeArgumentOf(String supertypeFqn, int index) {
        TypeRef[] args = typeArgsBySupertype.get(supertypeFqn);
        if (args == null || index < 0 || index >= args.length) return null;
        return args[index];
    }

    @Override public @Nullable Object nativeHandle() { return null; }

    public static final class Builder {
        private final String fqn;
        private boolean publicNoArgCtor = true;
        private final Map<String, TypeRef[]> bySupertype = new LinkedHashMap<>();

        private Builder(String fqn) { this.fqn = fqn; }
        public Builder publicNoArgCtor(boolean v) { this.publicNoArgCtor = v; return this; }
        public Builder implementsParameterized(String supertypeFqn, TypeRef... args) {
            bySupertype.put(supertypeFqn, args.clone()); return this;
        }
        public FakeTypeSymbol build() { return new FakeTypeSymbol(fqn, publicNoArgCtor, bySupertype); }
    }
}
