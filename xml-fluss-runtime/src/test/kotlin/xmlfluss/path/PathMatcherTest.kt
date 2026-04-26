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
        assertTrue(m.pushElement(qn("author"), noAttrs))
        m.popElement()

        // depth 3: root/section/author
        m.pushElement(qn("root"), noAttrs)
        m.pushElement(qn("section"), noAttrs)
        assertTrue(m.pushElement(qn("author"), noAttrs))
        m.popElement(); m.popElement(); m.popElement()
    }

    @Test fun absolutePath_onlyMatchesExactRootChain() {
        val m = matcher("/library/author")
        m.pushElement(qn("library"), noAttrs)
        assertTrue(m.pushElement(qn("author"), noAttrs))
        m.popElement()
        // deeper author should NOT match
        m.pushElement(qn("section"), noAttrs)
        assertFalse(m.pushElement(qn("author"), noAttrs))
        m.popElement(); m.popElement(); m.popElement()
    }

    @Test fun absolutePath_doesNotMatchAtWrongRoot() {
        val m = matcher("/library/author")
        m.pushElement(qn("notlibrary"), noAttrs)
        assertFalse(m.pushElement(qn("author"), noAttrs))
        m.popElement(); m.popElement()
    }

    @Test fun relativePath_autoDescendant_matchesAtAnyDepth() {
        val m = matcher("authors/author")
        // direct under root
        m.pushElement(qn("authors"), noAttrs)
        assertTrue(m.pushElement(qn("author"), noAttrs))
        m.popElement(); m.popElement()

        // nested deeper: root/section/authors/author
        m.pushElement(qn("root"), noAttrs)
        m.pushElement(qn("section"), noAttrs)
        m.pushElement(qn("authors"), noAttrs)
        assertTrue(m.pushElement(qn("author"), noAttrs))
        m.popElement(); m.popElement(); m.popElement(); m.popElement()
    }

    @Test fun predicateAttrEq_matchesWhenAttrEqual() {
        val m = matcher("//book[@id='42']")
        val attrs: (QName) -> String? = { if (it.local == "id") "42" else null }
        assertTrue(m.pushElement(qn("book"), attrs))
        m.popElement()
    }

    @Test fun predicateAttrEq_failsWhenAttrAbsent() {
        val m = matcher("//book[@id='42']")
        assertFalse(m.pushElement(qn("book"), noAttrs))
        m.popElement()
    }

    @Test fun predicateAttrEq_failsWhenAttrDifferent() {
        val m = matcher("//book[@id='42']")
        val attrs: (QName) -> String? = { if (it.local == "id") "7" else null }
        assertFalse(m.pushElement(qn("book"), attrs))
        m.popElement()
    }

    @Test fun predicateAttrNotEq_matchesWhenAttrAbsent() {
        val m = matcher("//book[@x!='y']")
        assertTrue(m.pushElement(qn("book"), noAttrs))
        m.popElement()
    }

    @Test fun predicateAttrNotEq_matchesWhenAttrPresentAndDifferent() {
        val m = matcher("//book[@x!='y']")
        val attrs: (QName) -> String? = { if (it.local == "x") "z" else null }
        assertTrue(m.pushElement(qn("book"), attrs))
        m.popElement()
    }

    @Test fun predicateAttrNotEq_failsWhenAttrEqual() {
        val m = matcher("//book[@x!='y']")
        val attrs: (QName) -> String? = { if (it.local == "x") "y" else null }
        assertFalse(m.pushElement(qn("book"), attrs))
        m.popElement()
    }

    @Test fun predicateIndex_matchesOnlyThirdSibling() {
        val m = matcher("//item[3]")
        assertFalse(m.pushElement(qn("item"), noAttrs)); m.popElement()
        assertFalse(m.pushElement(qn("item"), noAttrs)); m.popElement()
        assertTrue(m.pushElement(qn("item"), noAttrs)); m.popElement()
        assertFalse(m.pushElement(qn("item"), noAttrs)); m.popElement()
    }

    @Test fun predicateAnd_requiresBoth() {
        val m = matcher("//e[@a='1' and @b='2']")
        val both: (QName) -> String? = { if (it.local == "a") "1" else if (it.local == "b") "2" else null }
        val onlyA: (QName) -> String? = { if (it.local == "a") "1" else null }
        val onlyB: (QName) -> String? = { if (it.local == "b") "2" else null }
        assertTrue(m.pushElement(qn("e"), both)); m.popElement()
        assertFalse(m.pushElement(qn("e"), onlyA)); m.popElement()
        assertFalse(m.pushElement(qn("e"), onlyB)); m.popElement()
        assertFalse(m.pushElement(qn("e"), noAttrs)); m.popElement()
    }

    @Test fun predicateOr_requiresEither() {
        val m = matcher("//e[@a='1' or @b='2']")
        val both: (QName) -> String? = { if (it.local == "a") "1" else if (it.local == "b") "2" else null }
        val onlyA: (QName) -> String? = { if (it.local == "a") "1" else null }
        val onlyB: (QName) -> String? = { if (it.local == "b") "2" else null }
        assertTrue(m.pushElement(qn("e"), both)); m.popElement()
        assertTrue(m.pushElement(qn("e"), onlyA)); m.popElement()
        assertTrue(m.pushElement(qn("e"), onlyB)); m.popElement()
        assertFalse(m.pushElement(qn("e"), noAttrs)); m.popElement()
    }

    @Test fun namespaceMatch_explicitUri() {
        val m = matcher("//{atom}entry")
        assertTrue(m.pushElement(qn("entry", ns = "atom"), noAttrs)); m.popElement()
        // wrong namespace
        assertFalse(m.pushElement(qn("entry", ns = "other"), noAttrs)); m.popElement()
        // null namespace
        assertFalse(m.pushElement(qn("entry"), noAttrs)); m.popElement()
    }

    @Test fun namespaceWildcard_matchesAnyNs() {
        val m = matcher("//{*}entry")
        assertTrue(m.pushElement(qn("entry", ns = "atom"), noAttrs)); m.popElement()
        assertTrue(m.pushElement(qn("entry", ns = "rss"), noAttrs)); m.popElement()
        assertTrue(m.pushElement(qn("entry", ns = null), noAttrs)); m.popElement()
        // wrong local
        assertFalse(m.pushElement(qn("other", ns = "atom"), noAttrs)); m.popElement()
    }

    @Test fun ignoreNsFlag_treatsAllNamespacesAsWildcard() {
        val m = matcher("//{atom}entry", ignoreNs = true)
        // even mismatched ns uri should match because ignoreNs is true
        assertTrue(m.pushElement(qn("entry", ns = "completelyDifferentNs"), noAttrs)); m.popElement()
        assertTrue(m.pushElement(qn("entry", ns = null), noAttrs)); m.popElement()
        // local mismatch still fails
        assertFalse(m.pushElement(qn("other", ns = "atom"), noAttrs)); m.popElement()
    }

    @Test fun popAfterNoMatch_preservesPreviousState() {
        // matches /a/b
        val m = matcher("/a/b")
        m.pushElement(qn("a"), noAttrs)
        // push a non-matching element under <a>
        assertFalse(m.pushElement(qn("z"), noAttrs))
        m.popElement()
        // now <b> directly under <a> should still match — the failed push shouldn't
        // have damaged the stack-saved state at depth 1.
        assertTrue(m.pushElement(qn("b"), noAttrs))
        m.popElement()
        m.popElement()
    }

    @Test fun matchedFlag_reflectsLastPush() {
        val m = matcher("//author")
        m.pushElement(qn("root"), noAttrs)
        assertFalse(m.matched)
        m.pushElement(qn("author"), noAttrs)
        assertTrue(m.matched)
        m.popElement()
        assertFalse(m.matched)
        m.popElement()
    }

    @Test fun returnFlagAndMatchedFlag_agree() {
        val m = matcher("//x")
        val r1 = m.pushElement(qn("y"), noAttrs)
        assertEquals(r1, m.matched)
        m.popElement()
        val r2 = m.pushElement(qn("x"), noAttrs)
        assertEquals(r2, m.matched)
        m.popElement()
    }

    @Test fun chainedAttrThenIndex_filtersFirstThenIndexes() {
        // XPath: 2nd <item> with @kind='post'.
        val m = matcher("//root/item[@kind='post'][2]")
        m.pushElement(qn("root"), noAttrs)
        val page: (QName) -> String? = { if (it.local == "kind") "page" else null }
        val post: (QName) -> String? = { if (it.local == "kind") "post" else null }
        assertFalse(m.pushElement(qn("item"), page)); m.popElement()  // not a post
        assertFalse(m.pushElement(qn("item"), post)); m.popElement()  // 1st post
        assertFalse(m.pushElement(qn("item"), page)); m.popElement()  // not a post
        assertTrue(m.pushElement(qn("item"), post)); m.popElement()   // 2nd post — match
        assertFalse(m.pushElement(qn("item"), post)); m.popElement()  // 3rd post
        m.popElement()
    }

    @Test fun chainedIndexThenAttr_indexesFirstThenFilters() {
        // XPath: the 2nd <item> overall, AND it must have @kind='post'.
        val m = matcher("//root/item[2][@kind='post']")
        val post: (QName) -> String? = { if (it.local == "kind") "post" else null }
        val page: (QName) -> String? = { if (it.local == "kind") "page" else null }

        m.pushElement(qn("root"), noAttrs)
        assertFalse(m.pushElement(qn("item"), post)); m.popElement()  // 1st item, post — fails [2]
        assertTrue(m.pushElement(qn("item"), post)); m.popElement()   // 2nd item, post — match
        m.popElement()

        // Different parent: 2nd item is a page → no match.
        m.pushElement(qn("root"), noAttrs)
        assertFalse(m.pushElement(qn("item"), post)); m.popElement()  // 1st item, post
        assertFalse(m.pushElement(qn("item"), page)); m.popElement()  // 2nd item, but not post
        m.popElement()
    }
}
