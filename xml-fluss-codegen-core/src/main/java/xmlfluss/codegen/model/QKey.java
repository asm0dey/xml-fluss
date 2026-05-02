package xmlfluss.codegen.model;

import org.jspecify.annotations.Nullable;

/**
 * Lightweight (namespace, local-name) key. {@code ns} is {@code null} when the qualified
 * name has no explicit namespace (matches XML "no namespace" semantics).
 */
public record QKey(@Nullable String ns, String local) {
    public QKey {
        java.util.Objects.requireNonNull(local, "local");
    }
}
