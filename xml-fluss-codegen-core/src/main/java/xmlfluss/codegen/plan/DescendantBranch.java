package xmlfluss.codegen.plan;

import xmlfluss.codegen.model.FieldSpec;
import xmlfluss.path.Predicate;

import java.util.List;
import java.util.Objects;

/**
 * One descendant-search branch attached to a {@link DispatchPlan}: the brackets that
 * qualify the head element plus the {@link FieldSpec} that consumes the matched
 * descendant. The brackets are copied defensively to keep the record immutable.
 */
public record DescendantBranch(List<Predicate> brackets, FieldSpec field) {
    public DescendantBranch {
        Objects.requireNonNull(brackets, "brackets");
        Objects.requireNonNull(field, "field");
        brackets = List.copyOf(brackets);
    }
}
