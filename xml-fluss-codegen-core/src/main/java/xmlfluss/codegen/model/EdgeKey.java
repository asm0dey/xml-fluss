package xmlfluss.codegen.model;

import xmlfluss.path.Predicate;

import java.util.List;
import java.util.Objects;

/**
 * Composite trie key: a {@link QKey} plus the ordered bracket list that disambiguates
 * sibling branches with the same qualified name. Equality is structural over both.
 */
public record EdgeKey(QKey qkey, List<Predicate> brackets) {
    public EdgeKey {
        Objects.requireNonNull(qkey, "qkey");
        Objects.requireNonNull(brackets, "brackets");
        brackets = List.copyOf(brackets);
    }
}
