package xmlfluss.sample

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MidPathPredicateTest {

    @Test
    fun predicateOnMidSegmentDispatches() = runTest {
        val xml = """
            <doc>
              <wrapper>
                <section kind="intro">
                  <title>Welcome</title>
                  <note>not collected</note>
                </section>
                <section kind="body">
                  <title>Main Body</title>
                  <note>n1</note>
                  <note>n2</note>
                </section>
                <section kind="appendix">
                  <title>Skipped</title>
                </section>
              </wrapper>
            </doc>
        """.trimIndent()
        val out = MidPathDocParser.parse(xml.byteInputStream()).toList().single()
        assertEquals("Welcome", out.introTitle)
        assertEquals("Main Body", out.bodyTitle)
        assertEquals(listOf("n1", "n2"), out.bodyNotes)
    }
}
