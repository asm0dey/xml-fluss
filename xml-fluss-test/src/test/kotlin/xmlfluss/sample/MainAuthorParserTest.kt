package xmlfluss.sample

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MainAuthorParserTest {

    @Test
    fun predicateFiltersByAttribute() = runTest {
        val xml = """
            <r>
              <author role="main"><name>A</name></author>
              <author role="aux"><name>B</name></author>
              <author><name>C</name></author>
              <author role="main"><name>D</name></author>
            </r>
        """.trimIndent()
        val mains = MainAuthorParser.parse(xml.byteInputStream()).toList()
        assertEquals(listOf("A", "D"), mains.map { it.name })
    }
}
