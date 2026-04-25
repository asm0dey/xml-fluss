package xmlfluss.sample

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IgnoreNamespaceTest {

    private val bareXml = """
        <feed>
          <entry id="1"><title>Hello</title><summary>One</summary></entry>
          <entry id="2"><title>World</title></entry>
        </feed>
    """.trimIndent()

    private val wrongNsXml = """
        <feed xmlns="http://example.com/wrong">
          <entry id="1"><title>Hello</title><summary>One</summary></entry>
          <entry id="2"><title>World</title></entry>
        </feed>
    """.trimIndent()

    @Test
    fun defaultParseHonoursNamespaceBinding() = runTest {
        // Sample expects atom-NS entries; bare doc has no NS → no matches.
        val out = EntryParser.parse(bareXml.byteInputStream()).toList()
        assertEquals(emptyList(), out)
    }

    @Test
    fun ignoreNamespaceMatchesBareDocument() = runTest {
        val out = EntryParser.parse(bareXml.byteInputStream(), ignoreNamespace = true).toList()
        assertEquals(2, out.size)
        assertEquals("1", out[0].id)
        assertEquals("Hello", out[0].title)
        assertEquals("One", out[0].summary)
        assertEquals("2", out[1].id)
        assertEquals("World", out[1].title)
        assertEquals(null, out[1].summary)
    }

    @Test
    fun ignoreNamespaceMatchesAcrossDifferentNamespace() = runTest {
        // Sample expects atom-NS; doc puts everything in a wrong default NS — still parses.
        val out = EntryParser.parse(wrongNsXml.byteInputStream(), ignoreNamespace = true).toList()
        assertEquals(listOf("Hello", "World"), out.map { it.title })
    }

    @Test
    fun ignoreNamespaceWorksWithNamespacedAttrPredicate() = runTest {
        // EnglishNote's record path has `[@xml:lang='en']`. With ignoreNamespace, the attr
        // namespace is dropped — so a bare `lang="en"` attribute matches.
        val xml = """
            <book>
              <note lang="en"><text>hello</text></note>
              <note lang="fr"><text>bonjour</text></note>
            </book>
        """.trimIndent()
        val out = EnglishNoteParser.parse(xml.byteInputStream(), ignoreNamespace = true).toList()
        assertEquals(listOf("hello"), out.map { it.text })
    }

    @Test
    fun parseSignatureKeepsBackwardCompatibleDefault() = runTest {
        // ignoreNamespace defaults to false — `parse(input)` still works for callers that
        // don't pass the flag.
        val out = EntryParser.parse(bareXml.byteInputStream()).toList()
        assertTrue(out.isEmpty())
    }
}
