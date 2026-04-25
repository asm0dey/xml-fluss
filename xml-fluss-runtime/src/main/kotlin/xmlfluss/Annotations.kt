package xmlfluss

import kotlin.reflect.KClass

/**
 * Marks a data class as a parseable record.
 *
 * KSP picks up the annotation and generates a `${ClassName}Parser` object with a
 * `parse(InputStream): Flow<T>` function. The flow emits one instance per element matched by
 * [path].
 *
 * @param path a mini-XPath expression locating the record element. Supported forms:
 *   - `//author`: descendant axis, matches `author` at any depth.
 *   - `/library/section/author`: absolute path from the document root.
 *   - `authors/author`: relative path. A leading descendant axis is added automatically.
 *   - `//author[@role='main']`: predicate filter on an attribute.
 *   - `//{atom}entry` or `//atom:entry`: namespaced element. Prefixes resolve via [XmlNs].
 *   - `//{*}author`: matches `author` in any namespace.
 *
 * Predicates support `=`, `!=`, `and`, `or`, and integer position (e.g. `[2]`). They run at
 * `START_ELEMENT` time. XPath functions and `text()=` are not supported.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class XmlRecord(val path: String)

/**
 * Binds a namespace prefix to a URI for the annotated record class.
 *
 * Repeat the annotation to declare several prefixes. Pass `""` as [prefix] to set the default
 * namespace; bare element names in [XmlChild] paths and the [XmlRecord] path then resolve to that
 * URI. Attributes stay in the null namespace by default.
 *
 * Nested data classes inherit their enclosing record's prefix bindings; a nested class only needs
 * its own [XmlNs] when it adds a new prefix. Redeclaring a prefix the parent already binds is
 * allowed only if it maps to the same URI — a different URI is a build error (so the same `x:` in
 * source code never resolves to two different namespaces). A nested type re-used from two records
 * with incompatible effective namespace maps is also a build error.
 *
 * Example:
 * ```
 * @XmlRecord("//atom:entry")
 * @XmlNs("atom", "http://www.w3.org/2005/Atom")
 * data class Entry(...)
 * ```
 *
 * @param prefix the prefix used inside paths, or `""` for the default namespace.
 * @param uri the namespace URI the prefix expands to.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
annotation class XmlNs(val prefix: String, val uri: String)

/**
 * Container for repeated [XmlNs] annotations. The Kotlin compiler synthesises this when a class
 * carries more than one [XmlNs]. You normally don't write it directly.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class XmlNamespaces(vararg val value: XmlNs)

/**
 * Reads an attribute from the record (or nested) element into the annotated field.
 *
 * Bare `name` resolves in the null namespace (the class's default [XmlNs] does not apply to
 * attributes — that is the XML spec). Pass `prefix:local` to read a namespaced attribute; the
 * prefix resolves via the class-level [XmlNs] declarations (e.g. `@XmlAttr("xml:lang")` after
 * `@XmlNs("xml", "http://www.w3.org/XML/1998/namespace")`).
 *
 * A non-nullable field with the attribute absent triggers [XmlParseException.Missing]. A nullable
 * field gets `null` instead.
 *
 * If [name] is left empty, the attribute name defaults to the field's own Kotlin identifier — so
 * `@XmlAttr val href: String` is equivalent to `@XmlAttr("href") val href: String`. Use the
 * explicit form when the XML name differs from the field name (`@XmlAttr("xml:lang") val lang`).
 *
 * @param name local name of the attribute, optionally `prefix:local`. Empty string = use field name.
 */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class XmlAttr(val name: String = "")

