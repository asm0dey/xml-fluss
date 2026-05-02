package xmlfluss.ksp

import com.squareup.kotlinpoet.CodeBlock
import xmlfluss.codegen.model.Coerce as CoreCoerce
import xmlfluss.codegen.model.FieldSpec as CoreFieldSpec
import xmlfluss.codegen.model.NestedRegistry as CoreNestedRegistry
import xmlfluss.codegen.model.PolyDispatch as CorePolyDispatch

/** Inline a single non-list `Source.Child` leaf read. */
internal fun emitLeafReadInline(cb: CodeBlock.Builder, f: CoreFieldSpec, registry: CoreNestedRegistry) {
    when (f.coerce()) {
        is CoreCoerce.Nested -> emitNestedHelperAssign(cb, f, f.elemTypeFq(), registry)

        else -> {
            val needLoc = needsChildLoc(f)
            if (needLoc) cb.add("val __t_loc·=·c.childLocation()\n")
            cb.add("val __t = c.childText(false)\n")
            if (f.isList) {
                cb.add("${listN(f.name())}.add(__t)\n")
            } else {
                cb.add("${rawN(f.name())} = __t\n")
                if (needLoc) cb.add("${locN(f.name())} = __t_loc\n")
                cb.add("${setN(f.name())} = true\n")
            }
        }
    }
}

internal fun emitPolyAssign(
    cb: CodeBlock.Builder,
    f: CoreFieldSpec,
    subtypeFq: String,
    registry: CoreNestedRegistry,
) {
    // buildPolyChild calls ensureNested(sub) for every subtype, so the registry has every spec.
    emitNestedHelperAssign(cb, f, subtypeFq, registry)
}

/** Call the registry helper for [helperFq] and bind the result into the field's slot. */
internal fun emitNestedHelperAssign(
    cb: CodeBlock.Builder,
    f: CoreFieldSpec,
    helperFq: String,
    registry: CoreNestedRegistry,
) {
    val helper = registry.helperName(helperFq)
    cb.add("val ${nN(f.name())}·=·${helper}(c)\n")
    if (f.isList) {
        cb.add("${listN(f.name())}.add(${nN(f.name())})\n")
    } else {
        cb.add("${nestedN(f.name())} = ${nN(f.name())}\n")
        cb.add("${setN(f.name())} = true\n")
    }
}

internal fun emitPolyAttrSwitch(
    cb: CodeBlock.Builder,
    f: CoreFieldSpec,
    d: CorePolyDispatch.Attr,
    registry: CoreNestedRegistry,
) {
    cb.add("val ${discN(f.name())}: %T = c.childAttr(%L, %S)\n", STRING_NULLABLE, nsLit(d.attrNs()), d.attrLocal())
    cb.beginControlFlow("when (${discN(f.name())})")
    for (v in d.variants()) {
        cb.beginControlFlow("%S ->", v.value())
        emitPolyAssign(cb, f, v.subtypeFq(), registry)
        cb.endControlFlow()
    }
    cb.add(ELSE_SKIP_CHILD)
    cb.endControlFlow()
}

/** Emit attr entries followed by body content for a child trie node. */
internal fun emitChildBody(
    cb: CodeBlock.Builder,
    node: xmlfluss.codegen.plan.TrieNode,
    registry: CoreNestedRegistry,
) {
    emitAttrEntries(cb, node)
    emitChildBodyContent(cb, node, registry)
}

