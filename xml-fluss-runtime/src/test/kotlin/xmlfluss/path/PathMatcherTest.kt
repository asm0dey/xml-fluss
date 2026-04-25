package xmlfluss.path

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Direct unit tests for [PathMatcher]. Drives `pushElement` / `popElement` with hand-built
 * [QName]s and an attribute-resolver lambda, bypassing real XML parsing.
 */
class PathMatcherTest {

    private fun compile(expr: String, ns: Map<String, String> = emptyMap()): CompiledPath =
        PathParser(nsResolve = { ns[it] }, defaultNs = ns[""]).parse(expr)

    private fun matcher(expr: String, ignoreNs: Boolean = false, ns: Map<String, String> = emptyMap()): PathMatcher =
        PathMatcher(compile(expr, ns), ignoreNs = ignoreNs)

    private fun qn(local: String, ns: String? = null): QName = QName(ns, local)

    private val noAttrs: (QName) -> String? = { null }

    @Test fun descendantSingleStep_matchesAtAnyDepth() {
        val m = matcher("//author")
        assertTrue(m.pushElement(qn("author"), noAttrs, 1))
        m.popElement()

        // depth 3: root/section/author
        m.pushElement(qn("root"), noAttrs, 1)
        m.pushElement(qn("section"), noAttrs, 1)
        assertTrue(m.pushElement(qn("author"), noAttrs, 1))
        m.popElement(); m.popElement(); m.popElement()
    }

    @Test fun absolutePath_onlyMatchesExactRootChain() {
        val m = matcher("/library/author")
        m.pushElement(qn("library"), noAttrs, 1)
        assertTrue(m.pushElement(qn("author"), noAttrs, 1))
        m.popElement()
        // deeper author should NOT match
        m.pushElement(qn("section"), noAttrs, 1)
        assertFalse(m.pushElement(qn("author"), noAttrs, 1))
        m.popElement(); m.popElement(); m.popElement()
    }

    @Test fun absolutePath_doesNotMatchAtWrongRoot() {
        val m = matcher("/library/author")
        m.pushElement(qn("notlibrary"), noAttrs, 1)
        assertFalse(m.pushElement(qn("author"), noAttrs, 1))
        m.popElement(); m.popElement()
    }

    @Test fun relativePath_autoDescendant_matchesAtAnyDepth() {
        val m = matcher("authors/author")
        // direct under root
        m.pushElement(qn("authors"), noAttrs, 1)
        assertTrue(m.pushElement(qn("author"), noAttrs, 1))
        m.popElement(); m.popElement()

        // nested deeper: root/section/authors/author
        m.pushElement(qn("root"), noAttrs, 1)
        m.pushElement(qn("section"), noAttrs, 1)
        m.pushElement(qn("authors"), noAttrs, 1)
        assertTrue(m.pushElement(qn("author"), noAttrs, 1))
        m.popElement(); m.popElement(); m.popElement(); m.popElement()
    }

    @Test fun predicateAttrEq_matchesWhenAttrEqual() {
        val m = matcher("//book[@id='42']")
        val attrs: (QName) -> String? = { if (it.local == "id") "42" else null }
        assertTrue(m.pushElement(qn("book"), attrs, 1))
        m.popElement()
    }

    @Test fun predicateAttrEq_failsWhenAttrAbsent() {
        val m = matcher("//book[@id='42']")
        assertFalse(m.pushElement(qn("book"), noAttrs, 1))
        m.popElement()
    }

    @Test fun predicateAttrEq_failsWhenAttrDifferent() {
        val m = matcher("//book[@id='42']")
        val attrs: (QName) -> String? = { if (it.local == "id") "7" else null }
        assertFalse(m.pushElement(qn("book"), attrs, 1))
        m.popElement()
    }

    @Test fun predicateAttrNotEq_matchesWhenAttrAbsent() {
        val m = matcher("//book[@x!='y']")
        assertTrue(m.pushElement(qn("book"), noAttrs, 1))
        m.popElement()
    }

    @Test fun predicateAttrNotEq_matchesWhenAttrPresentAndDifferent() {
        val m = matcher("//book[@x!='y']")
        val attrs: (QName) -> String? = { if (it.local == "x") "z" else null }
        assertTrue(m.pushElement(qn("book"), attrs, 1))
        m.popElement()
    }

    @Test fun predicateAttrNotEq_failsWhenAttrEqual() {
        val m = matcher("//book[@x!='y']")
        val attrs: (QName) -> String? = { if (it.local == "x") "y" else null }
        assertFalse(m.pushElement(qn("book"), attrs, 1))
        m.popElement()
    }

