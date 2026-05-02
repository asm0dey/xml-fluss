package xmlfluss.codegen.plan;

import xmlfluss.codegen.model.QKey;

import java.util.Objects;

/**
 * Trie of the descendant tail paths that share a common head element. Emitters
 * use this to walk inside a matched descendant element when there is more than
 * one path segment to resolve.
 *
 * @param head  the head {@link QKey} whose match triggers a descent into {@code trie}
 * @param trie  the tail {@link TrieNode} (rooted at the head element's children)
 * @param slots positional-counter slots for indexed brackets at the tail root
 */
public record TailTrie(QKey head, TrieNode trie, SlotTable slots) {
    public TailTrie {
        Objects.requireNonNull(head, "head");
        Objects.requireNonNull(trie, "trie");
        Objects.requireNonNull(slots, "slots");
    }
}
