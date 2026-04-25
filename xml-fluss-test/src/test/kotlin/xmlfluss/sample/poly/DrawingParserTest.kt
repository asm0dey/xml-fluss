package xmlfluss.sample.poly

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DrawingParserTest {

    @Test
    fun parsesTagAndAttrPolymorphism() = runTest {
        val xml = """
            <gallery>
              <drawing id="1">
                <circle r="2.5"/>
                <event type="login" user="alice">hi there</event>
                <square side="3.0"/>
                <event type="logout" user="alice"/>
                <triangle base="4.0" height="5.0"><label>tri</label></triangle>
                <highlight type="login" user="bob">welcome</highlight>
                <primary type="logout" user="bob"/>
                <event type="bogus" user="zzz"/>
              </drawing>
              <drawing id="2">
                <primary type="login" user="root">hello</primary>
              </drawing>
            </gallery>
        """.trimIndent()

        val drawings = DrawingParser.parse(xml.byteInputStream()).toList()
        assertEquals(2, drawings.size)

        val d1 = drawings[0]
        assertEquals(1, d1.id)
        assertEquals(3, d1.shapes.size)
        assertIs<Shape.Circle>(d1.shapes[0]).also { assertEquals(2.5, it.r) }
        assertIs<Shape.Square>(d1.shapes[1]).also { assertEquals(3.0, it.side) }
        assertIs<Shape.Triangle>(d1.shapes[2]).also {
            assertEquals(4.0, it.base)
            assertEquals(5.0, it.height)
            assertEquals("tri", it.label)
        }
        assertEquals(2, d1.events.size)
        assertIs<Event.Login>(d1.events[0]).also {
            assertEquals("alice", it.user)
            assertEquals("hi there", it.msg)
        }
        assertIs<Event.Logout>(d1.events[1]).also { assertEquals("alice", it.user) }
        val hl = d1.highlight
        assertNotNull(hl)
        assertIs<Event.Login>(hl).also {
            assertEquals("bob", it.user)
            assertEquals("welcome", it.msg)
        }
        assertIs<Event.Logout>(d1.primary).also { assertEquals("bob", it.user) }

        val d2 = drawings[1]
        assertEquals(2, d2.id)
        assertEquals(emptyList(), d2.shapes)
        assertEquals(emptyList(), d2.events)
        assertNull(d2.highlight)
        assertIs<Event.Login>(d2.primary).also {
            assertEquals("root", it.user)
            assertEquals("hello", it.msg)
        }
    }
}
