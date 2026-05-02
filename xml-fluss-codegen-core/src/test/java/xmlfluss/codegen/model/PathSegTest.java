package xmlfluss.codegen.model;

import org.junit.jupiter.api.Test;
import xmlfluss.path.Predicate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PathSegTest {

    @Test
    void elementWithoutBrackets_defaultsToEmptyList() {
        PathSeg.Element e = new PathSeg.Element(null, "item");
        assertEquals("item", e.name());
        assertNull(e.ns());
        assertEquals(List.of(), e.brackets());
    }

    @Test
    void elementWithBrackets_carriesOrderedList() {
        Predicate p = new Predicate.Index(2);
        PathSeg.Element e = new PathSeg.Element(null, "item", List.of(p));
        assertEquals(List.of(p), e.brackets());
    }

    @Test
    void elementBracketsList_isImmutable() {
        Predicate p = new Predicate.Index(1);
        PathSeg.Element e = new PathSeg.Element(null, "item", List.of(p));
        // List.copyOf returns an unmodifiable list; mutation throws at runtime.
        assertThrows(UnsupportedOperationException.class,
                () -> e.brackets().add(new Predicate.Index(2)));
    }

    @Test
    void attrLeaf_holdsNsAndName() {
        PathSeg.AttrLeaf a = new PathSeg.AttrLeaf("urn:x", "id");
        assertEquals("urn:x", a.ns());
        assertEquals("id", a.name());
    }

    @Test
    void elementRejectsNullBrackets() {
        assertThrows(NullPointerException.class,
                () -> new PathSeg.Element(null, "item", null));
    }
}
