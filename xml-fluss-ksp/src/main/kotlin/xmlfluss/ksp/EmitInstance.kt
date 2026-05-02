package xmlfluss.ksp

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.STRING
import xmlfluss.codegen.plan.DispatchPlan
import xmlfluss.codegen.model.FieldSpec as CoreFieldSpec
import xmlfluss.codegen.model.NestedRegistry as CoreNestedRegistry
import xmlfluss.codegen.model.RecordSpec as CoreRecordSpec
import xmlfluss.codegen.model.Source as CoreSource

/**
 * Local enum tracking record vs subrecord context so the emitter can pick the right
 * cursor accessor name (`recordAttr` vs `childAttr`, etc.). No neutral equivalent —
 * this is a pure emit-side switch.
 */
internal enum class Ctx { RECORD, SUBRECORD }

internal fun buildNsInitializer(nsMap: Map<String, String>): CodeBlock {
    if (nsMap.isEmpty()) return CodeBlock.of("emptyMap()")
    val cb = CodeBlock.builder().add("mapOf(\n")
    nsMap.forEach { (k, v) -> cb.add("    %S to %S,\n", k, v) }
    cb.add(")")
    return cb.build()
}

internal fun buildParseBody(
    recordType: ClassName,
    fields: List<CoreFieldSpec>,
    convVarFor: Map<String, String>,
    registry: CoreNestedRegistry,
    plan: DispatchPlan,
): CodeBlock {
    val cb = CodeBlock.builder()
    cb.beginControlFlow("return·%M", FLOW_BUILDER)
    cb.beginControlFlow("%T(input,·PATH,·ignoreNamespace).use·{ c ->\n", XML_READ_CURSOR)
    cb.beginControlFlow("while (c.findNextRecord())")
    emitInstanceBody(cb, recordType, fields, convVarFor, registry, plan, ctx = Ctx.RECORD)
    cb.endControlFlow()
    cb.endControlFlow()
    cb.endControlFlow()
    return cb.build()
}

internal fun buildSubrecordBody(
    type: ClassName,
    spec: CoreRecordSpec,
    convVarFor: Map<String, String>,
    registry: CoreNestedRegistry,
    plan: DispatchPlan,
): CodeBlock {
    val cb = CodeBlock.builder()
    emitInstanceBody(cb, type, spec.fields(), convVarFor, registry, plan, ctx = Ctx.SUBRECORD)
    return cb.build()
}

private fun emitInstanceBody(
    cb: CodeBlock.Builder,
    type: ClassName,
    fields: List<CoreFieldSpec>,
    convVarFor: Map<String, String>,
    registry: CoreNestedRegistry,
    plan: DispatchPlan,
    ctx: Ctx,
) {
    val attrFields = fields.filter { it.source() is CoreSource.Attr }
    val textField = fields.firstOrNull { it.source() is CoreSource.Text }
    val childFields = fields.filter { it.source() is CoreSource.Child }
    val mapFields = fields.filter { it.source() is CoreSource.MapEntry }
    val polyFields = fields.filter { it.source() is CoreSource.PolyChild }

    val attrFn = if (ctx == Ctx.RECORD) "recordAttr" else "childAttr"
    val forEachFn = if (ctx == Ctx.RECORD) "forEachRecordChild" else "forEachSubrecordChild"
    val textFn = if (ctx == Ctx.RECORD) "recordText" else "subrecordText"
    val locFn = if (ctx == Ctx.RECORD) "recordLocation" else "childLocation"

    cb.add("val __loc: %T = c.${locFn}()\n", LOCATION)

    for (f in attrFields) {
        val src = f.source() as CoreSource.Attr
        cb.add("val ${rawN(f.name())}: %T = c.${attrFn}(%L, %S)\n", STRING_NULLABLE, nsLit(src.ns()), src.name())
    }

    for (f in childFields + polyFields + listOfNotNull(textField)) emitFieldStateInit(cb, f)
    for (mf in mapFields) emitMapStateInit(cb, mf)

    // Invariant: textField != null  ⇒  needTraverse == true (textField is one of the
    // disjuncts below). coerceField below reads `__raw_${textField.name}` unconditionally,
    // so the declaration emitted inside this block is always reached when textField != null.
    val needTraverse = childFields.isNotEmpty() || textField != null || mapFields.isNotEmpty() || polyFields.isNotEmpty()
    if (needTraverse) {
        // Counter slots must be declared OUTSIDE the per-sibling lambda so that ++__cnt[0]
        // accumulates across siblings rather than resetting per iteration.
        declareSlotsCode(cb, plan.slots())
        cb.beginControlFlow("c.${forEachFn}·{ ln, ns ->\n")
        emitTopLevelChildSwitch(cb, plan, registry, convVarFor)
        cb.endControlFlow()
        if (textField != null) {
            val preserve = (textField.source() as CoreSource.Text).preserveWhitespace()
            cb.add("val ${rawN(textField.name())}: %T = c.${textFn}($preserve)\n", STRING)
        }
    }

    for (f in fields) cb.add(coerceField(f, convVarFor))

    val emitVerb = if (ctx == Ctx.RECORD) "emit" else "return"
    cb.add("$emitVerb(%T(\n", type)
    cb.indent()
    for (f in fields) cb.add("${f.name()} = ${finalN(f.name())},\n")
    cb.unindent()
    cb.add("))\n")
}
