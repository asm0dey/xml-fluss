package xmlfluss.codegen.model;

import org.junit.jupiter.api.Test;
import xmlfluss.path.Predicate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EdgeKeyTest {

    @Test
    void equalsIsStructural_includingBrackets() {
        QKey k = new QKey(null, "item");
        Predicate p1 = new Predicate.Index(1);
        EdgeKey a = new EdgeKey(k, List.of(p1));
        EdgeKey b = new EdgeKey(k, List.of(new Predicate.Index(1)));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void differentBracketOrder_isNotEqual() {
        QKey k = new QKey(null, "item");
        Predicate p1 = new Predicate.Index(1);
        Predicate p2 = new Predicate.Index(2);
        EdgeKey a = new EdgeKey(k, List.of(p1, p2));
        EdgeKey b = new EdgeKey(k, List.of(p2, p1));
        assertNotEquals(a, b);
    }

    @Test
    void rejectsNullBrackets() {
        assertThrows(NullPointerException.class,
                () -> new EdgeKey(new QKey(null, "x"), null));
    }
}
