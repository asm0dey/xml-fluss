package xmlfluss;

import java.lang.annotation.*;

/**
 * Binds a namespace prefix to a URI for the annotated record class.
 *
 * <p>Repeat the annotation to declare several prefixes. Pass {@code ""} as {@link #prefix} to
 * set the default namespace; bare element names in {@link XmlChild} paths and the
 * {@link XmlRecord} path then resolve to that URI. Attributes stay in the null namespace by
 * default.
 *
 * <p>Nested record / data classes inherit their enclosing record's prefix bindings; a nested
 * class only needs its own {@code XmlNs} when it adds a new prefix. Redeclaring a prefix the
 * parent already binds is allowed only if it maps to the same URI — a different URI is a build
 * error (so the same {@code x:} in source code never resolves to two different namespaces).
 * A nested type re-used from two records with incompatible effective namespace maps is also a
 * build error.
 *
 * <p>Example:
 * <pre>
 * &#64;XmlRecord("//atom:entry")
 * &#64;XmlNs(prefix = "atom", uri = "http://www.w3.org/2005/Atom")
 * public record Entry(...) {}
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
@Repeatable(XmlNamespaces.class)
public @interface XmlNs {
    String prefix();
    String uri();
}
