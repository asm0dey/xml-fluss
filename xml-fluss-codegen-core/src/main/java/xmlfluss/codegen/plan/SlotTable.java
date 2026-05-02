package xmlfluss.codegen.plan;

import java.util.*;

/**
 * Order-preserving map from {@link PrefixKey} to slot variable name. Insertion order
 * is the order in which slots are first encountered while walking a trie node's
 * direct edges; emitters rely on it to produce stable variable declarations.
 *
 * <p>The class is package-private mutable: only {@link TrieNode#allocateSlots()} writes
 * to it. Callers see it as an immutable, iterable view.
 */
public final class SlotTable implements Iterable<Map.Entry<PrefixKey, String>> {

    private final LinkedHashMap<PrefixKey, String> entries;

    public SlotTable() {
        this.entries = new LinkedHashMap<>();
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public boolean containsKey(PrefixKey k) {
        return entries.containsKey(k);
    }

    public String get(PrefixKey k) {
        return entries.get(k);
    }

    public Set<Map.Entry<PrefixKey, String>> entries() {
        return Collections.unmodifiableSet(entries.entrySet());
    }

    void put(PrefixKey k, String v) {
        entries.put(k, v);
    }

    @Override
    public Iterator<Map.Entry<PrefixKey, String>> iterator() {
        return entries().iterator();
    }
}
