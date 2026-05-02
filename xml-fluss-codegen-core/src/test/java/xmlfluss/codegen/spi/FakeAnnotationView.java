package xmlfluss.codegen.spi;

import org.jspecify.annotations.Nullable;
import xmlfluss.codegen.model.TypeRef;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builder-style fake. Use {@link #builder()} to assemble annotation entries indexed by
 * (annotation FQN, attribute name).
 */
public final class FakeAnnotationView implements AnnotationView {

    private final Map<String, Map<String, Object>> data;

    private FakeAnnotationView(Map<String, Map<String, Object>> data) {
        this.data = data;
    }

    public static Builder builder() { return new Builder(); }

    @Override
    public boolean has(String fqn) {
        return data.containsKey(fqn);
    }

    @Override
    public @Nullable String stringValue(String fqn, String attr) {
        Object v = lookup(fqn, attr);
        return v instanceof String s ? s : null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public @Nullable List<String> stringArrayValue(String fqn, String attr) {
        Object v = lookup(fqn, attr);
        return v instanceof List<?> l ? (List<String>) l : null;
    }

    @Override
    public @Nullable TypeRef classValue(String fqn, String attr) {
        Object v = lookup(fqn, attr);
        return v instanceof TypeRef t ? t : null;
    }

    @Override
    public @Nullable Boolean booleanValue(String fqn, String attr) {
        Object v = lookup(fqn, attr);
        return v instanceof Boolean b ? b : null;
    }

    private @Nullable Object lookup(String fqn, String attr) {
        Map<String, Object> attrs = data.get(fqn);
        return attrs == null ? null : attrs.get(attr);
    }

    public static final class Builder {
        private final Map<String, Map<String, Object>> data = new LinkedHashMap<>();

        public Builder annotation(String fqn) {
            data.putIfAbsent(fqn, new HashMap<>());
            return this;
        }

        public Builder string(String fqn, String attr, String value) {
            data.computeIfAbsent(fqn, k -> new HashMap<>()).put(attr, value);
            return this;
        }

        public Builder stringArray(String fqn, String attr, List<String> values) {
            data.computeIfAbsent(fqn, k -> new HashMap<>()).put(attr, List.copyOf(values));
            return this;
        }

        public Builder classRef(String fqn, String attr, TypeRef ref) {
            data.computeIfAbsent(fqn, k -> new HashMap<>()).put(attr, ref);
            return this;
        }

        public Builder bool(String fqn, String attr, boolean value) {
            data.computeIfAbsent(fqn, k -> new HashMap<>()).put(attr, value);
            return this;
        }

        public FakeAnnotationView build() {
            Map<String, Map<String, Object>> snapshot = new LinkedHashMap<>();
            data.forEach((k, v) -> snapshot.put(k, Map.copyOf(v)));
            return new FakeAnnotationView(Map.copyOf(snapshot));
        }
    }
}
