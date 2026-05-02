package xmlfluss.ksp

import com.squareup.kotlinpoet.CodeBlock
import xmlfluss.codegen.plan.DescendantBranch
import xmlfluss.codegen.plan.SlotTable
import xmlfluss.codegen.plan.TailTrie
import xmlfluss.codegen.model.FieldSpec as CoreFieldSpec
import xmlfluss.codegen.model.NestedRegistry as CoreNestedRegistry
import xmlfluss.codegen.model.QKey as CoreQKey
import xmlfluss.codegen.model.Source as CoreSource

internal fun emitDescendantArm(
    cb: CodeBlock.Builder,
    head: CoreQKey,
    branches: List<DescendantBranch>,
    tailTries: Map<CoreQKey, TailTrie>,
    registry: CoreNestedRegistry,
    terminating: Boolean,
) {
    // The descendant-axis head segment cannot carry a positional predicate (rejected by
    // validateChildPredicate), so brackets here are guaranteed Index-free. We can still have
    // attribute-equality predicates that select among descendant heads.
    val unguarded = branches.filter { it.brackets().isEmpty() }.map { it.field() }
    val guarded = branches.filter { it.brackets().isNotEmpty() }
    val tail = tailTries[head]
    if (guarded.isEmpty()) {
        emitDescendantArmBody(cb, head, unguarded, tail, registry)
        if (terminating) cb.add(TRUE_NL)
        return
    }
    cb.beginControlFlow("when")
    for (b in guarded) {
        cb.beginControlFlow("%L ->", predicateExpr(b.brackets(), head, SlotTable()))
        emitDescendantArmBody(cb, head, listOf(b.field()), tail, registry)
        if (terminating) cb.add(TRUE_NL)
        cb.endControlFlow()
    }
    if (unguarded.isNotEmpty()) {
        cb.beginControlFlow("else ->")
        emitDescendantArmBody(cb, head, unguarded, tail, registry)
        if (terminating) cb.add(TRUE_NL)
        cb.endControlFlow()
    } else {
        cb.add("else -> ")
        if (terminating) cb.add("false\n") else cb.add(SKIP_CHILD)
    }
    cb.endControlFlow()
}

private fun emitDescendantArmBody(
    cb: CodeBlock.Builder,
    head: CoreQKey,
    headFields: List<CoreFieldSpec>,
    tail: TailTrie?,
    registry: CoreNestedRegistry,
) {
    val anyEmpty = headFields.any {
        (it.source() as CoreSource.Child).segments().size == 1
    }
    val anyNonEmpty = headFields.any {
        (it.source() as CoreSource.Child).segments().size > 1
    }
    vRequire(!(anyEmpty && anyNonEmpty)) {
        "cannot mix '//${head.local()}' with '//${head.local()}/...' on the same head element"
    }
    if (anyEmpty) {
        vRequire(headFields.size == 1) {
            "multiple descendant fields targeting '//${head.local()}' (single-segment); at most one allowed"
        }
        emitLeafReadInline(cb, headFields[0], registry)
    } else {
        val tailTrie = tail
            ?: error("missing tail trie for descendant head $head — bug in DispatchPlanBuilder")
        emitChildBody(cb, tailTrie.trie(), registry)
    }
}
