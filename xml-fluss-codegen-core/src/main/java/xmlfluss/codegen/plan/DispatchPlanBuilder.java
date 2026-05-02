package xmlfluss.codegen.plan;

import xmlfluss.codegen.model.*;

import java.util.*;

/**
 * Builds a {@link DispatchPlan} for a {@link RecordSpec} (and every nested record in
 * the supplied {@link NestedRegistry}). Field classification follows the canonical
 * APT/KSP behaviour:
 * <ul>
 *   <li>{@link Source.Child} with {@code descendant=false} feeds {@link TrieNode#insert}
 *       into the direct trie;</li>
 *   <li>{@link Source.Child} with {@code descendant=true} groups under its head
 *       {@link QKey} in {@code descendantByHead} and contributes a tail trie when the
 *       remaining segments are non-empty;</li>
 *   <li>{@link Source.MapEntry} produces a {@link MapPlan} keyed by the field;</li>
 *   <li>{@link Source.PolyChild} accumulates in {@code polyFields}.</li>
 * </ul>
 */
public final class DispatchPlanBuilder {

    private DispatchPlanBuilder() {}

    /**
     * Builds the top-level plan for {@code rs} and pre-computes plans for every record
     * in {@code reg}. The returned plan's {@link DispatchPlan#byFq()} contains every
     * nested-record plan from the registry, so emitters can recurse without re-walking
     * it. Each nested sub-plan also exposes that same map as its own {@code byFq}, so
     * sibling nested plans are reachable from any plan in the graph.
     */
    public static DispatchPlan build(RecordSpec rs, NestedRegistry reg) {
        LinkedHashMap<String, DispatchPlan> byFq = new LinkedHashMap<>();
        Map<String, DispatchPlan> byFqView = Collections.unmodifiableMap(byFq);
        DispatchPlan top = buildOne(rs, byFqView);
        for (var entry : reg.byFq().entrySet()) {
            byFq.put(entry.getKey(), buildOne(entry.getValue(), byFqView));
        }
        return top.withByFq(byFqView);
    }

    private static DispatchPlan buildOne(RecordSpec rs, Map<String, DispatchPlan> byFqView) {
        TrieNode directRoot = new TrieNode();
        List<FieldSpec> descendantSourceChildren = new ArrayList<>();
        Map<FieldSpec, MapPlan> mapPlans = new LinkedHashMap<>();
        List<FieldSpec> polyFields = new ArrayList<>();

        for (FieldSpec f : rs.fields()) {
            Source s = f.source();
            if (s instanceof Source.Child sc) {
                if (sc.descendant()) {
                    descendantSourceChildren.add(f);
                } else {
                    TrieNode.insert(directRoot, sc.segments(), f);
                }
            } else if (s instanceof Source.MapEntry) {
                mapPlans.put(f, buildMap(f));
            } else if (s instanceof Source.PolyChild) {
                polyFields.add(f);
            }
        }

        Map<QKey, List<DescendantBranch>> byHead = new LinkedHashMap<>();
        Map<QKey, TailTrie> tailTries = new LinkedHashMap<>();
        groupDescendants(descendantSourceChildren, byHead, tailTries);

        return new DispatchPlan(
                rs.packageName() + "." + rs.simpleName(),
                directRoot,
                Collections.unmodifiableMap(byHead),
                Collections.unmodifiableMap(tailTries),
                Collections.unmodifiableMap(mapPlans),
                List.copyOf(polyFields),
                directRoot.allocateSlots(),
                byFqView
        );
    }

    private static MapPlan buildMap(FieldSpec mapField) {
        TrieNode root = new TrieNode();
        List<FieldSpec> descendantSourceChildren = new ArrayList<>();
        FieldSpec keyF = Objects.requireNonNull(mapField.mapKeyField(),
                "MapEntry field missing key sub-spec: " + mapField.name());
        FieldSpec valF = Objects.requireNonNull(mapField.mapValueField(),
                "MapEntry field missing value sub-spec: " + mapField.name());
        for (FieldSpec sf : List.of(keyF, valF)) {
            if (sf.source() instanceof Source.Child sc) {
                if (sc.descendant()) descendantSourceChildren.add(sf);
                else TrieNode.insert(root, sc.segments(), sf);
            }
        }
        Map<QKey, List<DescendantBranch>> byHead = new LinkedHashMap<>();
        Map<QKey, TailTrie> tailTries = new LinkedHashMap<>();
        groupDescendants(descendantSourceChildren, byHead, tailTries);
        return new MapPlan(root, root.allocateSlots(), byHead, tailTries);
    }

    /**
     * Group descendant {@link Source.Child} fields by their head {@link QKey} and
     * pre-build per-head {@link TailTrie}s. {@code byHead} and {@code tailTries} are
     * mutated in place: every head appears in {@code byHead}; only heads where at
     * least one field has a non-empty tail appear in {@code tailTries}. Callers wrap
     * the resulting maps in {@link Map#copyOf(Map)} or
     * {@link Collections#unmodifiableMap(Map)}.
     */
    private static void groupDescendants(
            List<FieldSpec> descendantSourceChildren,
            Map<QKey, List<DescendantBranch>> byHead,
            Map<QKey, TailTrie> tailTries) {
        for (FieldSpec f : descendantSourceChildren) {
            Source.Child sc = (Source.Child) f.source();
            PathSeg.Element head = (PathSeg.Element) sc.segments().get(0);
            byHead.computeIfAbsent(new QKey(head.ns(), head.name()), k -> new ArrayList<>())
                    .add(new DescendantBranch(head.brackets(), f));
        }
        for (var entry : byHead.entrySet()) {
            QKey head = entry.getKey();
            List<FieldSpec> nonEmptyTail = new ArrayList<>();
            for (DescendantBranch b : entry.getValue()) {
                Source.Child sc = (Source.Child) b.field().source();
                if (sc.segments().size() > 1) nonEmptyTail.add(b.field());
            }
            if (nonEmptyTail.isEmpty()) continue;
            TrieNode tail = new TrieNode();
            for (FieldSpec f : nonEmptyTail) {
                List<PathSeg> segs = ((Source.Child) f.source()).segments();
                TrieNode.insert(tail, segs.subList(1, segs.size()), f);
            }
            tailTries.put(head, new TailTrie(head, tail, tail.allocateSlots()));
        }
    }

}
