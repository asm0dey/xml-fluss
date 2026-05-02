package xmlfluss.codegen.plan;

import xmlfluss.codegen.model.QKey;
import xmlfluss.path.Predicate;

import java.util.List;
import java.util.Objects;

/**
 * Slot identity used by {@link SlotTable}: a {@link QKey} plus the bracket prefix
 * that precedes the first {@code Index}-bearing bracket. Two edges with the same
 * {@code (qkey, prefix)} share one positional counter slot in generated code.
 */
public record PrefixKey(QKey qkey, List<Predicate> prefix) {
    public PrefixKey {
        Objects.requireNonNull(qkey, "qkey");
        Objects.requireNonNull(prefix, "prefix");
        prefix = List.copyOf(prefix);
    }
}
