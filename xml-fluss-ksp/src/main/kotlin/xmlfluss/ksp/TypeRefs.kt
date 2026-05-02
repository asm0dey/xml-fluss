package xmlfluss.ksp

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.MAP
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import xmlfluss.codegen.model.TypeRef

/**
 * Translation between the neutral [TypeRef] carried by core specs and KotlinPoet
 * [TypeName] the KSP emitter renders. Sole place in xml-fluss-ksp that bridges
 * the two type systems; called on demand at emit sites. Mirrors the APT-side
 * `xmlfluss.apt.TypeRefs`.
 */
internal object TypeRefs {

    /**
     * Map a neutral [TypeRef] to a KotlinPoet [TypeName]. Reverses the encoding
     * in [xmlfluss.ksp.spi.KspModelToCore]: Java primitives become the matching
     * Kotlin scalar, `java.lang.String` becomes `kotlin.String`, and
     * `java.util.List`/`java.util.Map` become their `kotlin.collections`
     * counterparts. Unknown `(packageName, simpleName)` pairs become a plain
     * [ClassName] with the dotted simple name decoded into nested-class
     * segments.
     */
    fun toTypeName(ref: TypeRef): TypeName {
        val base: TypeName = when {
            ref.primitive() -> when (ref.simpleName()) {
                "int" -> INT
                "long" -> LONG
                "double" -> DOUBLE
                "boolean" -> BOOLEAN
                else -> error("unsupported primitive: ${ref.simpleName()}")
            }
            // Boxed Java scalars carried by CoreClassifier (CoreClassifier emits these for
            // every scalar field via `TypeRefClassification.boxedScalar`). Map them back
            // to Kotlin types so the emitter doesn't reference `java.lang.Integer` etc.,
            // and so a `Map<String, Int>` field stays `Map<String, Int>` end-to-end.
            ref.packageName() == "java.lang" && ref.simpleName() == "String" -> STRING
            ref.packageName() == "java.lang" && ref.simpleName() == "Integer" -> INT
            ref.packageName() == "java.lang" && ref.simpleName() == "Long" -> LONG
            ref.packageName() == "java.lang" && ref.simpleName() == "Double" -> DOUBLE
            ref.packageName() == "java.lang" && ref.simpleName() == "Boolean" -> BOOLEAN
            ref.packageName() == "java.util" && ref.simpleName() == "List" -> {
                val args = ref.typeArguments().map { toTypeName(it) }
                if (args.isEmpty()) LIST else LIST.parameterizedBy(*args.toTypedArray())
            }
            ref.packageName() == "java.util" && ref.simpleName() == "Map" -> {
                val args = ref.typeArguments().map { toTypeName(it) }
                if (args.isEmpty()) MAP else MAP.parameterizedBy(*args.toTypedArray())
            }
            else -> {
                val cn = toClassName(ref)
                if (ref.typeArguments().isEmpty()) {
                    cn
                } else {
                    val args = ref.typeArguments().map { toTypeName(it) }
                    cn.parameterizedBy(*args.toTypedArray())
                }
            }
        }
        return if (ref.nullable()) base.copy(nullable = true) else base
    }

    /**
     * Decode a possibly dotted simple name (e.g. `Outer.Inner`) into a
     * KotlinPoet [ClassName] with nested-class segments so emitted code
     * addresses inner records via their enclosing type.
     */
    fun toClassName(ref: TypeRef): ClassName {
        val simple = ref.simpleName()
        val dot = simple.indexOf('.')
        return if (dot < 0) {
            ClassName(ref.packageName(), simple)
        } else {
            val parts = simple.split('.')
            ClassName(ref.packageName(), parts.first(), *parts.drop(1).toTypedArray())
        }
    }
}
