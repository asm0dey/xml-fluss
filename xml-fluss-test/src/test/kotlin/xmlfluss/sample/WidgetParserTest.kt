package xmlfluss.sample

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WidgetParserTest {

    @Test
    fun bareAnnotationsUseFieldNames() = runTest {
        val xml = """
            <root>
              <widget id="w1" color="red">
                <name>Alpha</name>
                <tags>x</tags>
                <tags>y</tags>
                <note>first</note>
                <knob id="k0"><label>Main</label></knob>
                <knobs id="k1"><label>One</label></knobs>
                <knobs id="k2"><label>Two</label></knobs>
              </widget>
              <widget id="w2">
                <name>Beta</name>
              </widget>
            </root>
        """.trimIndent()
        val widgets = WidgetParser.parse(xml.byteInputStream()).toList()
        assertEquals(2, widgets.size)
        assertEquals(
            Widget(
                id = "w1",
                color = "red",
                name = "Alpha",
                tags = listOf("x", "y"),
                note = "first",
                knob = Knob("k0", "Main"),
                knobs = listOf(Knob("k1", "One"), Knob("k2", "Two")),
            ),
            widgets[0],
        )
        val w2 = widgets[1]
        assertEquals("w2", w2.id)
        assertNull(w2.color)
        assertEquals("Beta", w2.name)
        assertEquals(emptyList(), w2.tags)
        assertNull(w2.note)
        assertNull(w2.knob)
        assertEquals(emptyList(), w2.knobs)
    }
}
