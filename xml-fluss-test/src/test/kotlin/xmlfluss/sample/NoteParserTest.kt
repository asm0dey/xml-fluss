package xmlfluss.sample

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import xmlfluss.XmlParseException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NoteParserTest {

    @Test
    fun parsesXmlLangAttr() = runTest {
        val xml = """
            <root xmlns:x="http://example.com/x">
              <note id="n1" xml:lang="en" x:tag="hot">
                <body>Hello</body>
              </note>
              <note id="n2" xml:lang="fr">
                <body>Bonjour</body>
              </note>
            </root>
        """.trimIndent()
        val notes = NoteParser.parse(xml.byteInputStream()).toList()
        assertEquals(2, notes.size)
        assertEquals(Note(id = "n1", lang = "en", tag = "hot", body = "Hello"), notes[0])
        assertEquals("n2", notes[1].id)
        assertEquals("fr", notes[1].lang)
        assertNull(notes[1].tag)
        assertEquals("Bonjour", notes[1].body)
    }

    @Test
    fun bareIdAttrIgnoresNamespacedDuplicate() = runTest {
        // a bogus xml:id alongside the bare id; only the bare one binds.
        val xml = """
            <root>
              <note id="real" xml:lang="en" xml:id="ghost">
                <body>x</body>
              </note>
            </root>
        """.trimIndent()
        val n = NoteParser.parse(xml.byteInputStream()).toList().single()
        assertEquals("real", n.id)
    }

    @Test
    fun missingNsAttrThrows() = runTest {
        val xml = """
            <root>
              <note id="n1"><body>x</body></note>
            </root>
        """.trimIndent()
        assertFailsWith<XmlParseException.Missing> {
            NoteParser.parse(xml.byteInputStream()).toList()
        }
    }

    @Test
    fun nsAttrOnNestedAndMap() = runTest {
        val xml = """
            <root xmlns:x="http://example.com/x">
              <doc id="d1">
                <title xml:lang="en">Greetings</title>
                <para xml:lang="en" x:role="lead"><text>Hi</text></para>
                <para xml:lang="de"><text>Hallo</text></para>
                <meta><inner x:flag="on"/></meta>
                <trans xml:lang="en" x:val="Hello"/>
                <trans xml:lang="fr" x:val="Bonjour"/>
              </doc>
            </root>
        """.trimIndent()
        val d = DocParser.parse(xml.byteInputStream()).toList().single()
        assertEquals("d1", d.id)
        assertEquals(listOf(Para("en", "lead", "Hi"), Para("de", null, "Hallo")), d.paras)
        assertEquals("en", d.titleLang)
        assertEquals("on", d.innerFlag)
        assertEquals(mapOf("en" to "Hello", "fr" to "Bonjour"), d.translations)
    }

    @Test
    fun nestedTypeRootInheritsParentNs() = runTest {
        // <x:sticker> root lives in parent's 'x' namespace; non-namespaced <sticker> ignored.
        val xml = """
            <root xmlns:x="http://example.com/x">
              <doc id="d1">
                <title xml:lang="en">x</title>
                <x:sticker id="s1"><x:label>Hot</x:label></x:sticker>
                <sticker id="ghost"><x:label>NoMatch</x:label></sticker>
                <x:sticker id="s2"><x:label>Cold</x:label></x:sticker>
              </doc>
            </root>
        """.trimIndent()
        val d = DocParser.parse(xml.byteInputStream()).toList().single()
        assertEquals(listOf(Sticker("s1", "Hot"), Sticker("s2", "Cold")), d.stickers)
    }

    @Test
    fun recordRootInNamespace() = runTest {
        // @XmlRecord("//x:feed") — record root itself namespaced; null-NS <feed> ignored.
        val xml = """
            <root xmlns:x="http://example.com/x">
              <feed id="ghost"/>
              <x:feed id="f1"/>
              <x:feed id="f2"/>
            </root>
        """.trimIndent()
        val feeds = FeedParser.parse(xml.byteInputStream()).toList()
        assertEquals(listOf(Feed("f1"), Feed("f2")), feeds)
    }

    @Test
    fun childAttrLeafMissingStaysNull() = runTest {
        val xml = """
            <root xmlns:x="http://example.com/x">
              <doc id="d1">
                <title xml:lang="en">x</title>
                <meta><inner/></meta>
              </doc>
            </root>
        """.trimIndent()
        val d = DocParser.parse(xml.byteInputStream()).toList().single()
        assertNull(d.innerFlag)
    }
}
