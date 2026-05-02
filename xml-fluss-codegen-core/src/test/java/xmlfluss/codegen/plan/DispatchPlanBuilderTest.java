package xmlfluss.codegen.plan;

import org.junit.jupiter.api.Test;
import xmlfluss.codegen.model.Coerce;
import xmlfluss.codegen.model.FieldSpec;
import xmlfluss.codegen.model.NestedRegistry;
import xmlfluss.codegen.model.PathSeg;
import xmlfluss.codegen.model.QKey;
import xmlfluss.codegen.model.RecordSpec;
import xmlfluss.codegen.model.Source;
import xmlfluss.codegen.model.TypeRef;
import xmlfluss.path.Predicate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DispatchPlanBuilderTest {

    @Test
    void empty_record_yields_empty_plan() {
        RecordSpec rs = recordSpec("Empty", List.of());
        DispatchPlan plan = DispatchPlanBuilder.build(rs, new NestedRegistry());
        assertTrue(plan.directRoot().isEmpty());
        assertTrue(plan.descendantByHead().isEmpty());
        assertTrue(plan.mapPlans().isEmpty());
        assertTrue(plan.polyFields().isEmpty());
        assertEquals(0, plan.slots().size());
        assertTrue(plan.tailTries().isEmpty());
    }

    @Test
    void direct_child_field_lands_in_directRoot() {
        FieldSpec f = childField("title", false, "title");
        RecordSpec rs = recordSpec("Article", List.of(f));
        DispatchPlan plan = DispatchPlanBuilder.build(rs, new NestedRegistry());
        assertEquals(1, plan.directRoot().children().size());
        assertTrue(plan.descendantByHead().isEmpty());
    }

    @Test
    void descendant_field_lands_in_byHead_and_tailTries() {
        FieldSpec f = childField("subtitle", true, "title", "sub");
        RecordSpec rs = recordSpec("Article", List.of(f));
        DispatchPlan plan = DispatchPlanBuilder.build(rs, new NestedRegistry());
        assertTrue(plan.directRoot().isEmpty());
        QKey head = new QKey(null, "title");
        assertEquals(1, plan.descendantByHead().get(head).size());
        assertNotNull(plan.tailTries().get(head));
    }

    @Test
    void slots_allocated_for_indexed_brackets() {
        FieldSpec f1 = childWithBrackets("a", "item", new Predicate.Index(1));
        FieldSpec f2 = childWithBrackets("b", "item", new Predicate.Index(2));
        DispatchPlan plan = DispatchPlanBuilder.build(
                recordSpec("Article", List.of(f1, f2)), new NestedRegistry());
        assertEquals(1, plan.slots().size());
    }

    @Test
    void fqn_combines_package_and_simple_name() {
        RecordSpec rs = recordSpec("Article", List.of());
        DispatchPlan plan = DispatchPlanBuilder.build(rs, new NestedRegistry());
        assertEquals("com.ex.Article", plan.fqn());
    }

    @Test
    void byFq_holds_every_nested_record_plan() {
        RecordSpec nested = recordSpec("Body", List.of());
        NestedRegistry reg = new NestedRegistry();
        reg.put("com.ex.Body", nested);
        RecordSpec top = recordSpec("Article", List.of());
        DispatchPlan plan = DispatchPlanBuilder.build(top, reg);
        Map<String, DispatchPlan> byFq = plan.byFq();
        assertTrue(byFq.containsKey("com.ex.Body"));
        assertEquals("com.ex.Body", byFq.get("com.ex.Body").fqn());
        assertFalse(byFq.containsKey("com.ex.Article"),
                "top-level plan should not appear in its own byFq");
    }

    @Test
    void nested_plan_shares_top_level_byFq_view() {
        RecordSpec a = recordSpec("A", List.of());
        RecordSpec b = recordSpec("B", List.of());
        NestedRegistry reg = new NestedRegistry();
        reg.put("com.ex.A", a);
        reg.put("com.ex.B", b);
        RecordSpec top = recordSpec("Top", List.of());
        DispatchPlan plan = DispatchPlanBuilder.build(top, reg);
        DispatchPlan nestedA = plan.byFq().get("com.ex.A");
        assertNotNull(nestedA);
        assertTrue(nestedA.byFq().containsKey("com.ex.B"),
                "every plan in the graph must see every sibling plan via byFq");
    }

    @Test
    void mapPlan_descendant_with_tail_yields_tail_trie() {
        FieldSpec keyF = childField("k", false, "k");
        FieldSpec valF = childField("v", true, "wrap", "leaf");
        FieldSpec mapField = mapField("entries", "entry", keyF, valF);
        RecordSpec rs = recordSpec("Article", List.of(mapField));
        DispatchPlan plan = DispatchPlanBuilder.build(rs, new NestedRegistry());

        MapPlan mp = plan.mapPlans().get(mapField);
        assertNotNull(mp);
        QKey head = new QKey(null, "wrap");
        assertEquals(1, mp.descendantByHead().get(head).size());
        TailTrie tt = mp.tailTries().get(head);
        assertNotNull(tt);
        assertNotNull(tt.slots());
        assertFalse(tt.trie().isEmpty());
    }

    @Test
    void mapPlan_descendant_with_single_segment_skips_tail_trie() {
        FieldSpec keyF = childField("k", false, "k");
        FieldSpec valF = childField("v", true, "leaf");
        FieldSpec mapField = mapField("entries", "entry", keyF, valF);
        RecordSpec rs = recordSpec("Article", List.of(mapField));
        DispatchPlan plan = DispatchPlanBuilder.build(rs, new NestedRegistry());

        MapPlan mp = plan.mapPlans().get(mapField);
        assertNotNull(mp);
        QKey head = new QKey(null, "leaf");
        assertEquals(1, mp.descendantByHead().get(head).size());
        assertNull(mp.tailTries().get(head));
        assertTrue(mp.tailTries().isEmpty());
    }

    private static FieldSpec mapField(String name, String entryLocal, FieldSpec keyF, FieldSpec valF) {
        TypeRef str = TypeRef.of("java.lang", "String");
        TypeRef mapType = TypeRef.of("java.util", "Map");
        return new FieldSpec(name, false, false, mapType, mapType, mapType, "java.util.Map",
                new Source.MapEntry(null, entryLocal), new Coerce.MapAggregate(),
                keyF, valF);
    }

    private static RecordSpec recordSpec(String simpleName, List<FieldSpec> fields) {
        return new RecordSpec("com.ex", simpleName, "", Map.of(), fields, null);
    }

    private static PathSeg.Element elem(String ns, String name) {
        return new PathSeg.Element(ns, name, List.of());
    }

    private static FieldSpec childField(String name, boolean descendant, String... segs) {
        TypeRef str = TypeRef.of("java.lang", "String");
        PathSeg.Element[] elems = new PathSeg.Element[segs.length];
        for (int i = 0; i < segs.length; i++) {
            elems[i] = elem(null, segs[i]);
        }
        return new FieldSpec(name, false, false, str, str, str, "java.lang.String",
                new Source.Child(List.of(elems), descendant), new Coerce.AsString());
    }

    private static FieldSpec childWithBrackets(String name, String segName, Predicate predicate) {
        TypeRef str = TypeRef.of("java.lang", "String");
        return new FieldSpec(name, false, false, str, str, str, "java.lang.String",
                new Source.Child(List.of(new PathSeg.Element(null, segName, List.of(predicate))), false),
                new Coerce.AsString());
    }
}
