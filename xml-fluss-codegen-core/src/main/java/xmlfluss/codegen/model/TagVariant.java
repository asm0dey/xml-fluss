package xmlfluss.codegen.model;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/** Tag-based polymorphic variant: child element {@code (ns, local)} maps to subtype. */
public record TagVariant(@Nullable String ns, String local, String subtypeFq) {
    public TagVariant {
        Objects.requireNonNull(local, "local");
        Objects.requireNonNull(subtypeFq, "subtypeFq");
    }
}
