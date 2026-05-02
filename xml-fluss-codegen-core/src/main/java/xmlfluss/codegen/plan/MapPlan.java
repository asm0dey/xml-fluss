package xmlfluss.codegen.plan;

import xmlfluss.codegen.model.QKey;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pre-built dispatch tables for one {@code @XmlMap} field's synthetic key/value
 * sub-fields. Mirrors the canonical APT/KSP map-entry handling: direct child paths
 * land in {@code directRoot}; descendant sub-fields are pre-grouped by head
 * {@link QKey} (with their tail tries already built) so the emitter can be a pure
 * plan walker. Shape mirrors {@link DispatchPlan} for direct/descendant dispatch
 * (no map-of-map or polymorphic dispatch nests inside a map entry).
 *
 * @param directRoot       trie of direct-child sub-paths (key + value)
 * @param slots            positional-counter slots for {@code directRoot}'s direct edges
 * @param descendantByHead descendant sub-fields grouped by their head {@link QKey}
 * @param tailTries        per-head tail tries (with allocated slots) when at least one
 *                         field at that head has a non-empty tail; heads with only
 *                         single-segment paths are absent from this map
 */
public record MapPlan(
        TrieNode directRoot,
        SlotTable slots,
        Map<QKey, List<DescendantBranch>> descendantByHead,
        Map<QKey, TailTrie> tailTries
) {
    public MapPlan {
        Objects.requireNonNull(directRoot, "directRoot");
        Objects.requireNonNull(slots, "slots");
        Objects.requireNonNull(descendantByHead, "descendantByHead");
        Objects.requireNonNull(tailTries, "tailTries");
        descendantByHead = Map.copyOf(descendantByHead);
        tailTries = Map.copyOf(tailTries);
    }
}
