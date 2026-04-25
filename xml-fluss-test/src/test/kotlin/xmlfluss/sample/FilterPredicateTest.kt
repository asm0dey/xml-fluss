package xmlfluss.sample

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FilterPredicateTest {

    private val xml = """
        <library>
          <author role="main" active="true"><name>Alice</name></author>
          <author role="editor" active="false"><name>Bob</name></author>
          <author role="main" active="false"><name>Carol</name></author>
          <author role="contributor"><name>Dave</name></author>
        </library>
    """.trimIndent()

    @Test
    fun notEqualsPredicate() = runTest {
        val out = NotMainAuthorParser.parse(xml.byteInputStream()).toList()
        assertEquals(listOf("Bob", "Dave"), out.map { it.name })
        assertEquals(listOf("editor", "contributor"), out.map { it.role })
    }

    @Test
    fun andCompoundPredicate() = runTest {
        val out = ActiveMainAuthorParser.parse(xml.byteInputStream()).toList()
        assertEquals(listOf("Alice"), out.map { it.name })
    }

    @Test
    fun orCompoundPredicate() = runTest {
        val out = MainOrEditorParser.parse(xml.byteInputStream()).toList()
        assertEquals(listOf("Alice", "Bob", "Carol"), out.map { it.name })
        assertEquals(listOf("main", "editor", "main"), out.map { it.role })
    }

    @Test
    fun positionalPredicate() = runTest {
        val out = SecondAuthorParser.parse(xml.byteInputStream()).toList()
        assertEquals(listOf("Bob"), out.map { it.name })
    }

    private val notesXml = """
        <book>
          <note xml:lang="en"><text>hello</text></note>
          <note xml:lang="fr"><text>bonjour</text></note>
          <note xml:lang="de"><text>hallo</text></note>
          <note lang="en"><text>bareEn</text></note>
          <note xmlns:custom="http://example.com/custom" custom:lang="en"><text>customEn</text></note>
          <note><text>nolang</text></note>
        </book>
    """.trimIndent()

    @Test
    fun namespacedAttrPredicateEquals() = runTest {
        // Neither `lang="en"` (null-NS) nor `custom:lang="en"` (custom-NS, declared
        // inline on the element via xmlns:custom) match `[@xml:lang='en']` — predicate
        // qname resolves to xml-NS, attribute lookup is NS-keyed.
        val out = EnglishNoteParser.parse(notesXml.byteInputStream()).toList()
        assertEquals(listOf("hello"), out.map { it.text })
    }

    @Test
    fun namespacedAttrPredicateNotEquals() = runTest {
        // `!=` is true when the attribute is absent (v == null → eq false → !eq true),
        // so the unlabelled <note>, the bare `lang="en"` <note>, AND the inline-
        // namespaced `custom:lang="en"` <note> all match — none carries an `xml:lang`
        // attribute in the xml namespace.
        val out = NonEnglishNoteParser.parse(notesXml.byteInputStream()).toList()
        assertEquals(listOf("bonjour", "hallo", "bareEn", "customEn", "nolang"), out.map { it.text })
    }
}
