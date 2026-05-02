package xmlfluss.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier
import xmlfluss.codegen.classify.CoreClassifier
import xmlfluss.codegen.plan.DispatchPlanBuilder

/**
 * KSP processor that turns `@XmlRecord` data classes into streaming parsers.
 *
 * For each annotated class the processor:
 *
 * 1. Resolves `@XmlNs` declarations and compiles the record path with
 *    [xmlfluss.runtime.Paths.compile].
 * 2. Walks the primary constructor parameters, classifying each as `@XmlAttr`, `@XmlChild`,
 *    `@XmlText`, `@XmlMap`, or polymorphic (sealed parent annotated with `@XmlPolymorphic` +
 *    `@XmlSubtype` data-class variants), and resolves the field's coercion (scalar, temporal,
 *    decimal, custom converter, nested data class, map aggregate, or sealed dispatch).
 * 3. Emits a `${ClassName}Parser` Kotlin object via KotlinPoet, exposing
 *    `parse(InputStream): Flow<T>` plus private helpers for nested types.
 *
 * Generated parsers drive [xmlfluss.runtime.XmlReadCursor]. No reflection is used at runtime.
 *
 * The class itself is intentionally thin: the heavy lifting lives in the per-concern emitter
 * files in this package (see `EmitFile.kt`, `EmitInstance.kt`, `ChildSwitch.kt`, etc.).
 */
class XmlDslProcessor(env: SymbolProcessorEnvironment) : SymbolProcessor {

    private val codeGen: CodeGenerator = env.codeGenerator
    private val logger: KSPLogger = env.logger

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(XML_RECORD_FQ)
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        val symbolProvider = xmlfluss.ksp.spi.KspSymbolProvider(resolver, logger)
        for (cls in symbols) {
            try {
                // Drive the field model through the shared codegen-core classifier and walk
                // the neutral RecordSpec directly — TypeRefs handles JavaPoet/KotlinPoet
                // conversions on demand at emit sites.
                generate(cls, symbolProvider)
            } catch (e: ProcessorValidationException) {
                logger.error("xml-fluss-ksp: ${e.message}", cls)
            } catch (e: Throwable) {
                logger.error("xml-fluss-ksp: ${e.stackTraceToString()}", cls)
            }
        }
        return emptyList()
    }

    private fun generate(cls: KSClassDeclaration, symbolProvider: xmlfluss.ksp.spi.KspSymbolProvider) {
        // The SPI's KspSymbolProvider.lookupRecord() owns the kind check (data class OR sealed
        // parent for @XmlPolymorphic); a non-matching kind returns null after emitting the
        // diagnostic. Widen the local guard accordingly so sealed-root @XmlRecord cases don't
        // get rejected here before reaching CoreClassifier.
        if (Modifier.DATA !in cls.modifiers && Modifier.SEALED !in cls.modifiers) {
            logger.error("@XmlRecord requires data class", cls)
            return
        }
        val fqn = cls.qualifiedName?.asString() ?: return
        val symbol = symbolProvider.lookupRecord(fqn) ?: return
        val core = CoreClassifier(symbolProvider)
        val coreSpec = core.classify(symbol) ?: return
        val plan = DispatchPlanBuilder.build(coreSpec, core.registry())
        emitFile(coreSpec, plan, core.registry(), codeGen)
    }
}
