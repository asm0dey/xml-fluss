package xmlfluss.sample

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ChildPositionalTest {

    @Test
    fun leafPositionalSelectsSecondSibling() = runTest {
        val xml = """
            <feed>
              <item id="1"/>
              <item id="2"/>
              <item id="3"/>
            </feed>
        """.trimIndent()
        val out = FeedSecondItemParser.parse(xml.byteInputStream()).toList()
        assertEquals(1, out.size)
        assertEquals("2", out[0].secondItem.id)
    }

    @Test
    fun chainedMidSegmentPositional() = runTest {
        val xml = """
            <feed>
              <meta kind="page"><published>p1</published></meta>
              <meta kind="post"><published>P1</published></meta>
              <meta kind="page"><published>p2</published></meta>
              <meta kind="post"><published>P2</published></meta>
              <meta kind="post"><published>P3</published></meta>
            </feed>
        """.trimIndent()
        val out = FeedSecondPostMetaPublishedParser.parse(xml.byteInputStream()).toList()
        assertEquals(1, out.size)
        assertEquals("P2", out[0].published)
    }

    @Test
    fun everySegmentPositional() = runTest {
        val xml = """
            <doc>
              <section>
                <para><span>section1_para1_span1</span></para>
                <para><span>section1_para2_span1</span></para>
              </section>
              <section>
                <para><span>section2_para1_span1</span></para>
                <para><span>EXPECTED</span></para>
              </section>
            </doc>
        """.trimIndent()
        val out = DocSecondSectionSecondParaFirstSpanParser.parse(xml.byteInputStream()).toList()
        assertEquals(1, out.size)
        assertEquals("EXPECTED", out[0].text) // section[2] then para[2] then span[1]
    }

    @Test
    fun attrLeafWithChainedPositional() = runTest {
        val xml = """
            <entry>
              <link type="acquisition" href="A1"/>
              <link type="epub" href="E1"/>
              <link type="acquisition" href="A2"/>
              <link type="epub" href="E2"/>
              <link type="epub" href="E3"/>
            </entry>
        """.trimIndent()
        val out = EntrySecondEpubHrefParser.parse(xml.byteInputStream()).toList()
        assertEquals(1, out.size)
        assertEquals("E2", out[0].href)
    }

    @Test
    fun descendantWithDirectPositional() = runTest {
        val xml = """
            <root>
              <wrap>
                <x a="b"><y>X1Y1</y><y>X1Y2</y><y>X1Y3</y></x>
              </wrap>
              <x a="c"><y>SkipY1</y><y>SkipY2</y></x>
              <x a="b"><y>X2Y1</y><y>X2Y2</y></x>
            </root>
        """.trimIndent()
        val out = RootSecondYUnderMatchingXParser.parse(xml.byteInputStream()).toList()
        assertEquals(1, out.size)
        assertEquals(listOf("X1Y2", "X2Y2"), out[0].ys)
    }

    @Test
    fun positionalCounterResetsPerParent() = runTest {
        val xml = """
            <doc>
              <section><item>S1I1</item><item>S1I2</item><item>S1I3</item></section>
              <section><item>S2I1</item><item>S2I2</item></section>
            </doc>
        """.trimIndent()
        val out = DocSecondItemPerSectionParser.parse(xml.byteInputStream()).toList()
        assertEquals(1, out.size)
        assertEquals(listOf("S1I2", "S2I2"), out[0].items)
    }
}
