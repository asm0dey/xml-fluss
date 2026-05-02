package xmlfluss.ksp.spi

import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSType
import xmlfluss.codegen.model.TypeRef
import xmlfluss.codegen.spi.AnnotationView

/** KSP-side [AnnotationView] backed by [KSAnnotated.annotations]. */
class KspAnnotationView(private val annotated: KSAnnotated) : AnnotationView {

    override fun has(fqn: String): Boolean = findAnnotation(fqn) != null

    override fun stringValue(fqn: String, attr: String): String? {
        val v = readArg(fqn, attr) ?: return null
        return v as? String
    }

    @Suppress("UNCHECKED_CAST")
    override fun stringArrayValue(fqn: String, attr: String): List<String>? {
        val v = readArg(fqn, attr) ?: return null
        if (v is List<*> && v.all { it is String }) return v as List<String>
        return null
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

    private fun findAnnotation(fqn: String): KSAnnotation? =
        annotated.annotations.firstOrNull {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == fqn
        }

    private fun readArg(fqn: String, attr: String): Any? {
        val a = findAnnotation(fqn) ?: return null
        return a.arguments.firstOrNull { it.name?.asString() == attr }?.value
    }
}
