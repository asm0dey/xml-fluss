package xmlfluss.codegen.plan;

import org.jspecify.annotations.Nullable;
import xmlfluss.codegen.model.FieldSpec;

import java.util.Objects;

/**
 * One attribute leaf collected on a {@link TrieNode}. Equality is structural over
 * {@code (ns, name, field)}; {@code ns} is {@code null} when the attribute has no
 * namespace, matching {@link xmlfluss.codegen.model.Source.Attr} semantics.
 */
public record AttrEntry(@Nullable String ns, String name, FieldSpec field) {
    public AttrEntry {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(field, "field");
    }
}
