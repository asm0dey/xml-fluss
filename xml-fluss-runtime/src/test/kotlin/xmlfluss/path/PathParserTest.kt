package xmlfluss.path

import kotlin.test.*

class PathParserTest {
    private fun parse(expr: String, ns: Map<String, String> = emptyMap()): CompiledPath =
        PathParser(nsResolve = { ns[it] }, defaultNs = ns[""]).parse(expr)

    @Test fun absolute_singleSlash_noLeadingDescendant() {
        val cp = parse("/a/b")
        assertTrue(cp.absolute)
        assertEquals(2, cp.steps.size)
        assertTrue(cp.steps[0] is Step.Named && (cp.steps[0] as Step.Named).name.local == "a")
        assertTrue(cp.steps[1] is Step.Named && (cp.steps[1] as Step.Named).name.local == "b")
    }

    @Test fun relative_autoPrependsDescendant() {
        val cp = parse("a/b")
        assertEquals(false, cp.absolute)
        assertTrue(cp.steps.first() is Step.Descendant)
        assertEquals(3, cp.steps.size)
    }

    @Test fun doubleSlashLeading_descendantPresent() {
        val cp = parse("//a/b")
        assertTrue(cp.absolute)
        assertTrue(cp.steps.first() is Step.Descendant)
    }

    @Test fun predicate_attrEq() {
        val cp = parse("//book[@id='1']")
        val pred = (cp.steps.last() as Step.Named).predicate
        val ae = pred as Predicate.AttrEq
        assertEquals("id", ae.name.local)
        assertEquals("1", ae.value)
        assertEquals(false, ae.negate)
    }

    @Test fun predicate_attrNotEq() {
        val cp = parse("//book[@id!='1']")
        val ae = (cp.steps.last() as Step.Named).predicate as Predicate.AttrEq
        assertEquals(true, ae.negate)
    }

    @Test fun predicate_index() {
        val cp = parse("//book[3]")
        val idx = (cp.steps.last() as Step.Named).predicate as Predicate.Index
        assertEquals(3, idx.n)
    }

    @Test fun predicate_and() {
        val cp = parse("//book[@a='1' and @b='2']")
        val p = (cp.steps.last() as Step.Named).predicate
        assertTrue(p is Predicate.And)
    }

    @Test fun predicate_or() {
        val cp = parse("//book[@a='1' or @b='2']")
        val p = (cp.steps.last() as Step.Named).predicate
        assertTrue(p is Predicate.Or)
    }

    @Test fun predicate_chainedBracketsAreImplicitAnd() {
        val cp = parse("//book[@a='1'][@b='2']")
        val p = (cp.steps.last() as Step.Named).predicate
        assertTrue(p is Predicate.And)
        val and = p as Predicate.And
        val l = and.l as Predicate.AttrEq
        val r = and.r as Predicate.AttrEq
        assertEquals("a", l.name.local)
        assertEquals("1", l.value)
        assertEquals("b", r.name.local)
        assertEquals("2", r.value)
    }

    @Test fun predicate_chainedAttrAndPosition() {
        val cp = parse("//entry[@kind='post'][3]")
        val p = (cp.steps.last() as Step.Named).predicate as Predicate.And
        assertTrue(p.l is Predicate.AttrEq)
        val r = p.r
        assertTrue(r is Predicate.Index)
        assertEquals(3, r.n)
    }

    @Test fun predicate_missingClose_throws() {
        assertFailsWith<PathParseException> { parse("//book[@a='1'") }
    }

    @Test fun predicate_missingEquals_throws() {
        assertFailsWith<PathParseException> { parse("//book[@a 'v']") }
    }

    @Test fun predicate_unterminatedString_throws() {
        assertFailsWith<PathParseException> { parse("//book[@a='v]") }
    }

    @Test fun predicate_empty_throws() {
        assertFailsWith<PathParseException> { parse("//book[]") }
    }

    @Test fun attrLeaf_midPath_rejected() {
        assertFailsWith<PathParseException> { parse("//book/@id/x") }
    }

    @Test fun attrLeaf_lastAccepted() {
        val cp = parse("//book/@id")
        assertNotNull(cp.attrLeaf)
        assertEquals("id", cp.attrLeaf.name.local)
        assertEquals(1, cp.elementSteps.count { it is Step.Named })
    }

    @Test fun attrLeaf_firstSegmentAccepted() {
        val cp = parse("@a")
        assertNotNull(cp.attrLeaf)
        assertEquals("a", cp.attrLeaf.name.local)
        // null ns for null-prefix attribute
        assertNull(cp.attrLeaf.name.ns)
    }

    @Test fun prefixQname_resolvedByNsResolver() {
        val cp = parse("//atom:entry", mapOf("atom" to "http://www.w3.org/2005/Atom"))
        val n = cp.steps.last() as Step.Named
        assertEquals("http://www.w3.org/2005/Atom", n.name.ns)
        assertEquals("entry", n.name.local)
    }

    @Test fun unboundPrefix_throws() {
        assertFailsWith<PathParseException> { parse("//atom:entry") }
    }

    @Test fun nestedBrackets_throws() {
        assertFailsWith<PathParseException> { parse("//book[[@a='1']]") }
    }

    @Test fun equalsWithoutAt_throws() {
        assertFailsWith<PathParseException> { parse("//book[='1']") }
    }

    @Test fun emptyExpression_throws() {
        assertFailsWith<PathParseException> { parse("") }
    }
}