/**
 * Reads a child element (or its attribute) into the annotated field. Multi-segment paths walk
 * intermediate wrapper elements; the descendant axis skips them.
 *
 * Path syntax:
 * - `name`: direct child named `name` of the record (or nested) element.
 * - `wrapper/name`: descend through `wrapper`, then match its direct child `name`.
 * - `name/@attr`: read attribute `attr` from the matched child element.
 * - `prefix:name`: resolve `prefix` against the class-level [XmlNs] declarations.
 * - `//name`: descendant axis. Matches `name` at any depth inside the enclosing element, so
 *   wrapper elements that exist only to group content can stay out of the model.
 * - `//head/seg/...` and `//head/.../@attr`: descendant axis followed by a direct sub-path. The
 *   first segment is matched at any depth; remaining segments walk that match's direct children
 *   (and an optional trailing `@attr` reads from the final element).
 *
 * Field type rules:
 * - Scalars (`String`, `Int`, `Long`, `Double`, `Boolean`), supported temporals (`LocalDate`,
 *   `LocalDateTime`, `Instant`), and `BigDecimal` parse the matched element's text.
 * - `T?` gets `null` on no match. Non-nullable `T` throws [XmlParseException.Missing] on no match.
 * - `List<T>` collects every match in document order. `T` may be a scalar or a `data class`. The
 *   list itself is non-nullable; an empty list represents no matches.
 * - A field typed as a `data class` (or `data class?`) builds a nested instance from that
 *   element's attrs, children, and text. The nested type does not need its own [XmlRecord]; it
 *   inherits its enclosing record's [XmlNs] bindings (see [XmlNs]).
 *
 * If [path] is left empty, the path defaults to the field's own Kotlin identifier — so
 * `@XmlChild val title: String` is equivalent to `@XmlChild("title") val title: String`. Use the
 * explicit form for any other path shape (multi-segment, attribute leaf, descendant axis,
 * namespace-prefixed, or simply when the XML name differs from the field name).
 *
 * @param path location relative to the enclosing element. Empty string = use field name as a
 *   single direct-child segment.
 */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class XmlChild(val path: String = "")

/**
 * Reads the text content of the record (or nested) element itself.
 *
 * One [XmlText] field per class. Whitespace is trimmed by default. Coercion to scalars / temporals
 * via [XmlFormat] / [XmlConverter] applies the same way it does for [XmlChild] text reads.
 *
 * @param preserveWhitespace when `true`, the raw text is returned untouched.
 */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class XmlText(val preserveWhitespace: Boolean = false)

/**
 * Reads repeated entry-style child elements into a `Map<K, V>` field.
 *
 * For a record containing `<entry k="alice"><v>9.5</v></entry>` children:
 *
 * ```
 * @XmlMap(entry = "entry", key = "@k", value = "v") val scores: Map<String, Double>
 * ```
 *
 * Each occurrence of [entry] inside the enclosing element produces one map entry. Insertion order
 * is preserved by the underlying `LinkedHashMap`.
 *
 * ## Path syntax for [key] and [value]
 *
 * Same as [XmlChild] paths, evaluated against the entry element:
 * - `@name`: attribute on the entry element. Bare `name` is null-namespace; `@prefix:local`
 *   resolves via the class-level [XmlNs] map (same rules as [XmlAttr]).
 * - `name`: direct child element of the entry. Reads its text content. Prefixed names and the
 *   class-level default namespace resolve via [XmlNs].
 * - `wrapper/name` or `name/@attr`: multi-segment paths walk wrappers, then read text or attribute.
 * - `//name` and `//head/seg/.../@attr`: descendant axis matches `head` at any depth inside the
 *   entry, then walks a direct sub-path under it.
 *
 * ## Type rules
 *
 * `K` and `V` can each independently be:
 * - A scalar / temporal / `BigDecimal` runtime-coerced type.
 * - A `data class` (nested), populated via the same machinery as nested [XmlChild] fields. Element
 *   path required (an `@attr` cannot produce a nested instance).
 * - `T?` for any of the above (single match → null when absent).
 * - `List<T>` of any of the above. With element paths, every match in document order contributes
 *   one element. With an attribute path, the attribute's single value contributes a one-element
 *   list per entry.
 *
 * Combinations rejected: `Map<Map<…,…>, V>`, `Map<K, Map<…,…>>`, `List<List<…>>` on either side,
 * `List<T?>` (list elements are inherently non-null).
 *
 * ## Aggregation semantics
 *
 * Per entry, [key] and [value] each yield either a single value (scalar / nested / nullable) or a
 * list of values (when the corresponding type is `List<T>`). The four combinations:
 *
 * | `K` shape  | `V` shape  | Per entry                     | Cross-entry duplicate `K`        |
 * |------------|------------|-------------------------------|----------------------------------|
 * | scalar     | scalar     | one `K`, one `V`              | last write wins                  |
 * | scalar     | `List<V>`  | one `K`, append all `V`       | append further `V` matches       |
 * | `List<K>`  | scalar     | snapshot all key matches      | last write wins (structural eq.) |
 * | `List<K>`  | `List<V>`  | snapshot key list, append `V` | append further `V` matches       |
 *
 * `List<K>` keys rely on Kotlin's structural list equality — two entries that produce the same
 * sequence of key matches collapse to the same map entry.
 *
 * ## Nullability
 *
 * - `Map<K, V>`: an empty map represents no matched entries.
 * - `Map<K, V>?`: stays `null` until at least one entry matches, otherwise the populated map.
 * - `Map<K?, V>` / `Map<K, V?>`: a missing key or value path in an entry stores `null` instead of
 *   throwing. Without nullability, a missing key or value throws [XmlParseException.Missing].
 *
 * ## Limitations
 *
 * - `@XmlFormat` / `@XmlConverter` are not honoured on `@XmlMap` fields.
 * - The entry name must not clash with another `@XmlChild`'s first segment on the same record.
 * - The entry path is single-segment (no `wrapper/entry`, no `//entry`).
 *
 * @param entry local name of the repeating entry element (optionally `prefix:local`).
 * @param key path to the entry's key.
 * @param value path to the entry's value.
 */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class XmlMap(val entry: String, val key: String, val value: String)

