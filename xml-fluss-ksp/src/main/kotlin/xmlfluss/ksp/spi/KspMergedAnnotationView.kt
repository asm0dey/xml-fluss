package xmlfluss.ksp.spi

import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSType
import xmlfluss.codegen.model.TypeRef
import xmlfluss.codegen.spi.AnnotationView

/**
 * An [AnnotationView] that merges annotations from two [KSAnnotated] sources.
 *
 * Kotlin data-class constructor parameters may carry their annotations on the
 * value-parameter symbol rather than the backing property symbol (the default
 * use-site target in Kotlin is `@param:` for constructor parameters). This view
 * checks both sources so that callers don't need to know which target was used.
 */
class KspMergedAnnotationView(
    private val primary: KSAnnotated,
    private val secondary: KSAnnotated?,
) : AnnotationView {

    override fun has(fqn: String): Boolean = findAnnotation(fqn) != null

    override fun stringValue(fqn: String, attr: String): String? {
        val v = readArg(fqn, attr) ?: return null
        return v as? String
    }

    override fun classValue(fqn: String, attr: String): TypeRef? {
        val v = readArg(fqn, attr) ?: return null
        val type = v as? KSType ?: return null
        return KspModelToCore.toTypeRef(type)
    }

    override fun booleanValue(fqn: String, attr: String): Boolean? {
        val v = readArg(fqn, attr) ?: return null
        return v as? Boolean
    }

    private fun findAnnotation(fqn: String): KSAnnotation? {
        fun KSAnnotated.findIn(): KSAnnotation? =
            annotations.firstOrNull {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == fqn
            }
        return primary.findIn() ?: secondary?.findIn()
    }

    private fun readArg(fqn: String, attr: String): Any? {
        val a = findAnnotation(fqn) ?: return null
        return a.arguments.firstOrNull { it.name?.asString() == attr }?.value
    }
}
