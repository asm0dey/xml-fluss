package xmlfluss.ksp.spi

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Variance
import xmlfluss.codegen.model.TypeRef

private const val JAVA_LANG_PKG = "java.lang"

/** Maps a [KSType] (after `resolve()`) to a neutral [TypeRef]. */
internal object KspModelToCore {

    private val PRIMITIVE_FQNS = setOf(
        "kotlin.Int", "kotlin.Long", "kotlin.Double", "kotlin.Boolean",
        "kotlin.Float", "kotlin.Short", "kotlin.Byte", "kotlin.Char"
    )
    private val PRIMITIVE_SIMPLE = mapOf(
        "kotlin.Int" to "int",
        "kotlin.Long" to "long",
        "kotlin.Double" to "double",
        "kotlin.Boolean" to "boolean",
        "kotlin.Float" to "float",
        "kotlin.Short" to "short",
        "kotlin.Byte" to "byte",
        "kotlin.Char" to "char",
    )

    fun toTypeRef(type: KSType): TypeRef {
        val ref = toTypeRefRaw(type)
        // Propagate Kotlin's `?` so type-argument nullability survives the trip to
        // CoreClassifier: e.g. `List<String?>` in a Map value field needs to surface the
        // inner `String?` so CoreClassifier's "nullable element inside List" check fires.
        return if (type.isMarkedNullable) ref.asNullable() else ref
    }

    private fun toTypeRefRaw(type: KSType): TypeRef {
        val fqn = type.declaration.qualifiedName?.asString()
            ?: error("KSType without qualifiedName cannot be mapped")
        if (fqn in PRIMITIVE_FQNS) {
            return TypeRef.ofPrimitive(PRIMITIVE_SIMPLE.getValue(fqn))
        }
        if (fqn == "kotlin.String") return TypeRef.of(JAVA_LANG_PKG, "String")
        if (fqn == "kotlin.collections.List") {
            val args = type.arguments.map { arg ->
                arg.type?.resolve()?.let { toTypeRef(it) }
                    ?: TypeRef.of(JAVA_LANG_PKG, "Object")
            }
            return TypeRef.parameterized("java.util", "List", args)
        }
        // CoreClassifier checks `isJavaUtilMap` against `java.util.Map`; Kotlin source-level
        // `Map<K, V>` resolves through KSP as `kotlin.collections.Map`. Mirror the List
        // mapping so the @XmlMap branch sees the same fqn the APT side reports.
        if (fqn == "kotlin.collections.Map") {
            val args = type.arguments.map { arg ->
                arg.type?.resolve()?.let { toTypeRef(it) }
                    ?: TypeRef.of(JAVA_LANG_PKG, "Object")
            }
            return TypeRef.parameterized("java.util", "Map", args)
        }
        // For classes (including nested ones) walk the parentDeclaration chain so that
        // `pkg.Outer.Inner` is encoded as package=`pkg`, simpleName=`Outer.Inner`. This
        // mirrors `AptModelToCore.toTypeRef`'s use of dotted simple names; the
        // CoreToModelBridge / KspToModelBridge decode the dot back into a nested class
        // reference. Falling back to fqn-based slicing keeps non-class declarations
        // (typealiases, etc.) safe.
        val decl = type.declaration
        val (pkg, simple) = if (decl is KSClassDeclaration) {
            decl.packageName.asString() to nestedSimpleName(decl)
        } else {
            fqn.substringBeforeLast('.', "") to fqn.substringAfterLast('.')
        }
        if (type.arguments.isEmpty()) return TypeRef.of(pkg, simple)
        val args = type.arguments.map { arg ->
            when (arg.variance) {
                Variance.STAR -> TypeRef.of(JAVA_LANG_PKG, "Object")
                else -> arg.type?.resolve()?.let { toTypeRef(it) }
                    ?: TypeRef.of(JAVA_LANG_PKG, "Object")
            }
        }
        return TypeRef.parameterized(pkg, simple, args)
    }

    private fun nestedSimpleName(decl: KSClassDeclaration): String {
        val chain = generateSequence(decl) { it.parentDeclaration as? KSClassDeclaration }
            .toList()
            .asReversed()
        return chain.joinToString(".") { it.simpleName.asString() }
    }
}
