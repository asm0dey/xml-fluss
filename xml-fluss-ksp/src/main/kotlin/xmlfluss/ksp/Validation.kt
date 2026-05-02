package xmlfluss.ksp

/**
 * Sealed marker for diagnostics that originate from user input (annotation
 * misuse, malformed paths, type-shape rules). Caught by [XmlDslProcessor.process]
 * and rendered as concise KSP errors. Anything else escaping `generate` is
 * treated as an internal bug and reported with a full stacktrace.
 */
internal sealed class ProcessorValidationException(message: String) : RuntimeException(message)
internal class ValidationError(message: String) : ProcessorValidationException(message)

internal inline fun vRequire(cond: Boolean, msg: () -> String) {
    if (!cond) throw ValidationError(msg())
}
