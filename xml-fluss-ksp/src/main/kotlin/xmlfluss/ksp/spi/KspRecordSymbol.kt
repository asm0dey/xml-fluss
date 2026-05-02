package xmlfluss.ksp.spi

import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier
import xmlfluss.codegen.model.PolyDispatch
import xmlfluss.codegen.spi.AnnotationView
import xmlfluss.codegen.spi.ComponentSymbol
import xmlfluss.codegen.spi.RecordSymbol

class KspRecordSymbol(private val decl: KSClassDeclaration) : RecordSymbol {

    private val view = KspAnnotationView(decl)

    override fun packageName(): String = decl.packageName.asString()

    /**
     * Walk the enclosing class chain so nested records (data classes nested inside a
     * sealed parent for `@XmlPolymorphic`, or any other nested record) surface their
     * dotted path. Mirrors `KspModelToCore.toTypeRef`'s nested-name encoding so the
     * `(packageName, simpleName)` pair returned here matches the `TypeRef.simpleName`
     * the bridge uses to build KotlinPoet `ClassName`s — without it the emitter would
     * reference `Logout` where `Event.Logout` is required.
     */
    override fun simpleName(): String {
        val chain = generateSequence(decl) { it.parentDeclaration as? KSClassDeclaration }
            .toList()
            .asReversed()
        return chain.joinToString(".") { it.simpleName.asString() }
    }

    override fun qualifiedName(): String = decl.qualifiedName?.asString() ?: simpleName()

    override fun components(): List<ComponentSymbol> {
        val ctor = decl.primaryConstructor ?: return emptyList()
        val propsByName = decl.getAllProperties()
            .associateBy { it.simpleName.asString() }
        return ctor.parameters.mapNotNull { p ->
            val name = p.name?.asString() ?: return@mapNotNull null
            val prop = propsByName[name] ?: return@mapNotNull null
            KspComponentSymbol(prop, p)
        }
    }

    override fun declaredNamespaces(): Map<String, String> {
        val ns = LinkedHashMap<String, String>()
        for (a: KSAnnotation in decl.annotations) {
            val fq = a.annotationType.resolve().declaration.qualifiedName?.asString()
            if (fq != "xmlfluss.XmlNs") continue
            val prefix = a.arguments.firstOrNull { it.name?.asString() == "prefix" }?.value as? String
            val uri = a.arguments.firstOrNull { it.name?.asString() == "uri" }?.value as? String
            if (prefix != null && uri != null) ns[prefix] = uri
        }
        return ns
    }

    override fun declaredPath(): String? = view.stringValue("xmlfluss.XmlRecord", "path")

    // CoreClassifier decodes @XmlPolymorphic directly from AnnotationView; the SPI accessor
    // stays as a stable null so KspRecordSymbol still satisfies the RecordSymbol contract.
    override fun polymorphic(): PolyDispatch? = null

    override fun sealedSubtypes(): List<RecordSymbol> {
        if (Modifier.SEALED !in decl.modifiers) return emptyList()
        // Only data-class subclasses (and nested sealed parents) qualify as polymorphic
        // variants — the parser needs a primary ctor + record shape to materialise. Plain
        // classes among the sealed children are silently filtered so CoreClassifier sees
        // a homogeneous list of records; if that filter empties the list, CoreClassifier
        // surfaces "sealed type … has no permitted subclasses" with the same diagnostic
        // as a sealed parent that genuinely has no children.
        return decl.getSealedSubclasses()
            .filter { Modifier.DATA in it.modifiers || Modifier.SEALED in it.modifiers }
            .map { KspRecordSymbol(it) }
            .toList()
    }

    override fun annotations(): AnnotationView = view

    override fun nativeHandle(): Any = decl
}
