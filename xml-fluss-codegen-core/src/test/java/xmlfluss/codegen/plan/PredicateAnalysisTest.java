package xmlfluss.codegen.plan;

import org.junit.jupiter.api.Test;
import xmlfluss.path.Predicate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PredicateAnalysisTest {

    @Test void containsIndex_detects_direct_Index() {
        assertTrue(PredicateAnalysis.containsIndex(new Predicate.Index(2)));
    }

    @Test void containsIndex_walks_And() {
        Predicate p = new Predicate.And(new Predicate.Index(1),
                new Predicate.AttrEq(new xmlfluss.path.QName(null, "x"), "y", false));
        assertTrue(PredicateAnalysis.containsIndex(p));
    }

    @Test void prefixOfFirstIndex_returns_brackets_before_index() {
        Predicate attr = new Predicate.AttrEq(new xmlfluss.path.QName(null, "k"), "v", false);
        Predicate idx = new Predicate.Index(3);
        List<Predicate> brackets = List.of(attr, idx, attr);
        assertEquals(List.of(attr), PredicateAnalysis.prefixOfFirstIndex(brackets));
    }

    @Test void firstIndexValue_returns_n_from_compound() {
        Predicate folded = new Predicate.And(new Predicate.Index(7),
                new Predicate.AttrEq(new xmlfluss.path.QName(null, "x"), "y", false));
        assertEquals(7, PredicateAnalysis.firstIndexValue(folded));
    }

    @Test void stripIndex_removes_only_Index_arms() {
        Predicate attr = new Predicate.AttrEq(new xmlfluss.path.QName(null, "x"), "y", false);
        Predicate compound = new Predicate.And(new Predicate.Index(1), attr);
        assertEquals(attr, PredicateAnalysis.stripIndex(compound));
    }

    @Test void slotName_strips_unsafe_chars() {
        assertEquals("foo_bar_0", PredicateAnalysis.slotName(
                new xmlfluss.codegen.model.QKey(null, "foo:bar"), 0));
    }
}
