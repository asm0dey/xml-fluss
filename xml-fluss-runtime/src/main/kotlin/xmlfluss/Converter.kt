package xmlfluss

/**
 * Turns raw XML text into a value of [T]. Wire it up by annotating a field with
 * [XmlConverter]`(MyConverter::class)`.
 *
 * Implementations must have a no-arg constructor. Throw [XmlParseException.Coercion] (with the
 * supplied [Location]) when the input cannot be parsed. Any other thrown exception is wrapped
 * by the runtime.
 */
interface Converter<T> {
    /**
     * @param raw the trimmed text content (or attribute value) read from the document.
     * @param loc the position in the source document, useful for error messages.
     * @return the converted value.
     */
    fun convert(raw: String, loc: Location): T
}
