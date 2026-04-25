package xmlfluss.sample

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EmbeddedXmlTest {

    @Test
    fun parsesXmlEmbeddedInsideCdataOfOuterXml() = runTest {
        val outer = """
            <root>
              <envelope id="e1"><payload><![CDATA[<wire>
                <msg id="m1"><body>hello</body></msg>
                <msg id="m2"><body>world</body></msg>
              </wire>]]></payload></envelope>
            </root>
        """.trimIndent()

        val envelope = EnvelopeParser.parse(outer.byteInputStream()).toList().single()
        assertEquals("e1", envelope.id)

        val inner = EmbeddedMsgParser.parse(envelope.payload.byteInputStream()).toList()
        assertEquals(
            listOf(
                EmbeddedMsg(id = "m1", body = "hello"),
                EmbeddedMsg(id = "m2", body = "world"),
            ),
            inner,
        )
    }

    @Test
    fun cdataConcatenatedWithSurroundingTextRoundTrips() = runTest {
        // Producer split inner doc across CDATA + plain text + second CDATA. Aalto coalesces
        // them back into a single payload string; inner parser then sees a normal document.
        val outer = """
            <root>
              <envelope id="e2"><payload><![CDATA[<wire><msg id="x1"><body>a]]>b<![CDATA[c</body></msg></wire>]]></payload></envelope>
            </root>
        """.trimIndent()

        val envelope = EnvelopeParser.parse(outer.byteInputStream()).toList().single()
        val inner = EmbeddedMsgParser.parse(envelope.payload.byteInputStream()).toList().single()
        assertEquals(EmbeddedMsg(id = "x1", body = "abc"), inner)
    }

    @Test
    fun multipleEnvelopesEachWithEmbeddedRecords() = runTest {
        val outer = """
            <root>
              <envelope id="e1"><payload><![CDATA[<wire><msg id="m1"><body>one</body></msg></wire>]]></payload></envelope>
              <envelope id="e2"><payload><![CDATA[<wire>
                <msg id="m2"><body>two</body></msg>
                <msg id="m3"><body>three</body></msg>
              </wire>]]></payload></envelope>
            </root>
        """.trimIndent()

        val envelopes = EnvelopeParser.parse(outer.byteInputStream()).toList()
        assertEquals(listOf("e1", "e2"), envelopes.map { it.id })

        val all = envelopes.flatMap { env ->
            EmbeddedMsgParser.parse(env.payload.byteInputStream()).toList()
        }
        assertEquals(
            listOf(
                EmbeddedMsg("m1", "one"),
                EmbeddedMsg("m2", "two"),
                EmbeddedMsg("m3", "three"),
            ),
            all,
        )
    }
}
