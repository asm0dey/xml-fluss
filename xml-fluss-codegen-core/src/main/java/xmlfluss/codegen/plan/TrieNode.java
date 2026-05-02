package xmlfluss.codegen.plan;

import xmlfluss.codegen.model.*;
import xmlfluss.path.Predicate;

import java.util.*;

/**
 * Mutable trie node used to build the dispatch plan for a record's child paths.
 *
 * <p>Each node carries:
 * <ul>
 *   <li>direct edges keyed by {@link EdgeKey} (qkey + bracket list),</li>
 *   <li>{@link AttrEntry} attribute leaves attached at this node,</li>
 *   <li>{@link FieldSpec} text entries (terminal element paths with non-nested coercion),</li>
 *   <li>{@link FieldSpec} nested entries (terminal element paths with {@link Coerce.Nested}).</li>
 * </ul>
 *
 * <p>The {@code insert}, {@code groupChildrenByQKey}, and {@code allocateSlots} operations
 * mirror the canonical APT/KSP behaviour so both processors produce structurally identical
 * code. Insertion order is preserved on every internal map.
 */
public final class TrieNode {

    private final LinkedHashMap<EdgeKey, TrieNode> children = new LinkedHashMap<>();
    private final List<AttrEntry> attrEntries = new ArrayList<>();
    private final List<FieldSpec> textEntries = new ArrayList<>();
    private final List<FieldSpec> nestedEntries = new ArrayList<>();

    public Map<EdgeKey, TrieNode> children() {
        return Collections.unmodifiableMap(children);
    }

    public List<AttrEntry> attrEntries() {
        return Collections.unmodifiableList(attrEntries);
    }

    public List<FieldSpec> textEntries() {
        return Collections.unmodifiableList(textEntries);
    }

    public List<FieldSpec> nestedEntries() {
        return Collections.unmodifiableList(nestedEntries);
    }

    /** True when this node carries any element/text/nested content (not just attribute leaves). */
    public boolean hasBodyContent() {
        return !textEntries.isEmpty() || !nestedEntries.isEmpty() || !children.isEmpty();
    }

    /** True when no children, attributes, text or nested entries are attached. */
    public boolean isEmpty() {
        return children.isEmpty() && attrEntries.isEmpty()
                && textEntries.isEmpty() && nestedEntries.isEmpty();
    }

    /**
     * Inserts a path-and-field pair into the trie rooted at {@code root}. Element segments
     * walk down the {@link #children} map (creating nodes on demand); a terminal
     * {@link PathSeg.AttrLeaf} attaches an {@link AttrEntry} on the deepest reached node.
     * A pure-element path attaches the field as a text or nested entry depending on the
     * field's {@link Coerce} variant.
     */
    public static void insert(TrieNode root, List<PathSeg> segments, FieldSpec field) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(segments, "segments");
        Objects.requireNonNull(field, "field");
        TrieNode node = root;
        int i = 0;
        for (; i < segments.size(); i++) {
            if (!(segments.get(i) instanceof PathSeg.Element e)) break;
            EdgeKey edge = new EdgeKey(new QKey(e.ns(), e.name()), e.brackets());
            node = node.children.computeIfAbsent(edge, k -> new TrieNode());
        }
        if (i == segments.size()) {
            if (field.coerce() instanceof Coerce.Nested) {
                node.nestedEntries.add(field);
            } else {
                node.textEntries.add(field);
            }
        } else if (i == segments.size() - 1 && segments.get(i) instanceof PathSeg.AttrLeaf al) {
            node.attrEntries.add(new AttrEntry(al.ns(), al.name(), field));
        }
    }

    /**
     * Groups direct children by their base {@link QKey}, collecting bracket-list variants
     * in declared insertion order. The returned map is mutable but emitters typically
     * treat it as read-only.
     */
    public Map<QKey, List<Map.Entry<List<Predicate>, TrieNode>>> groupChildrenByQKey() {
        LinkedHashMap<QKey, List<Map.Entry<List<Predicate>, TrieNode>>> grouped = new LinkedHashMap<>();
        for (var entry : children.entrySet()) {
            EdgeKey edge = entry.getKey();
            grouped.computeIfAbsent(edge.qkey(), k -> new ArrayList<>())
                    .add(new AbstractMap.SimpleEntry<>(edge.brackets(), entry.getValue()));
        }
        return grouped;
    }

    /**
     * Allocates a positional-counter slot for every distinct {@code (qkey, prefix-of-first-Index)}
     * encountered among this node's direct edges. Edges whose brackets contain no
     * {@link Predicate.Index} are skipped.
     */
    public SlotTable allocateSlots() {
        SlotTable out = new SlotTable();
        var grouped = groupChildrenByQKey();
        for (var qe : grouped.entrySet()) {
            QKey qk = qe.getKey();
            for (var be : qe.getValue()) {
                List<Predicate> brackets = be.getKey();
                if (!PredicateAnalysis.bracketsHaveIndex(brackets)) continue;
                List<Predicate> prefix = PredicateAnalysis.prefixOfFirstIndex(brackets);
                PrefixKey key = new PrefixKey(qk, prefix);
                if (out.containsKey(key)) continue;
                out.put(key, PredicateAnalysis.slotName(qk, out.size()));
            }
        }
        return out;
    }
}
