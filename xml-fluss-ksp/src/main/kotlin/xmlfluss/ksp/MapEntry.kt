package xmlfluss.ksp

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.STRING
import xmlfluss.codegen.plan.MapPlan
import xmlfluss.codegen.model.FieldSpec as CoreFieldSpec
import xmlfluss.codegen.model.NestedRegistry as CoreNestedRegistry
import xmlfluss.codegen.model.Source as CoreSource

internal fun emitMapEntryCase(
    cb: CodeBlock.Builder,
    f: CoreFieldSpec,
    mp: MapPlan,
    registry: CoreNestedRegistry,
    convVarFor: Map<String, String>,
) {
    // Same invariant as emitMapStateInit: MapAggregate fields always carry both synthetic
    // key/value FieldSpecs.
    val keyF = requireNotNull(f.mapKeyField()) {
        "MapAggregate field ${f.name()} missing synthetic key spec — bug in CoreClassifier"
    }
    val valF = requireNotNull(f.mapValueField()) {
        "MapAggregate field ${f.name()} missing synthetic value spec — bug in CoreClassifier"
    }
    val synthetic = listOf(keyF, valF)

    for (sf in synthetic) {
        val src = sf.source()
        if (src is CoreSource.Attr) {
            val ns = nsLit(src.ns())
            if (sf.isList) {
                cb.add(
                    "val ${listN(sf.name())}: %T = mutableListOf()\n",
                    MUTABLE_LIST.parameterizedBy(STRING)
                )
                cb.add("c.childAttr(%L, %S)?.let { ${listN(sf.name())}.add(it) }\n", ns, src.name())
            } else {
                cb.add(
                    "val ${rawN(sf.name())}: %T = c.childAttr(%L, %S)\n",
                    STRING_NULLABLE, ns, src.name()
                )
            }
        } else {
            emitFieldStateInit(cb, sf)
        }
    }

    if (mp.directRoot().children().isNotEmpty() || mp.descendantByHead().isNotEmpty()) {
        declareSlotsCode(cb, mp.slots())
        cb.beginControlFlow("c.forEachSubrecordChild·{ ln, ns ->\n")
        emitMapEntrySwitch(cb, mp, registry, convVarFor)
        cb.endControlFlow()
    }

    cb.add(coerceField(keyF, convVarFor))
    cb.add(coerceField(valF, convVarFor))

    if (valF.isList) {
        cb.add(
            "${mapN(f.name())}.getOrPut(${finalN(keyF.name())}) { mutableListOf() }.addAll(${finalN(valF.name())})\n"
        )
    } else {
        cb.add("${mapN(f.name())}[${finalN(keyF.name())}] = ${finalN(valF.name())}\n")
    }
    if (fieldNullable(f)) cb.add("${setN(f.name())} = true\n")
}

/**
 * Map-entry child dispatch: pure plan walker over the pre-built [MapPlan]. MapPlan has no
 * map-of-map or polymorphic children.
 */
internal fun emitMapEntrySwitch(
    cb: CodeBlock.Builder,
    mp: MapPlan,
    registry: CoreNestedRegistry,
    convVarFor: Map<String, String>,
) {
    emitChildSwitchCore(
        cb,
        mp.directRoot(),
        mp.slots(),
        mp.descendantByHead(),
        mp.tailTries(),
        emptyMap(),
        emptyList(),
        registry, convVarFor,
    )
}
