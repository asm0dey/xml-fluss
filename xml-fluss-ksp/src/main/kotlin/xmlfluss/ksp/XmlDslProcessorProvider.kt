package xmlfluss.ksp

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

/**
 * KSP entry point. Hook this provider into a Gradle build via the `ksp` configuration:
 *
 * ```kotlin
 * dependencies {
 *     ksp("site.asm0dey.xmlfluss:xml-fluss-ksp:VERSION")
 * }
 * ```
 *
 * Each `@XmlRecord` data class in the module then yields a generated `${ClassName}Parser` object.
 */
class XmlDslProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        XmlDslProcessor(environment)
}
