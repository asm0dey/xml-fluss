package xmlfluss.sample.japt

import xmlfluss.XmlParseException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Smoke + namespace coverage for the APT-generated [JNoteParser]. Mirrors the contract of
 * the KSP-generated `NoteParser` for Kotlin records; both processors should produce
 * functionally equivalent parsers.
 */
class JNoteParserTest {

    @Test
    fun parsesIdLangAndBody() {
        val xml = """
            <root>
              <note id="n1" xml:lang="en"><body>Hello</body></note>
              <note id="n2" xml:lang="fr"><body>Bonjour</body></note>
            </root>
        """.trimIndent()
        val notes = JNoteParser.parse(xml.byteInputStream())
            .toList()
        assertEquals(2, notes.size)
        assertEquals("n1", notes[0].id())
        assertEquals("en", notes[0].lang())
        assertEquals("Hello", notes[0].body())
        assertEquals("n2", notes[1].id())
        assertEquals("fr", notes[1].lang())
        assertEquals("Bonjour", notes[1].body())
    }

    @Test
    fun missingRequiredXmlLangThrows() {
        val xml = """
            <root>
              <note id="n1"><body>x</body></note>
            </root>
        """.trimIndent()
        assertFailsWith<XmlParseException.Missing> {
            JNoteParser.parse(xml.byteInputStream())
                .toList()
        }
    }

    @Test
    fun nullableBodyIsNullWhenAbsent() {
        val xml = """<root><note id="n1" xml:lang="en"/></root>"""
        val n = JNoteParser.parse(xml.byteInputStream()).toList().single()
        assertEquals("n1", n.id())
        assertNull(n.body())
    }
}
