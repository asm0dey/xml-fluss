package xmlfluss.runtime

import xmlfluss.path.CompiledPath
import xmlfluss.path.PathParser

/** Entry point for compiling mini-XPath expressions. Generated parsers call this. */
object Paths {
    /**
     * Compiles [expr] into a [CompiledPath].
     *
     * @param expr the path expression. See [PathParser] for the supported grammar.
     * @param namespaces prefix-to-URI map. The empty key (`""`) sets the default namespace for
     *   bare element names.
     */
    fun compile(expr: String, namespaces: Map<String, String> = emptyMap()): CompiledPath =
        PathParser(nsResolve = { namespaces[it] }, defaultNs = namespaces[""]).parse(expr)
}
