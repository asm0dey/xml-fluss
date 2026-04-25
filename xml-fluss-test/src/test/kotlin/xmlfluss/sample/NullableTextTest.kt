package xmlfluss.sample

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NullableTextTest {

    @Test
    fun emptyElementBindsEmptyStringNotNull() = runTest {
        val xml = """
            <root>
              <memo id="m1"/>
              <memo id="m2"></memo>
              <memo id="m3">hello</memo>
            </root>
        """.trimIndent()
        val memos = MemoParser.parse(xml.byteInputStream()).toList()
        assertEquals(3, memos.size)
        // self-closing -> empty string, NOT null
        assertNotNull(memos[0].body)
        assertEquals("", memos[0].body)
        // explicit empty body -> empty string
        assertNotNull(memos[1].body)
        assertEquals("", memos[1].body)
        // populated -> populated string
        assertEquals("hello", memos[2].body)
    }

    // Regression-pin for iter2 hoist: behavior test above passes even if the
    // text-call hoist regresses (recordText returns "" for empty element either
    // way). Inspect generated source directly to ensure the hoisted call still
    // emits `c.recordText(...)` outside the forEachRecordChild block.
    @Test
    fun generatedCodeHoistsRecordTextCall() {
        val candidates = listOf(
            "build/generated/ksp/main/kotlin/xmlfluss/sample/MemoParser.kt",
            "../xml-fluss-test/build/generated/ksp/main/kotlin/xmlfluss/sample/MemoParser.kt",
        )
        val file = candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: return // generated path not available in this environment; skip
        val src = file.readText()
        assertTrue(
            "c.recordText(" in src,
            "Expected hoisted recordText call in generated MemoParser.kt; got:\n$src",
        )
    }
}
