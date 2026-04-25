package xmlfluss.path

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ClarkNotationTest {

    @Test
    fun parsesClarkUriDescendant() {
        val p = PathParser().parse("//{http://www.w3.org/2005/Atom}entry")
        val named = p.steps.last() as Step.Named
        assertEquals("http://www.w3.org/2005/Atom", named.name.ns)
        assertEquals("entry", named.name.local)
    }

    @Test
    fun parsesClarkWildcardNs() {
        val p = PathParser().parse("//{*}author")
        val named = p.steps.last() as Step.Named
        assertEquals(PathParser.WILDCARD, named.name.ns)
        assertEquals("author", named.name.local)
    }

    @Test
    fun parsesClarkEmptyUriAsNullNamespace() {
        val p = PathParser().parse("//{}bare")
        val named = p.steps.last() as Step.Named
        assertNull(named.name.ns)
        assertEquals("bare", named.name.local)
    }

    @Test
    fun parsesClarkWithPredicate() {
        val p = PathParser().parse("//{http://example.com/x}book[@id='42']")
        val named = p.steps.last() as Step.Named
        assertEquals("http://example.com/x", named.name.ns)
        assertEquals("book", named.name.local)
        val pred = named.predicate as Predicate.AttrEq
        assertEquals("42", pred.value)
    }

    @Test
    fun parsesClarkWithAttributeLeaf() {
        val p = PathParser().parse("//{http://example.com/x}book/@id")
        val leaf = p.attrLeaf!!
        assertEquals("id", leaf.name.local)
        val named = p.elementSteps.last() as Step.Named
        assertEquals("http://example.com/x", named.name.ns)
        assertEquals("book", named.name.local)
    }

    @Test
    fun parsesAbsoluteClarkPath() {
        val p = PathParser().parse("/{urn:a}root/{urn:a}child")
        val steps = p.elementSteps.filterIsInstance<Step.Named>()
        assertEquals(2, steps.size)
        assertEquals("urn:a", steps[0].name.ns)
        assertEquals("root", steps[0].name.local)
        assertEquals("urn:a", steps[1].name.ns)
        assertEquals("child", steps[1].name.local)
    }

    @Test
    fun rejectsUnterminatedClarkBrace() {
        assertFailsWith<PathParseException> { PathParser().parse("//{http://x.example.com/entry") }
    }

    @Test
    fun emptyPathThrowsPathParseException() {
        assertFailsWith<PathParseException> { PathParser().parse("") }
        assertFailsWith<PathParseException> { PathParser().parse("   ") }
    }

    @Test
    fun parsesClarkAttributeInPredicate() {
        val p = PathParser().parse("//book[@{http://example.com/x}id='42']")
        val named = p.steps.last() as Step.Named
        assertEquals("book", named.name.local)
        val pred = named.predicate as Predicate.AttrEq
        assertEquals("http://example.com/x", pred.name.ns)
        assertEquals("id", pred.name.local)
        assertEquals("42", pred.value)
    }
}
