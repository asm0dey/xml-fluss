package xmlfluss.sample

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PositionalPredicateTest {

    @Test
    fun testSecondItem() = runTest {
        val xml = """
            <root>
                <item id="1"><name>First</name></item>
                <item id="2"><name>Second</name></item>
                <item id="3"><name>Third</name></item>
                <group>
                    <item id="4"><name>GroupFirst</name></item>
                    <item id="5"><name>GroupSecond</name></item>
                </group>
            </root>
        """.trimIndent()
        
        val items = SecondItemParser.parse(xml.byteInputStream()).toList()
        assertEquals(2, items.size)
        assertEquals("2", items[0].id)
        assertEquals("Second", items[0].name)
        assertEquals("5", items[1].id)
        assertEquals("GroupSecond", items[1].name)
    }
}
