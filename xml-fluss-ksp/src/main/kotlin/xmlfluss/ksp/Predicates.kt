package xmlfluss.ksp

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.INT_ARRAY
import xmlfluss.codegen.plan.PredicateAnalysis
import xmlfluss.codegen.plan.PrefixKey
import xmlfluss.codegen.plan.SlotTable
import xmlfluss.codegen.model.QKey as CoreQKey
import xmlfluss.path.Predicate as PathPredicate

/**
 * Emit a boolean expression for [brackets] in the context of [qkey], optionally referencing
 * counter slots from [slots]. When any bracket contains a positional [PathPredicate.Index],
 * the corresponding `__pre_<slot>`/`__pos_<slot>` references are emitted; everything before
 * the first Index becomes part of the slot's prefix expression and everything after is
 * appended as plain expressions.
 */
internal fun predicateExpr(
    brackets: List<PathPredicate>,
    qkey: CoreQKey,
    slots: SlotTable,
): CodeBlock {
    if (brackets.isEmpty()) return CodeBlock.of("true")
    if (!PredicateAnalysis.bracketsHaveIndex(brackets)) {
        val folded = brackets.reduce { a, b -> PathPredicate.And(a, b) }
        return plainPredicateExpr(folded)
    }
    val firstIdxIndex = brackets.indexOfFirst { PredicateAnalysis.containsIndex(it) }
    // unreachable: bracketsHaveIndex returned true, so at least one bracket contains Index.
    require(firstIdxIndex >= 0) { "predicateExpr called with no Index in brackets — bug in bracketsHaveIndex" }
    val prefix = brackets.subList(0, firstIdxIndex)
    val firstIdxBracket = brackets[firstIdxIndex]
    val suffix = brackets.subList(firstIdxIndex + 1, brackets.size)
    val n = PredicateAnalysis.firstIndexValue(firstIdxBracket)
    val slotKey = PrefixKey(qkey, PredicateAnalysis.prefixOfFirstIndex(brackets))
    // SlotTable.get returns null when the key is absent. DispatchPlanBuilder allocates every
    // (qkey, prefix) pair we encounter, so a null here signals a plan-builder bug.
    val slotName: String = slots.get(slotKey)
    val parts = mutableListOf<CodeBlock>()
    // When the prefix is empty there is no __pre_ variable — the counter is always incremented.
    if (prefix.isNotEmpty()) parts += CodeBlock.of("__pre_%L", slotName)
    parts += CodeBlock.of("(__pos_%L == %L)", slotName, n)
    // If the first-index bracket also contains non-Index predicates (e.g. `[2 and @x='y']`),
    // emit those alongside the position check.
    val residual = PredicateAnalysis.stripIndex(firstIdxBracket)
    if (residual != null) parts += plainPredicateExpr(residual)
    // Suffix: every bracket after the one that introduced the Index. These are evaluated as
    // ordinary attribute predicates against the current element — they refine the position
    // match but do not affect counter incrementing.
    for (s in suffix) parts += plainPredicateExpr(s)
    return parts.reduce { a, b -> CodeBlock.of("(%L && %L)", a, b) }
}

/**
 * Emit a boolean expression for an Index-free predicate. Equivalent to the legacy
 * `predicateExpr` minus the Index arm; descending into And/Or recurses through this same
 * function. Callers must guarantee [p] contains no [PathPredicate.Index].
 */
internal fun plainPredicateExpr(p: PathPredicate): CodeBlock = when (p) {
    is PathPredicate.AttrEq -> {
        val ns = p.name.ns
        if (p.negate) {
            CodeBlock.of("(c.childAttr(%L, %S).let { it == null || it != %S })", nsLit(ns), p.name.local, p.value)
        } else {
            CodeBlock.of("(c.childAttr(%L, %S) == %S)", nsLit(ns), p.name.local, p.value)
        }
    }
    is PathPredicate.And -> CodeBlock.of("(%L && %L)", plainPredicateExpr(p.l), plainPredicateExpr(p.r))
    is PathPredicate.Or -> CodeBlock.of("(%L || %L)", plainPredicateExpr(p.l), plainPredicateExpr(p.r))
    // unreachable: caller routes Index-bearing brackets through predicateExpr; PredicateAnalysis.stripIndex removes Index nodes from And residuals before recursion.
    is PathPredicate.Index -> error("plainPredicateExpr called on Index — counter logic should have stripped this")
}

/** Emits `val __cnt_<slot>: IntArray = intArrayOf(0)` for each precomputed slot. */
internal fun declareSlotsCode(cb: CodeBlock.Builder, slots: SlotTable) {
    for ((_, name) in slots) {
        cb.add("val __cnt_%L: %T = intArrayOf(0)\n", name, INT_ARRAY)
    }
}