/**
 * Format string used when parsing a `LocalDate`, `LocalDateTime`, `Instant`, or `BigDecimal` field.
 *
 * Date and time fields go through [java.time.format.DateTimeFormatter.ofPattern]. `BigDecimal`
 * uses [java.text.DecimalFormat] in `parseBigDecimal` mode. Without [XmlFormat] the field falls
 * back to the ISO format (or to the `BigDecimal(String)` constructor).
 *
 * @param pattern the format pattern.
 */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class XmlFormat(val pattern: String)

/**
 * Marks a sealed `class`/`interface` whose `data class` subtypes are dispatched at parse time.
 *
 * A field whose declared type is the annotated sealed parent (or `T?` / `List<T>`) is bound via
 * the existing [XmlChild] annotation. The processor reads each subtype's [XmlSubtype] tag and
 * generates a dispatch table that picks the right subtype helper for every matched element.
 *
 * Two dispatch modes:
 *
 * - **Tag-name** (default, [discriminator] = `""`): each subtype matches a distinct child element
 *   directly under the enclosing record/subrecord. The field's [XmlChild] annotation must carry
 *   no path (use bare `@XmlChild`); the union of subtype tags acts as the field's match set. At
 *   most one tag-mode field per enclosing scope.
 *
 * - **Attribute** ([discriminator] = `"@attr"` or `"@prefix:local"`): every subtype matches the
 *   same wrapping element. The field's [XmlChild] path is the wrapping element's single-segment
 *   tag (no descendant axis, no nested wrappers). At parse time the processor reads the
 *   discriminator attribute on each match and routes by its value to the matching subtype's
 *   [XmlSubtype] entry. Unknown values skip the element.
 *
 * Subtypes must be `data class` types with a primary constructor (the same shape nested
 * `@XmlChild` data classes already use). They inherit the enclosing record's [XmlNs] bindings.
 *
 * @param discriminator empty for tag-name mode; `"@local"` or `"@prefix:local"` for attribute mode.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class XmlPolymorphic(val discriminator: String = "")

/**
 * Declares the discriminator value for a subtype of an [XmlPolymorphic] sealed parent.
 *
 * In tag-name mode [name] is the subtype's element local name (optionally `prefix:local`,
 * resolved against the enclosing record's [XmlNs] map; the default namespace applies as it does
 * for [XmlChild] element segments).
 *
 * In attribute mode [name] is the literal attribute value that selects this subtype.
 *
 * Subtype names must be unique within their sealed parent.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class XmlSubtype(val name: String)

/**
 * Routes raw text through a custom [Converter] before assignment. Use this for value types the
 * runtime does not coerce on its own (a domain `Money` class, for example).
 *
 * The converter must have a no-arg constructor. The generated parser instantiates it once per
 * parser object and reuses it.
 *
 * @param cls the converter class.
 */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class XmlConverter(val cls: KClass<out Converter<*>>)
