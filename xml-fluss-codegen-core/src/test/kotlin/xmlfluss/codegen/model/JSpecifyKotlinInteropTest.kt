package xmlfluss.codegen.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Pins Kotlin's view of core Java types: @NullMarked + @Nullable must surface as proper
 * nullable / non-null Kotlin types, not platform types. If this test compiles and runs,
 * the contract holds.
 */
class JSpecifyKotlinInteropTest {

    @Test
    fun qkey_nullableNamespace_seenAsNullable() {
        val key = QKey(null, "item")
        // ns is @Nullable String -> Kotlin sees String? (no NPE on access; safe-call legal)
        val ns: String? = key.ns
        assertEquals(null, ns)
        // local is non-null -> Kotlin sees String (assigning to non-null var works)
        val local: String = key.local
        assertEquals("item", local)
    }

    @Test
    fun typeRef_packageName_isNonNull() {
        val tr = TypeRef.of("java.util", "List")
        // packageName is non-null -> direct assignment to String compiles
        val pkg: String = tr.packageName
        assertEquals("java.util", pkg)
    }

    @Test
    fun nestedRegistry_get_isNullable() {
        val r = NestedRegistry()
        // get(...) returns @Nullable RecordSpec -> Kotlin sees RecordSpec?
        val absent: RecordSpec? = r.get("nope")
        assertEquals(null, absent)

        val spec = RecordSpec("p", "Foo", "", emptyMap(), emptyList(), null)
        r.put("p.Foo", spec)
        val present: RecordSpec? = r.get("p.Foo")
        assertNotNull(present)
        assertEquals("Foo", present.simpleName)
    }
}
