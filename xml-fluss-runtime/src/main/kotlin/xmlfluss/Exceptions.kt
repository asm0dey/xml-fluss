package xmlfluss

/**
 * Position in the source document, attached to every parse error.
 *
 * @property line one-based line number reported by the underlying StAX reader.
 * @property col one-based column number.
 * @property path breadcrumb of element local-names from the document root, for example
 *   `/library/section/shelf/author`. Disambiguates errors when the same element name appears in
 *   several places.
 */
data class Location(val line: Int, val col: Int, val path: String) {
    override fun toString(): String = "$path (line $line, col $col)"
}

/**
 * Base class for parser failures. Generated parsers throw subclasses of this through the
 * `Flow`. The flow terminates on the first error (fail-fast).
 */
sealed class XmlParseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    /**
     * A required field was absent from the document. Thrown when a non-nullable field has no
     * matching attribute, child, or text in the input.
     *
     * @property field the Kotlin field name.
     * @property loc where the missing data should have been.
     */
    class Missing(val field: String, val loc: Location) :
        XmlParseException("Missing required field '$field' at $loc")

    /**
     * A value was found but could not be converted to the declared type.
     *
     * @property field the Kotlin field name.
     * @property raw the text read from the document.
     * @property type the target type's display name (`Int`, `LocalDate`, `Money`, etc.).
     * @property loc the source position of the value.
     */
    class Coercion(val field: String, val raw: String, val type: String, val loc: Location, cause: Throwable) :
        XmlParseException("Cannot coerce '$raw' to $type for '$field' at $loc", cause)

    /**
     * The document is structurally invalid: truncated, mismatched tags, illegal XML, etc.
     *
     * @property loc the position where the parser gave up.
     */
    class Malformed(message: String, val loc: Location, cause: Throwable? = null) :
        XmlParseException("$message at $loc", cause)
}
