package xmlfluss.codegen.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NestedRegistryTest {

    @Test
    void helperName_unique_perFqn() {
        NestedRegistry r = new NestedRegistry();
        String a = r.helperName("com.example.Foo");
        String b = r.helperName("com.example.Foo");
        assertEquals(a, b);
        assertEquals("__parseNested_Foo", a);
    }

    @Test
    void helperName_disambiguatesDifferentFqnsWithSameSimpleName() {
        NestedRegistry r = new NestedRegistry();
        String a = r.helperName("a.Foo");
        String b = r.helperName("b.Foo");
        assertNotEquals(a, b);
        assertEquals("__parseNested_Foo", a);
        assertEquals("__parseNested_Foo_1", b);
    }

    @Test
    void inProgress_tracksClassificationCycles() {
        NestedRegistry r = new NestedRegistry();
        assertFalse(r.isInProgress("a.Foo"));
        r.markInProgress("a.Foo");
        assertTrue(r.isInProgress("a.Foo"));
        r.unmarkInProgress("a.Foo");
        assertFalse(r.isInProgress("a.Foo"));
    }

    @Test
    void put_then_get_roundTrip() {
        NestedRegistry r = new NestedRegistry();
        RecordSpec spec = new RecordSpec("p", "Foo", "", java.util.Map.of(),
                java.util.List.of(), null);
        r.put("p.Foo", spec);
        assertSame(spec, r.get("p.Foo"));
    }
}
