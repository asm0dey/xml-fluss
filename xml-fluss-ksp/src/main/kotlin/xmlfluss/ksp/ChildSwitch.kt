package xmlfluss.ksp

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.INT
import xmlfluss.codegen.plan.AttrEntry
import xmlfluss.codegen.plan.DescendantBranch
import xmlfluss.codegen.plan.DispatchPlan
import xmlfluss.codegen.plan.MapPlan
import xmlfluss.codegen.plan.SlotTable
import xmlfluss.codegen.plan.TailTrie
import xmlfluss.codegen.plan.TrieNode
import xmlfluss.codegen.model.FieldSpec as CoreFieldSpec
import xmlfluss.codegen.model.NestedRegistry as CoreNestedRegistry
import xmlfluss.codegen.model.PolyDispatch as CorePolyDispatch
import xmlfluss.codegen.model.QKey as CoreQKey
import xmlfluss.codegen.model.Source as CoreSource
import xmlfluss.path.Predicate as PathPredicate

/**
 * Emits the inside-lambda body of a `forEachChild { ln, ns -> ... }` switch over the direct
 * children of [node]. Counter slot declarations live OUTSIDE the lambda — callers obtain the
 * pre-built [SlotTable] from the plan and pass it in via [slots] so the IntArray persists
 * across sibling iterations.
 */
internal fun emitChildrenSwitch(
    cb: CodeBlock.Builder,
    node: TrieNode,
    slots: SlotTable,
    registry: CoreNestedRegistry,
) {
    if (node.children().isEmpty()) {
        cb.add(SKIP_CHILD)
        return
    }
    cb.beginControlFlow("when(ln)")
    emitGroupedChildArms(cb, node.groupChildrenByQKey(), slots, registry)
    cb.add(ELSE_SKIP_CHILD)
    cb.endControlFlow()
}

/** Emit `local -> { … }` arms over a [TrieNode.groupChildrenByQKey] map inside an open `when(ln)` block. */
internal fun emitGroupedChildArms(
    cb: CodeBlock.Builder,
    grouped: Map<CoreQKey, List<Map.Entry<List<PathPredicate>, TrieNode>>>,
    slots: SlotTable,
    registry: CoreNestedRegistry,
) {
    for ((qk, branches) in grouped) {
        cb.beginControlFlow("%L ->", qnameCond(qk.ns(), qk.local()))
        emitPredicateBranches(cb, qk, branches, slots, registry)
        cb.endControlFlow()
    }
}

internal fun emitPredicateBranches(
    cb: CodeBlock.Builder,
    qkey: CoreQKey,
    branches: List<Map.Entry<List<PathPredicate>, TrieNode>>,
    slots: SlotTable,
    registry: CoreNestedRegistry,
) {
    // Per-element pre-compute: increment any counters that key on this qname BEFORE the
    // two-pass dispatch. Each element produces exactly one increment per slot, regardless of
    // how many attr-only / body branches reference that slot.
    for (slotEntry in slots) {
        val key = slotEntry.key
        if (key.qkey() != qkey) continue
        val name = slotEntry.value
        if (key.prefix().isEmpty()) {
            // No prefix guard — unconditionally increment.
            cb.add("val __pos_%L: %T = ++__cnt_%L[0]\n", name, INT, name)
        } else {
            val folded = key.prefix().reduce { a, b -> PathPredicate.And(a, b) }
            val preExpr: CodeBlock = plainPredicateExpr(folded)
            cb.add("val __pre_%L: %T = %L\n", name, BOOLEAN, preExpr)
            cb.add("val __pos_%L: %T = if (__pre_%L) ++__cnt_%L[0] else 0\n", name, INT, name, name)
        }
    }

    if (branches.size == 1 && branches[0].key.isEmpty()) {
        emitChildBody(cb, branches[0].value, registry)
        return
    }
    // Two-pass dispatch when predicate variants overlap on the same element:
    //  1. attr-only reads run for EVERY matching predicate (childAttr is a pure lookup, no
    //     element consumption). Without this, two paths like
    //       link[@type='epub']/@href
    //       link[@type='epub'][@rel='acq']/@href
    //     would race and only the first matching arm would fire.
    //  2. Body-consuming variants (text / nested / descend) dispatch first-match-wins —
    //     a single element body can only be consumed once.
    for (entry in branches) {
        val child = entry.value
        if (child.attrEntries().isEmpty()) continue
        val expr = predicateExpr(entry.key, qkey, slots)
        cb.beginControlFlow("if (%L)", expr)
        emitAttrEntries(cb, child)
        cb.endControlFlow()
    }
    val bodyBranches = branches.filter { it.value.hasBodyContent() }
    if (bodyBranches.isEmpty()) {
        cb.add(SKIP_CHILD)
        return
    }
    cb.beginControlFlow("when")
    for (entry in bodyBranches) {
        val expr = predicateExpr(entry.key, qkey, slots)
        cb.beginControlFlow("%L ->", expr)
        emitChildBodyContent(cb, entry.value, registry)
        cb.endControlFlow()
    }
    cb.add(ELSE_SKIP_CHILD)
    cb.endControlFlow()
}

internal fun emitAttrEntries(cb: CodeBlock.Builder, node: TrieNode) {
    for (ae: AttrEntry in node.attrEntries()) {
        val f = ae.field()
        val ns = nsLit(ae.ns())
        if (f.isList) {
            cb.add("c.childAttr(%L, %S)?.let { ${listN(f.name())}.add(it) }\n", ns, ae.name())
        } else if (needsChildLoc(f)) {
            cb.add(
                "c.childAttr(%L, %S)?.let { ${rawN(f.name())} = it; ${locN(f.name())} = c.childLocation(); ${setN(f.name())} = true }\n",
                ns, ae.name()
            )
        } else {
            cb.add(
                "c.childAttr(%L, %S)?.let { ${rawN(f.name())} = it; ${setN(f.name())} = true }\n",
                ns, ae.name()
            )
        }
    }
}

