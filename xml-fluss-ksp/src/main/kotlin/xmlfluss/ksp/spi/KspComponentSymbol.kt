package xmlfluss.ksp.spi

import com.google.devtools.ksp.symbol.*
import xmlfluss.codegen.model.TypeRef
import xmlfluss.codegen.spi.AnnotationView
import xmlfluss.codegen.spi.ComponentSymbol
import xmlfluss.codegen.spi.RecordSymbol

class KspComponentSymbol(
    private val property: KSPropertyDeclaration,
    private val parameter: KSValueParameter? = null,
) : ComponentSymbol {

    private val resolved: KSType = property.type.resolve()
    private val view = KspMergedAnnotationView(property, parameter)

    override fun name(): String = property.simpleName.asString()

    override fun type(): TypeRef = KspModelToCore.toTypeRef(resolved)

    /**
     * Kotlin's type system is the source of truth for nullability — unlike Java, every
     * type reference is either explicitly nullable (`String?`) or explicitly non-nullable
     * (`String`). The JSpecify scope walk that APT relies on (`@NullMarked` /
     * `@NullUnmarked` / `@Nullable` on the component) only exists to compensate for
     * Java's lack of a nullability marker; replaying it here would override Kotlin's
     * own answer and produce nullable parsers for plain `String` fields.
     *
     * Order:
     *   1. explicit `@org.jspecify.annotations.NonNull` on property OR ctor parameter → non-null
     *   2. explicit `@org.jspecify.annotations.Nullable` on property OR ctor parameter → nullable
     *   3. Kotlin `?` (`isMarkedNullable`) → nullable
     *   4. otherwise → non-null (Kotlin's default for `String` / nested classes / `List<>`)
     *
     * Walking BOTH the property declaration and the ctor parameter is required because Kotlin
     * data-class annotations land on whichever use-site target the author picked (default for
     * ctor params is `@param:`, but `@property:` and `@get:` are also valid).
     */
    override fun nullable(): Boolean {
        if (hasExplicitAnnotation(FQ_NON_NULL)) return false
        if (hasExplicitAnnotation(FQ_NULLABLE)) return true
        return resolved.isMarkedNullable
    }

    /**
     * Checks every surface where Kotlin can land a JSpecify annotation:
     *   1. the property declaration itself (`@property:` / `@field:` / default in some configs);
     *   2. the property's type reference (the type-use position `val x: @NonNull String`);
     *   3. the ctor value parameter (`@param:` — Kotlin's default for ctor-param annotations);
     *   4. the ctor parameter's type reference.
     * APT walks both the component element AND the accessor return type for the same reason —
     * this method is the KSP analog of `hasAnn` + `hasTypeUseAnn` in `AptComponentSymbol`.
     */
    private fun hasExplicitAnnotation(fqn: String): Boolean {
        if (hasAnnotation(property, fqn)) return true
        if (hasAnnotation(property.type, fqn)) return true
        if (parameter != null) {
            if (hasAnnotation(parameter, fqn)) return true
            if (hasAnnotation(parameter.type, fqn)) return true
        }
        return false
    }

    private fun hasAnnotation(target: KSAnnotated, fqn: String): Boolean =
        target.annotations.any {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == fqn
        }

    private companion object {
        const val FQ_NON_NULL = "org.jspecify.annotations.NonNull"
        const val FQ_NULLABLE = "org.jspecify.annotations.Nullable"
    }

    override fun isList(): Boolean =
        resolved.declaration.qualifiedName?.asString() == "kotlin.collections.List"

    override fun elementType(): TypeRef =
        if (isList()) {
            resolved.arguments.firstOrNull()?.type?.resolve()
                ?.let { KspModelToCore.toTypeRef(it) }
                ?: TypeRef.of("java.lang", "Object")
        } else type()

    override fun asNestedRecord(): RecordSymbol? {
        // Mirror AptComponentSymbol.asNestedRecord: any record-shaped element type counts as a
        // nested record candidate, regardless of whether it carries @XmlRecord. The classifier
        // treats nested data classes (Kotlin's record analog) the same as top-level records,
        // and the original KSP-side `isNestedDataClass` helper used Modifier.DATA alone.
        val target = if (isList()) resolved.arguments.firstOrNull()?.type?.resolve() else resolved
        if (target == null) return null
        val decl = target.declaration as? KSClassDeclaration ?: return null
        return if (com.google.devtools.ksp.symbol.Modifier.DATA in decl.modifiers) KspRecordSymbol(decl) else null
    }

    override fun annotations(): AnnotationView = view

    override fun nativeHandle(): Any = property
}
