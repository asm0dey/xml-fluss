package xmlfluss.ksp

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import xmlfluss.codegen.model.Coerce as CoreCoerce
import xmlfluss.codegen.model.FieldSpec as CoreFieldSpec
import xmlfluss.codegen.model.Source as CoreSource

/**
 * KSP-side "is this field nullable in Kotlin source"? Lists are always non-null on
 * the Kotlin side (the runtime contract is "no matches → empty list"); CoreFieldSpec
 * stores `required = !nullable && !isList`, so a List field reports `required = false`
 * even though Lists never carry the `?` marker. Treat list fields as non-nullable to
 * keep the emitted KotlinPoet type stable; only scalar/nested fields honour the
 * required flag for their `?` marker.
 */
internal fun fieldNullable(f: CoreFieldSpec): Boolean = !f.required() && !f.isList

internal fun fieldTypeName(f: CoreFieldSpec): TypeName {
    val base = TypeRefs.toTypeName(f.fieldType())
    return if (fieldNullable(f)) base.copy(nullable = true) else base
}

internal fun elemTypeName(f: CoreFieldSpec): TypeName = TypeRefs.toTypeName(f.elemType())

/**
 * `__loc_X` is read only by [coerceField] when the child field's `coerceRaw` consumes the
 * `lc` argument — i.e. for Scalar/Temporal/Decimal/Custom coercions on a single (non-list)
 * `Source.Child`. `AsString`, `Nested`, and list paths never reference `__loc_X`, so emitting
 * it would be dead code.
 */
internal fun needsChildLoc(f: CoreFieldSpec): Boolean =
    f.source() is CoreSource.Child && !f.isList &&
        f.coerce() !is CoreCoerce.AsString && f.coerce() !is CoreCoerce.Nested

internal fun emitFieldStateInit(cb: CodeBlock.Builder, f: CoreFieldSpec) {
    // Source.Text is declared and assigned in one go at the recordText/subrecordText call
    // site (see emitParseBody). No state needed up front — the cursor always provides a
    // String, so the early `var __raw_X: String? = null` would just be a dead initializer.
    if (f.source() is CoreSource.Text) return
    when {
        f.coerce() is CoreCoerce.Nested && f.isList -> {
            cb.add(
                "val ${listN(f.name())}: %T = mutableListOf()\n",
                MUTABLE_LIST.parameterizedBy(elemTypeName(f))
            )
        }

        f.coerce() is CoreCoerce.Nested -> {
            cb.add("var ${setN(f.name())}: %T = false\n", BOOLEAN)
            cb.add(
                "var ${nestedN(f.name())}: %T = null\n",
                elemTypeName(f).copy(nullable = true)
            )
        }

        f.isList -> {
            cb.add(
                "val ${listN(f.name())}: %T = mutableListOf()\n",
                MUTABLE_LIST.parameterizedBy(STRING)
            )
        }

        else -> {
            cb.add("var ${setN(f.name())}: %T = false\n", BOOLEAN)
            cb.add("var ${rawN(f.name())}: %T = null\n", STRING_NULLABLE)
            if (needsChildLoc(f)) {
                cb.add("var ${locN(f.name())}: %T = null\n", LOCATION_NULLABLE)
            }
        }
    }
}

internal fun emitMapStateInit(cb: CodeBlock.Builder, f: CoreFieldSpec) {
    // emitMapStateInit only runs for Coerce.MapAggregate fields built by classifyMapParam,
    // which always sets mapKeyField / mapValueField.
    val keyF = requireNotNull(f.mapKeyField()) {
        "MapAggregate field ${f.name()} missing synthetic key spec — bug in CoreClassifier"
    }
    val valF = requireNotNull(f.mapValueField()) {
        "MapAggregate field ${f.name()} missing synthetic value spec — bug in CoreClassifier"
    }
    val storedValueType: TypeName =
        if (valF.isList) MUTABLE_LIST.parameterizedBy(elemTypeName(valF)) else fieldTypeName(valF)
    val storeType = MUTABLE_MAP.parameterizedBy(fieldTypeName(keyF), storedValueType)
    cb.add("val ${mapN(f.name())}: %T = %M()\n", storeType, LINKED_MAP_OF)
    if (fieldNullable(f)) cb.add("var ${setN(f.name())}: %T = false\n", BOOLEAN)
}