internal fun emitChildBodyContent(
    cb: CodeBlock.Builder,
    node: TrieNode,
    registry: CoreNestedRegistry,
) {
    val hasText = node.textEntries().isNotEmpty()
    val hasNested = node.nestedEntries().isNotEmpty()
    val hasDescend = node.children().isNotEmpty()
    when {
        hasNested -> {
            for (f in node.nestedEntries()) {
                emitNestedHelperAssign(cb, f, f.elemTypeFq(), registry)
            }
        }
        hasText -> {
            val textFields = node.textEntries().toList()
            val needLoc = textFields.any { needsChildLoc(it) }
            if (needLoc) cb.add("val __t_loc·=·c.childLocation()\n")
            cb.add("val __t = c.childText(false)\n")
            for (f in textFields) {
                if (f.isList) cb.add("${listN(f.name())}.add(__t)\n")
                else {
                    cb.add("${rawN(f.name())} = __t\n")
                    if (needsChildLoc(f)) cb.add("${locN(f.name())} = __t_loc\n")
                    cb.add("${setN(f.name())} = true\n")
                }
            }
        }
        hasDescend -> {
            // Counter slots for direct edges under `node` must outlive the per-sibling lambda
            // — declare them here, BEFORE entering forEachChild, so increments accumulate
            // across siblings of the same parent.
            val slots = node.allocateSlots()
            declareSlotsCode(cb, slots)
            cb.beginControlFlow("c.forEachChild·{ ln, ns ->\n")
            emitChildrenSwitch(cb, node, slots, registry)
            cb.endControlFlow()
        }
        else -> cb.add(SKIP_CHILD)
    }
}

/**
 * Top-level child dispatch driven entirely by a pre-built [DispatchPlan]. The emitter does
 * not insert into tries, group by QKey, or allocate slots — every routing decision was
 * resolved by [xmlfluss.codegen.plan.DispatchPlanBuilder].
 */
internal fun emitTopLevelChildSwitch(
    cb: CodeBlock.Builder,
    plan: DispatchPlan,
    registry: CoreNestedRegistry,
    convVarFor: Map<String, String>,
) {
    emitChildSwitchCore(
        cb,
        plan.directRoot(),
        plan.slots(),
        plan.descendantByHead(),
        plan.tailTries(),
        plan.mapPlans(),
        plan.polyFields(),
        registry, convVarFor,
    )
}

/**
 * Core child-dispatch switch. Used both by record/nested-record bodies (via the full
 * [DispatchPlan]) and by `@XmlMap` entries (which only carry direct-child + descendant data —
 * no map-of-map or polymorphic dispatch).
 */
internal fun emitChildSwitchCore(
    cb: CodeBlock.Builder,
    directRoot: TrieNode,
    slots: SlotTable,
    byHead: Map<CoreQKey, List<DescendantBranch>>,
    tailTries: Map<CoreQKey, TailTrie>,
    mapPlans: Map<CoreFieldSpec, MapPlan>,
    polyFields: List<CoreFieldSpec>,
    registry: CoreNestedRegistry,
    convVarFor: Map<String, String>,
) {
    if (directRoot.children().isEmpty() && byHead.isEmpty() && mapPlans.isEmpty() && polyFields.isEmpty()) {
        cb.add(SKIP_CHILD)
        return
    }
    cb.beginControlFlow("when(ln)")
    emitGroupedChildArms(cb, directRoot.groupChildrenByQKey(), slots, registry)
    for ((mf, mp) in mapPlans) {
        val src = mf.source() as CoreSource.MapEntry
        cb.beginControlFlow("%L ->", qnameCond(src.entryNs(), src.entryLocal()))
        emitMapEntryCase(cb, mf, mp, registry, convVarFor)
        cb.endControlFlow()
    }
    for (pf in polyFields) {
        when (val d = (pf.source() as CoreSource.PolyChild).dispatch()) {
            is CorePolyDispatch.Tag -> {
                for (v in d.variants()) {
                    cb.beginControlFlow("%L ->", qnameCond(v.ns(), v.local()))
                    emitPolyAssign(cb, pf, v.subtypeFq(), registry)
                    cb.endControlFlow()
                }
            }
            is CorePolyDispatch.Attr -> {
                cb.beginControlFlow("%L ->", qnameCond(d.wrapNs(), d.wrapLocal()))
                emitPolyAttrSwitch(cb, pf, d, registry)
                cb.endControlFlow()
            }
        }
    }
    for ((head, branches) in byHead) {
        cb.beginControlFlow("%L ->", qnameCond(head.ns(), head.local()))
        emitDescendantArm(cb, head, branches, tailTries, registry, terminating = false)
        cb.endControlFlow()
    }
    if (byHead.isEmpty()) {
        cb.add(ELSE_SKIP_CHILD)
    } else {
        cb.beginControlFlow("else ->")
        cb.beginControlFlow("c.forEachDescendantInChild·{ dln, dns ->\n")
        cb.beginControlFlow("when(dln)")
        for ((head, branches) in byHead) {
            cb.beginControlFlow("%L ->", qnameCond(head.ns(), head.local(), nsVar = "dns"))
            emitDescendantArm(cb, head, branches, tailTries, registry, terminating = true)
            cb.endControlFlow()
        }
        cb.add("else -> false\n")
        cb.endControlFlow()
        cb.endControlFlow()
        cb.endControlFlow()
    }
    cb.endControlFlow()
}
