package xmlfluss.ksp

import com.squareup.kotlinpoet.CodeBlock

/**
 * Recurring CodeBlock fragments and local-variable name builders used across
 * emitter files. Names are prefixed with `__` so they cannot collide with
 * user-declared record fields (e.g. a record field literally named `t` would
 * otherwise shadow `__t`).
 */

/** `null` or `"<ns>"` — appears at every cursor accessor that takes a namespace argument. */
internal fun nsLit(ns: String?): CodeBlock =
    if (ns == null) CodeBlock.of("null") else CodeBlock.of("%S", ns)

/** `throw XmlParseException.Missing("<field>", __loc)` — used by [coerceField] for required fields. */
internal fun missingThrow(name: String): CodeBlock =
    CodeBlock.of("throw %T(%S, __loc)", MISSING_EX, name)

/**
 * `local if (ns == null || c.ignoreNamespace)` (or `ns == "<ns>"` when an `XmlNs` is bound).
 * Used inside `when(ln)` branches to match a child element by qualified name. [nsVar] lets
 * descendant-axis emission swap to `dns` for the inner cursor's namespace local.
 */
internal fun qnameCond(ns: String?, local: String, nsVar: String = "ns"): CodeBlock =
    if (ns == null) CodeBlock.of("%S if ($nsVar == null || c.ignoreNamespace)", local)
    else CodeBlock.of("%S if ($nsVar == %S || c.ignoreNamespace)", local, ns)

/* Local-name builders. Pure string ops; centralised so renames stay safe. */

internal fun rawN(name: String) = "__raw_$name"
internal fun setN(name: String) = "__set_$name"
internal fun listN(name: String) = "__list_$name"
internal fun nestedN(name: String) = "__nested_$name"
internal fun locN(name: String) = "__loc_$name"
internal fun finalN(name: String) = "__final_$name"
internal fun mapN(name: String) = "__map_$name"
internal fun discN(name: String) = "__disc_$name"
internal fun nN(name: String) = "__n_$name"
