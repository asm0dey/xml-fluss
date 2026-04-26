package xmlfluss;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class (Kotlin {@code data class} or Java {@code record}) as a parseable record.
 *
 * <p>The annotation processor (KSP for Kotlin, APT for Java) picks up the annotation and
 * generates a {@code ${ClassName}Parser} type with a {@code parse(InputStream)} entry point.
 * The KSP-generated parser exposes a {@code Flow<T>}; the APT-generated parser exposes a
 * {@code Stream<T>}. Each emitted instance corresponds to one element matched by {@link #path}.
 *
 * <p>Supported {@link #path} forms:
 * <ul>
 *   <li>{@code //author} — descendant axis, matches {@code author} at any depth.</li>
 *   <li>{@code /library/section/author} — absolute path from the document root.</li>
 *   <li>{@code authors/author} — relative path. A leading descendant axis is added automatically.</li>
 *   <li>{@code //author[@role='main']} — predicate filter on an attribute.</li>
 *   <li>{@code //{atom}entry} or {@code //atom:entry} — namespaced element. Prefixes resolve via {@link XmlNs}.</li>
 *   <li>{@code //{*}author} — matches {@code author} in any namespace.</li>
 * </ul>
 *
 * <p>Predicates support {@code =}, {@code !=}, {@code and}, {@code or}, and integer position
 * (e.g. {@code [2]}). They run at {@code START_ELEMENT} time. XPath functions and
 * {@code text()=} are not supported.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface XmlRecord {
    String path();
}
