package xmlfluss.sample

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ChainedPredicateTest {

    @Test
    fun chainedAttrAndPositionDispatchInRecordPath() = runTest {
        val xml = """
            <feed>
              <item id="1" kind="page"><title>Skip page</title></item>
              <item id="2" kind="post"><title>Skip — 1st post</title></item>
              <item id="3" kind="page"><title>Skip page again</title></item>
              <item id="4" kind="post"><title>Match — 2nd post</title></item>
              <item id="5" kind="post"><title>Skip — 3rd post</title></item>
            </feed>
        """.trimIndent()
        val out = SecondPostItemParser.parse(xml.byteInputStream()).toList()
        assertEquals(1, out.size)
        assertEquals("4", out[0].id)
        assertEquals("Match — 2nd post", out[0].title)
    }
}
