package xmlfluss.ksp.spi

import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier
import xmlfluss.codegen.spi.DiagnosticReporter
import xmlfluss.codegen.spi.RecordSymbol
import xmlfluss.codegen.spi.SymbolProvider
import xmlfluss.codegen.spi.TypeSymbol

class KspSymbolProvider(
    private val resolver: Resolver,
    logger: KSPLogger,
) : SymbolProvider {

    private val reporter = KspDiagnosticReporter(logger)

    override fun lookupRecord(fqn: String): RecordSymbol? {
        val decl = resolver.getClassDeclarationByName(fqn) ?: return null
        // Accept Kotlin data classes (the KSP analog of Java records) and sealed
        // class/interface parents that may carry @XmlPolymorphic. Mirrors
        // AptSymbolProvider.lookupRecord which silently returns null for any other
        // kind: CoreClassifier uses lookupRecord both as a top-level entry point
        // and as a type-shape probe (e.g. polymorphic-parent detection on a field's
        // element type), so emitting a diagnostic here would fire on every scalar
        // or non-record type the classifier considers. The "@XmlRecord requires
        // data class" diagnostic for top-level annotated classes lives in
        // XmlDslProcessor.generate where it's only invoked once per @XmlRecord.
        val isDataClass = Modifier.DATA in decl.modifiers
        val isSealedParent = Modifier.SEALED in decl.modifiers
        if (!isDataClass && !isSealedParent) {
            return null
        }
        // Reject conflicting @XmlNs declarations on the same record. Mirrors
        // AptSymbolProvider.validateOwnNamespaces; the merged-map view returned by
        // KspRecordSymbol.declaredNamespaces() silently keeps the last entry, so we
        // inspect the raw KSAnnotation list here to surface the diagnostic at the SPI
        // layer where CoreClassifier expects it.
        if (!validateOwnNamespaces(decl)) return null
        return KspRecordSymbol(decl)
    }

    override fun lookupType(fqn: String): TypeSymbol? {
        // Mirrors AptSymbolProvider.lookupType: resolve via the host's name service and
        // wrap in a KspTypeSymbol. Unknown names return null per the SPI contract; the
        // caller (CoreClassifier converter validation) raises the diagnostic.
        val decl = resolver.getClassDeclarationByName(fqn) ?: return null
        return KspTypeSymbol(decl)
    }

    override fun diagnostics(): DiagnosticReporter = reporter

    private fun validateOwnNamespaces(decl: KSClassDeclaration): Boolean {
        val seen = LinkedHashMap<String, String>()
        for (a in decl.annotations) {
            val fq = a.annotationType.resolve().declaration.qualifiedName?.asString()
            when (fq) {
                FQ_XML_NS -> if (!checkNs(decl, a, seen)) return false
                FQ_XML_NAMESPACES -> {
                    val raw = a.arguments.firstOrNull { it.name?.asString() == "value" }?.value
                    if (raw is List<*>) {
                        for (entry in raw) {
                            val inner = entry as? KSAnnotation ?: continue
                            if (!checkNs(decl, inner, seen)) return false
                        }
                    }
                }
            }
        }
        return true
    }

    private fun checkNs(
        owner: KSClassDeclaration,
        ann: KSAnnotation,
        sink: MutableMap<String, String>,
    ): Boolean {
        val prefix = ann.arguments.firstOrNull { it.name?.asString() == "prefix" }?.value as? String
            ?: return true
        val uri = ann.arguments.firstOrNull { it.name?.asString() == "uri" }?.value as? String
            ?: return true
        val prior = sink[prefix]
        if (prior != null && prior != uri) {
            reporter.error(
                owner,
                "@XmlNs prefix '$prefix' bound to two URIs: '$prior' and '$uri'",
            )
            return false
        }
        sink[prefix] = uri
        return true
    }

    private companion object {
        const val FQ_XML_NS = "xmlfluss.XmlNs"
        const val FQ_XML_NAMESPACES = "xmlfluss.XmlNamespaces"
    }
}