    @Test fun predicateIndex_matchesOnlyThirdSibling() {
        val m = matcher("//item[3]")
        assertFalse(m.pushElement(qn("item"), noAttrs, 1)); m.popElement()
        assertFalse(m.pushElement(qn("item"), noAttrs, 2)); m.popElement()
        assertTrue(m.pushElement(qn("item"), noAttrs, 3)); m.popElement()
        assertFalse(m.pushElement(qn("item"), noAttrs, 4)); m.popElement()
    }

    @Test fun predicateAnd_requiresBoth() {
        val m = matcher("//e[@a='1' and @b='2']")
        val both: (QName) -> String? = { if (it.local == "a") "1" else if (it.local == "b") "2" else null }
        val onlyA: (QName) -> String? = { if (it.local == "a") "1" else null }
        val onlyB: (QName) -> String? = { if (it.local == "b") "2" else null }
        assertTrue(m.pushElement(qn("e"), both, 1)); m.popElement()
        assertFalse(m.pushElement(qn("e"), onlyA, 1)); m.popElement()
        assertFalse(m.pushElement(qn("e"), onlyB, 1)); m.popElement()
        assertFalse(m.pushElement(qn("e"), noAttrs, 1)); m.popElement()
    }

    @Test fun predicateOr_requiresEither() {
        val m = matcher("//e[@a='1' or @b='2']")
        val both: (QName) -> String? = { if (it.local == "a") "1" else if (it.local == "b") "2" else null }
        val onlyA: (QName) -> String? = { if (it.local == "a") "1" else null }
        val onlyB: (QName) -> String? = { if (it.local == "b") "2" else null }
        assertTrue(m.pushElement(qn("e"), both, 1)); m.popElement()
        assertTrue(m.pushElement(qn("e"), onlyA, 1)); m.popElement()
        assertTrue(m.pushElement(qn("e"), onlyB, 1)); m.popElement()
        assertFalse(m.pushElement(qn("e"), noAttrs, 1)); m.popElement()
    }

    @Test fun namespaceMatch_explicitUri() {
        val m = matcher("//{atom}entry")
        assertTrue(m.pushElement(qn("entry", ns = "atom"), noAttrs, 1)); m.popElement()
        // wrong namespace
        assertFalse(m.pushElement(qn("entry", ns = "other"), noAttrs, 1)); m.popElement()
        // null namespace
        assertFalse(m.pushElement(qn("entry"), noAttrs, 1)); m.popElement()
    }

    @Test fun namespaceWildcard_matchesAnyNs() {
        val m = matcher("//{*}entry")
        assertTrue(m.pushElement(qn("entry", ns = "atom"), noAttrs, 1)); m.popElement()
        assertTrue(m.pushElement(qn("entry", ns = "rss"), noAttrs, 1)); m.popElement()
        assertTrue(m.pushElement(qn("entry", ns = null), noAttrs, 1)); m.popElement()
        // wrong local
        assertFalse(m.pushElement(qn("other", ns = "atom"), noAttrs, 1)); m.popElement()
    }

    @Test fun ignoreNsFlag_treatsAllNamespacesAsWildcard() {
        val m = matcher("//{atom}entry", ignoreNs = true)
        // even mismatched ns uri should match because ignoreNs is true
        assertTrue(m.pushElement(qn("entry", ns = "completelyDifferentNs"), noAttrs, 1)); m.popElement()
        assertTrue(m.pushElement(qn("entry", ns = null), noAttrs, 1)); m.popElement()
        // local mismatch still fails
        assertFalse(m.pushElement(qn("other", ns = "atom"), noAttrs, 1)); m.popElement()
    }

    @Test fun popAfterNoMatch_preservesPreviousState() {
        // matches /a/b
        val m = matcher("/a/b")
        m.pushElement(qn("a"), noAttrs, 1)
        // push a non-matching element under <a>
        assertFalse(m.pushElement(qn("z"), noAttrs, 1))
        m.popElement()
        // now <b> directly under <a> should still match — the failed push shouldn't
        // have damaged the stack-saved state at depth 1.
        assertTrue(m.pushElement(qn("b"), noAttrs, 1))
        m.popElement()
        m.popElement()
    }

    @Test fun matchedFlag_reflectsLastPush() {
        val m = matcher("//author")
        m.pushElement(qn("root"), noAttrs, 1)
        assertFalse(m.matched)
        m.pushElement(qn("author"), noAttrs, 1)
        assertTrue(m.matched)
        m.popElement()
        assertFalse(m.matched)
        m.popElement()
    }

    @Test fun returnFlagAndMatchedFlag_agree() {
        val m = matcher("//x")
        val r1 = m.pushElement(qn("y"), noAttrs, 1)
        assertEquals(r1, m.matched)
        m.popElement()
        val r2 = m.pushElement(qn("x"), noAttrs, 1)
        assertEquals(r2, m.matched)
        m.popElement()
    }
}
