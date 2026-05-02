package xmlfluss.ksp

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import xmlfluss.codegen.model.Coerce as CoreCoerce
import xmlfluss.codegen.model.FieldSpec as CoreFieldSpec
import xmlfluss.codegen.model.ScalarKind as CoreScalarKind
import xmlfluss.codegen.model.Source as CoreSource

internal fun coerceField(f: CoreFieldSpec, convVarFor: Map<String, String>): CodeBlock {
    val cb = CodeBlock.builder()
    cb.add("val ${finalN(f.name())}: %T = ", fieldTypeName(f))

    val nullable = fieldNullable(f)

    if (f.coerce() is CoreCoerce.MapAggregate) {
        if (nullable) cb.add("if (!${setN(f.name())}) null else ${mapN(f.name())}\n")
        else cb.add("${mapN(f.name())}\n")
        return cb.build()
    }

    if (f.coerce() is CoreCoerce.Nested) {
        if (f.isList) {
            cb.add("${listN(f.name())}\n")
        } else {
            if (nullable) {
                cb.add("if (!${setN(f.name())}) null else ${nestedN(f.name())}\n")
            } else {
                cb.add("if (!${setN(f.name())}) %L else ${nestedN(f.name())}!!\n", missingThrow(f.name()))
            }
        }
        return cb.build()
    }

    if (f.isList) {
        cb.add("${listN(f.name())}.map { __r -> ")
        cb.add(coerceRaw(f, CodeBlock.of("__r"), convVarFor))
        cb.add(" }\n")
        return cb.build()
    }

    val rawVar = CodeBlock.of(rawN(f.name()))
    val miss = missingThrow(f.name())
    val orMissing = CodeBlock.of("(%L ?: %L)", rawVar, miss)
    val orEmpty = CodeBlock.of("(%L ?: \"\")", rawVar)

    // Source.MapEntry filtered above via Coerce.MapAggregate; Source.PolyChild filtered via
    // Coerce.Nested. Remaining sources: Attr, Text, Child.
    when (f.source()) {
        is CoreSource.Attr -> {
            if (nullable) {
                // For AsString the if/else collapses to a no-op pass-through (`raw ?: raw`);
                // emit the raw nullable directly so kotlinc doesn't warn IfThenToSafeAccess.
                if (f.coerce() is CoreCoerce.AsString) {
                    cb.add(rawVar)
                } else {
                    cb.add("if (%L == null) null else ", rawVar)
                    cb.add(coerceRaw(f, rawVar, convVarFor))
                }
            } else {
                cb.add(coerceRaw(f, orMissing, convVarFor))
            }
        }

        is CoreSource.Text -> {
            // __raw_X is declared as non-null String at the recordText/subrecordText call,
            // and that call runs unconditionally for any record carrying an @XmlText field.
            // No __set_X gate needed: nullable @XmlText still binds whatever the cursor
            // produced (empty string for an empty body).
            cb.add(coerceRaw(f, rawVar, convVarFor))
        }

        is CoreSource.Child -> {
            val effLoc =
                if (needsChildLoc(f)) CodeBlock.of("(${locN(f.name())} ?: __loc)")
                else CodeBlock.of("__loc")
            if (nullable) {
                cb.add("if (!${setN(f.name())}) null else ")
                cb.add(coerceRaw(f, orEmpty, convVarFor, effLoc))
            } else {
                cb.add("if (!${setN(f.name())}) %L else ", miss)
                cb.add(coerceRaw(f, orEmpty, convVarFor, effLoc))
            }
        }

        else -> Unit
    }
    cb.add("\n")
    return cb.build()
}

private fun coerceRaw(
    f: CoreFieldSpec,
    raw: CodeBlock,
    convVarFor: Map<String, String>,
    lcExpr: CodeBlock = CodeBlock.of("__loc"),
): CodeBlock {
    val nl = CodeBlock.of("%S", f.name())
    // Coerce.MapAggregate and Coerce.Nested are filtered upstream in coerceField; reaching
    // them here is impossible, so they're not enumerated in this when.
    return when (val coerce = f.coerce()) {
        is CoreCoerce.AsString -> raw
        is CoreCoerce.Scalar -> CodeBlock.of("%M(%L, %L, %L)", scalarMember(coerce.kind()), nl, raw, lcExpr)
        is CoreCoerce.Temporal -> {
            val m = when (coerce.kind()) {
                CoreScalarKind.LOCAL_DATE -> COERCE_LOCAL_DATE
                CoreScalarKind.LOCAL_DATE_TIME -> COERCE_LOCAL_DATE_TIME
                CoreScalarKind.INSTANT -> COERCE_INSTANT
                else -> error("non-temporal ScalarKind in Coerce.Temporal: ${coerce.kind()}")
            }
            CodeBlock.of("%M(%L, %L, %S, %L)", m, nl, raw, coerce.pattern() ?: "", lcExpr)
        }

        is CoreCoerce.Decimal -> CodeBlock.of(
            "%M(%L, %L, %S, %L)", COERCE_BIG_DECIMAL, nl, raw, coerce.pattern() ?: "", lcExpr,
        )
        is CoreCoerce.Custom -> {
            // registerConverter pre-populates convVarFor for every Coerce.Custom field; lookup
            // is total here.
            val cls = TypeRefs.toClassName(coerce.converterClass())
            val varName = convVarFor.getValue(cls.canonicalName)
            CodeBlock.of("$varName.convert(%L, %L)", raw, lcExpr)
        }

        else -> error("unreachable: Coerce.MapAggregate / Coerce.Nested filtered before coerceRaw")
    }
}

/**
 * Map a neutral [CoreScalarKind] back to the runtime coercion member function. Mirrors the
 * APT side. Core's [CoreScalarKind.STRING] is unreachable through [CoreCoerce.Scalar] because
 * CoreClassifier routes string coercion through [CoreCoerce.AsString]; same for BIG_DECIMAL
 * (routed through [CoreCoerce.Decimal]) and the temporal kinds (routed through
 * [CoreCoerce.Temporal]). The else branch therefore signals a classifier bug rather than a
 * missing mapping.
 */
private fun scalarMember(kind: CoreScalarKind): MemberName = when (kind) {
    CoreScalarKind.INT -> COERCE_INT
    CoreScalarKind.LONG -> COERCE_LONG
    CoreScalarKind.DOUBLE -> COERCE_DOUBLE
    CoreScalarKind.BOOLEAN -> COERCE_BOOLEAN
    else -> error("Coerce.Scalar carries non-numeric ScalarKind $kind; CoreClassifier should route this through Decimal/Temporal/AsString")
}
