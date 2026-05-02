package xmlfluss.codegen.plan;

import org.junit.jupiter.api.Test;
import xmlfluss.codegen.model.*;
import xmlfluss.path.Predicate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class TrieNodeTest {

    @Test void insert_pure_element_path_sets_textEntry() {
        TrieNode root = new TrieNode();
        FieldSpec f = scalarField("title");
        TrieNode.insert(root, List.of(elem(null, "title")), f);

        TrieNode child = root.children().get(new EdgeKey(new QKey(null, "title"), List.of()));
        assertNotNull(child);
        assertEquals(List.of(f), child.textEntries());
        assertTrue(child.attrEntries().isEmpty());
        assertTrue(child.nestedEntries().isEmpty());
    }

    @Test void insert_nested_coerce_sets_nestedEntry() {
        TrieNode root = new TrieNode();
        FieldSpec f = nestedField("body", "com.ex.Body");
        TrieNode.insert(root, List.of(elem(null, "body")), f);

        TrieNode child = root.children().get(new EdgeKey(new QKey(null, "body"), List.of()));
        assertEquals(List.of(f), child.nestedEntries());
    }

    @Test void insert_attr_leaf_attaches_attrEntry() {
        TrieNode root = new TrieNode();
        FieldSpec f = scalarField("href");
        TrieNode.insert(root, List.of(elem(null, "link"), new PathSeg.AttrLeaf(null, "href")), f);

        TrieNode link = root.children().get(new EdgeKey(new QKey(null, "link"), List.of()));
        assertEquals(List.of(new AttrEntry(null, "href", f)), link.attrEntries());
    }

    @Test void groupChildrenByQKey_collapses_bracket_variants() {
        TrieNode root = new TrieNode();
        FieldSpec a = scalarField("a");
        FieldSpec b = scalarField("b");
        TrieNode.insert(root, List.of(elemWithBracket("link", new Predicate.Index(1))), a);
        TrieNode.insert(root, List.of(elemWithBracket("link", new Predicate.Index(2))), b);

        var grouped = root.groupChildrenByQKey();
        assertEquals(1, grouped.size());
        assertEquals(2, grouped.get(new QKey(null, "link")).size());
    }

    @Test void allocateSlots_emits_slot_per_index_bracket_prefix() {
        TrieNode root = new TrieNode();
        FieldSpec a = scalarField("a");
        FieldSpec b = scalarField("b");
        TrieNode.insert(root, List.of(elemWithBracket("e", new Predicate.Index(1))), a);
        TrieNode.insert(root, List.of(elemWithBracket("e", new Predicate.Index(2))), b);

        SlotTable slots = root.allocateSlots();
        assertEquals(1, slots.size()); // single (qkey, empty-prefix) slot for both Index(1)/Index(2)
        assertEquals("e_0", slots.iterator().next().getValue());
    }

    @Test void allocateSlots_skips_index_free_brackets() {
        TrieNode root = new TrieNode();
        TrieNode.insert(root, List.of(elem(null, "e")), scalarField("a"));
        assertEquals(0, root.allocateSlots().size());
    }

    private static PathSeg.Element elem(String ns, String name) {
        return new PathSeg.Element(ns, name, List.of());
    }

    private static PathSeg.Element elemWithBracket(String name, Predicate p) {
        return new PathSeg.Element(null, name, List.of(p));
    }

    private static FieldSpec scalarField(String name) {
        TypeRef str = TypeRef.of("java.lang", "String");
        return new FieldSpec(name, false, false, str, str, str, "java.lang.String",
                new Source.Attr(null, name), new Coerce.AsString());
    }

    private static FieldSpec nestedField(String name, String elemFq) {
        TypeRef ref = TypeRef.of(elemFq.substring(0, elemFq.lastIndexOf('.')),
                                 elemFq.substring(elemFq.lastIndexOf('.') + 1));
        return new FieldSpec(name, false, false, ref, ref, ref, elemFq,
                new Source.Child(List.of(elem(null, name)), false),
                new Coerce.Nested(elemFq));
    }
}
