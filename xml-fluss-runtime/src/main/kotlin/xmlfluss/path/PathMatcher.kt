package xmlfluss.path

/**
 * Streaming NFA matcher driven by [pushElement]/[popElement] events from the StAX cursor.
 *
 * The state at each depth is a [BooleanArray] of size `n + 1`, where `n` is the number of element
 * steps in the [CompiledPath]. Slot `i` is `true` when the input has consumed exactly the first
 * `i` steps. When the final slot becomes `true`, the current element matches the path.
 *
 * The matcher only inspects element names, attribute values, and sibling positions, so every
 * decision is made at `START_ELEMENT` time. No element content is buffered.
 *
 * @param path the compiled path to match against.
 * @param ignoreNs when `true`, the namespace component of each compiled-path element step is
 *   ignored — matching is by local-name only. Pair with an [xmlfluss.runtime.XmlReadCursor]
 *   constructed with the same flag so attribute lookups stay consistent.
 */
class PathMatcher(private val path: CompiledPath, private val ignoreNs: Boolean = false) {
    private val steps: List<Step> = path.elementSteps
    private val n: Int = steps.size

    private val stack: ArrayDeque<BooleanArray> = ArrayDeque()

    init {
        val initial = BooleanArray(n + 1)
        initial[0] = true
        epsilon(initial)
        stack.addLast(initial)
    }

    /** Whether the most recently pushed element matches the full path. */
    val matched: Boolean
        get() = stack.last()[n]

    /**
     * Records that a new element has been entered.
     *
     * @param qn the element's qualified name.
     * @param attrAt looks up an attribute on this element by [QName]. Used to evaluate predicates.
     * @param indexAmongSiblings the one-based position of this element among its same-named
     *   siblings under the parent (driving `[N]` predicates).
     * @return `true` if the path now matches at this element.
     */
    fun pushElement(qn: QName, attrAt: (QName) -> String?, indexAmongSiblings: Int): Boolean {
        val cur = stack.last()
        val next = BooleanArray(n + 1)
        for (i in 0 until n) {
            if (!cur[i]) continue
            when (val s = steps[i]) {
                is Step.Named -> {
                    if (matchName(s.name, qn) && evalPredicate(s.predicate, attrAt, indexAmongSiblings)) {
                        next[i + 1] = true
                    }
                }
                Step.Descendant -> {
                    next[i] = true
                }
                is Step.AttrLeaf -> {}
            }
        }
        epsilon(next)
        stack.addLast(next)
        return next[n]
    }

    /** Reverses the most recent [pushElement] when an `END_ELEMENT` is read. */
    fun popElement() { stack.removeLast() }

    private fun epsilon(s: BooleanArray) {
        for (i in 0 until n) {
            if (s[i] && steps[i] is Step.Descendant) s[i + 1] = true
        }
    }

    private fun matchName(pat: QName, q: QName): Boolean {
        if (pat.local != "*" && pat.local != q.local) return false
        if (ignoreNs) return true
        return when (pat.ns) {
            null -> q.ns == null
            PathParser.WILDCARD -> true
            else -> pat.ns == q.ns
        }
    }

    private fun evalPredicate(p: Predicate?, attrAt: (QName) -> String?, idx: Int): Boolean = when (p) {
        null -> true
        is Predicate.AttrEq -> {
            val v = attrAt(p.name)
            val eq = v != null && v == p.value
            if (p.negate) !eq else eq
        }
        is Predicate.Index -> idx == p.n
        is Predicate.And -> evalPredicate(p.l, attrAt, idx) && evalPredicate(p.r, attrAt, idx)
        is Predicate.Or  -> evalPredicate(p.l, attrAt, idx) || evalPredicate(p.r, attrAt, idx)
    }
}
