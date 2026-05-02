package xmlfluss.codegen.plan;

import xmlfluss.codegen.model.FieldSpec;
import xmlfluss.codegen.model.QKey;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pre-built dispatch tables for one record. Encapsulates everything an emitter needs
 * to generate the parser switch: the direct-child trie, descendant search heads with
 * their tail tries, map plans for {@code @XmlMap} fields, polymorphic fields, and the
 * positional-counter slots for the direct-child trie.
 *
 * <p>The {@code byFq} map carries plans for every nested record reachable from the
 * top-level record, so emitters can recurse without re-walking the
 * {@link xmlfluss.codegen.model.NestedRegistry}. Every plan in the graph (top and
 * nested) shares the same {@code byFq} view, so sibling lookups work from any plan.
 * The top-level plan is not entered into {@code byFq} under its own FQN.
 *
 * @param fqn               fully qualified name of the record this plan describes
 * @param directRoot        trie of direct-child paths
 * @param descendantByHead  descendant branches grouped by their head element {@link QKey}
 * @param tailTries         tail tries for descendant heads with multi-segment paths
 * @param mapPlans          per-{@code @XmlMap}-field dispatch tables
 * @param polyFields        {@code @XmlPolymorphic} fields, in declaration order
 * @param slots             positional-counter slots for {@code directRoot}'s edges
 * @param byFq              FQN-keyed plans for every nested record reachable from this
 *                          plan; the top-level plan is not entered under its own FQN
 */
public record DispatchPlan(
        String fqn,
        TrieNode directRoot,
        Map<QKey, List<DescendantBranch>> descendantByHead,
        Map<QKey, TailTrie> tailTries,
        Map<FieldSpec, MapPlan> mapPlans,
        List<FieldSpec> polyFields,
        SlotTable slots,
        Map<String, DispatchPlan> byFq
) {
    public DispatchPlan {
        Objects.requireNonNull(fqn, "fqn");
        Objects.requireNonNull(directRoot, "directRoot");
        Objects.requireNonNull(descendantByHead, "descendantByHead");
        Objects.requireNonNull(tailTries, "tailTries");
        Objects.requireNonNull(mapPlans, "mapPlans");
        Objects.requireNonNull(polyFields, "polyFields");
        Objects.requireNonNull(slots, "slots");
        Objects.requireNonNull(byFq, "byFq");
    }

    /** Returns a copy with the supplied {@code byFq} map. */
    public DispatchPlan withByFq(Map<String, DispatchPlan> byFq) {
        return new DispatchPlan(fqn, directRoot, descendantByHead, tailTries,
                mapPlans, polyFields, slots, byFq);
    }
}
