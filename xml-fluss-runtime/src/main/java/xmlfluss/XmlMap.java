package xmlfluss;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Reads repeated entry-style child elements into a {@code Map<K, V>} field.
 *
 * <p>For a record containing {@code <entry k="alice"><v>9.5</v></entry>} children:
 *
 * <pre>
 * &#64;XmlMap(entry = "entry", key = "&#64;k", value = "v")
 * Map&lt;String, Double&gt; scores
 * </pre>
 *
 * <p>Each occurrence of {@link #entry} inside the enclosing element produces one map entry.
 * Insertion order is preserved by the underlying {@code LinkedHashMap}.
 *
 * <h2>Path syntax for {@link #key} and {@link #value}</h2>
 *
 * <p>Same as {@link XmlChild} paths, evaluated against the entry element:
 * <ul>
 *   <li>{@code @name} — attribute on the entry element. Bare {@code name} is null-namespace;
 *       {@code @prefix:local} resolves via the class-level {@link XmlNs} map (same rules as {@link XmlAttr}).</li>
 *   <li>{@code name} — direct child element of the entry. Reads its text content. Prefixed names and the
 *       class-level default namespace resolve via {@link XmlNs}.</li>
 *   <li>{@code wrapper/name} or {@code name/@attr} — multi-segment paths walk wrappers, then read text or
 *       attribute.</li>
 *   <li>{@code //name} and {@code //head/seg/.../@attr} — descendant axis matches {@code head} at any depth
 *       inside the entry, then walks a direct sub-path under it.</li>
 * </ul>
 *
 * <h2>Type rules</h2>
 *
 * <p>{@code K} and {@code V} can each independently be:
 * <ul>
 *   <li>A scalar / temporal / {@code BigDecimal} runtime-coerced type.</li>
 *   <li>A nested record / data class, populated via the same machinery as nested {@link XmlChild} fields.
 *       Element path required (an {@code @attr} cannot produce a nested instance).</li>
 *   <li>The nullable form of any of the above (single match → null when absent).</li>
 *   <li>{@code List<T>} of any of the above. With element paths, every match in document order contributes
 *       one element. With an attribute path, the attribute's single value contributes a one-element list per
 *       entry.</li>
 * </ul>
 *
 * <p>Combinations rejected: {@code Map<Map<…,…>, V>}, {@code Map<K, Map<…,…>>}, {@code List<List<…>>} on
 * either side, {@code List<T?>} (list elements are inherently non-null).
 *
 * <h2>Aggregation semantics</h2>
 *
 * <p>Per entry, {@link #key} and {@link #value} each yield either a single value (scalar / nested /
 * nullable) or a list of values (when the corresponding type is {@code List<T>}). The four combinations:
 *
 * <table>
 *   <caption>Aggregation per entry / cross-entry</caption>
 *   <tr><th>K shape</th><th>V shape</th><th>Per entry</th><th>Cross-entry duplicate K</th></tr>
 *   <tr><td>scalar</td><td>scalar</td><td>one K, one V</td><td>last write wins</td></tr>
 *   <tr><td>scalar</td><td>List&lt;V&gt;</td><td>one K, append all V</td><td>append further V matches</td></tr>
 *   <tr><td>List&lt;K&gt;</td><td>scalar</td><td>snapshot all key matches</td><td>last write wins (structural eq.)</td></tr>
 *   <tr><td>List&lt;K&gt;</td><td>List&lt;V&gt;</td><td>snapshot key list, append V</td><td>append further V matches</td></tr>
 * </table>
 *
 * <p>{@code List<K>} keys rely on structural list equality — two entries that produce the same sequence
 * of key matches collapse to the same map entry.
 *
 * <h2>Nullability</h2>
 *
 * <ul>
 *   <li>{@code Map<K, V>} — an empty map represents no matched entries.</li>
 *   <li>Nullable {@code Map<K, V>} — stays {@code null} until at least one entry matches, otherwise the populated map.</li>
 *   <li>{@code Map<K?, V>} / {@code Map<K, V?>} — a missing key or value path in an entry stores {@code null}
 *       instead of throwing. Without nullability, a missing key or value throws {@link XmlParseException.Missing}.</li>
 * </ul>
 *
 * <h2>Limitations</h2>
 *
 * <ul>
 *   <li>{@link XmlFormat} / {@link XmlConverter} are not honoured on {@code @XmlMap} fields.</li>
 *   <li>The entry name must not clash with another {@link XmlChild}'s first segment on the same record.</li>
 *   <li>The entry path is single-segment (no {@code wrapper/entry}, no {@code //entry}).</li>
 * </ul>
 */
@Target({
    ElementType.RECORD_COMPONENT,
    ElementType.FIELD,
    ElementType.PARAMETER,
    ElementType.METHOD
})
@Retention(RetentionPolicy.SOURCE)
public @interface XmlMap {
    /** Local name of the repeating entry element (optionally {@code prefix:local}). */
    String entry();
    /** Path to the entry's key. */
    String key();
    /** Path to the entry's value. */
    String value();
}
