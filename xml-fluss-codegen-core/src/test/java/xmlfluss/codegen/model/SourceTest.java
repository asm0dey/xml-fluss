package xmlfluss.codegen.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SourceTest {

    @Test
    void child_rejectsEmptySegments() {
        assertThrows(IllegalArgumentException.class,
                () -> new Source.Child(List.of(), false));
    }

    @Test
    void child_rejectsAttrLeafAsHead() {
        PathSeg.AttrLeaf leaf = new PathSeg.AttrLeaf(null, "id");
        assertThrows(IllegalArgumentException.class,
                () -> new Source.Child(List.of(leaf), false));
    }

    @Test
    void child_acceptsElementHead_returnsItViaHead() {
        PathSeg.Element head = new PathSeg.Element(null, "root");
        Source.Child c = new Source.Child(List.of(head), false);
        assertSame(head, c.head());
        assertFalse(c.descendant());
    }

    @Test
    void child_acceptsElementHeadFollowedByAttrLeaf() {
        PathSeg.Element head = new PathSeg.Element(null, "book");
        PathSeg.AttrLeaf leaf = new PathSeg.AttrLeaf(null, "id");
        Source.Child c = new Source.Child(List.of(head, leaf), true);
        assertEquals(2, c.segments().size());
        assertTrue(c.descendant());
    }

    @Test
    void attr_holdsNamespaceAndName() {
        Source.Attr a = new Source.Attr("urn:x", "id");
        assertEquals("urn:x", a.ns());
        assertEquals("id", a.name());
    }

    @Test
    void attr_rejectsNullName() {
        assertThrows(NullPointerException.class, () -> new Source.Attr(null, null));
    }

    @Test
    void mapEntry_rejectsNullLocal() {
        assertThrows(NullPointerException.class, () -> new Source.MapEntry(null, null));
    }

    @Test
    void polyChild_rejectsNullDispatch() {
        assertThrows(NullPointerException.class, () -> new Source.PolyChild(null));
    }

    @Test
    void text_holdsPreserveWhitespaceFlag() {
        assertTrue(new Source.Text(true).preserveWhitespace());
        assertFalse(new Source.Text(false).preserveWhitespace());
    }
}
