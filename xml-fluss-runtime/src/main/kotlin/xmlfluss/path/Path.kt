package xmlfluss.path

/**
 * Qualified name. A `null` [ns] means "no namespace": a bare local-name on a document with no
 * default namespace, or a null-NS attribute.
 *
 * @property ns namespace URI, or `null`.
 * @property local local-name.
 */
data class QName(val ns: String?, val local: String) {
    override fun toString(): String = if (ns != null) "{$ns}$local" else local
}

/**
 * One step in a compiled path. Steps are produced by [PathParser] and consumed by [PathMatcher].
 */
sealed class Step {
    /**
     * A named element step, optionally constrained by an ordered list of bracket [brackets].
     *
     * Each bracket is a [Predicate]. Brackets apply in order and follow XPath semantics: a
     * positional check inside bracket `k` counts only same-name siblings under the parent that
     * passed every earlier bracket. So `step[@a='x'][2]` means "the 2nd same-name sibling among
     * those with `@a='x'`", not "the 2nd same-name sibling that also has `@a='x'`".
     *
     * Local-name `*` matches any element name. Namespace [PathParser.WILDCARD] matches any
     * namespace.
     */
    data class Named(val name: QName, val brackets: List<Predicate> = emptyList()) : Step() {
        /**
         * Compatibility view that folds [brackets] with implicit `and`. Useful for callers that
         * only need a boolean filter — for example `@XmlChild` codegen, where positional brackets
         * are rejected at validation, so an `and`-fold keeps the same semantics. The matcher
         * itself never reads this; it walks [brackets] in order to honour XPath positional rules.
         */
        val predicate: Predicate?
            get() = brackets.reduceOrNull { a, b -> Predicate.And(a, b) }
    }

    /** Descendant-or-self axis. Lets the next named step match at any depth. */
    data object Descendant : Step()

    /** Trailing attribute step (e.g. the `@id` in `book/@id`). Always last in a compiled path. */
    data class AttrLeaf(val name: QName) : Step()
}

/**
 * Filters a [Step.Named] step. The grammar is narrow on purpose: every predicate can be evaluated
 * at `START_ELEMENT` time without buffering element content.
 */
sealed class Predicate {
    /** `[@attr='value']` or `[@attr!='value']`. */
    data class AttrEq(val name: QName, val value: String, val negate: Boolean) : Predicate()

    /** `[N]`: one-based position among siblings of the matching name. */
    data class Index(val n: Int) : Predicate()

    /** Logical conjunction of two predicates. */
    data class And(val l: Predicate, val r: Predicate) : Predicate()

    /** Logical disjunction of two predicates. */
    data class Or(val l: Predicate, val r: Predicate) : Predicate()
}

/**
 * Output of [PathParser]. A list of [steps] plus an [absolute] flag controlling whether the path
 * is anchored to the document root.
 *
 * @property attrLeaf the trailing [Step.AttrLeaf] if the path ends with one, otherwise `null`.
 * @property elementSteps the steps with the trailing attr leaf removed; the matcher operates on
 *   these.
 */
data class CompiledPath(
    val steps: List<Step>,
    val absolute: Boolean,
) {
    val attrLeaf: Step.AttrLeaf? = steps.lastOrNull() as? Step.AttrLeaf
    val elementSteps: List<Step> = if (attrLeaf != null) steps.dropLast(1) else steps
}
