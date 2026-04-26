package xmlfluss;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Reads an attribute from the record (or nested) element into the annotated field /
 * record component / value parameter.
 *
 * <p>Bare {@code name} resolves in the null namespace (the class's default {@link XmlNs}
 * does not apply to attributes — that is the XML spec). Pass {@code prefix:local} to read a
 * namespaced attribute; the prefix resolves via the class-level {@link XmlNs} declarations
 * (e.g. {@code @XmlAttr("xml:lang")} after
 * {@code @XmlNs(prefix = "xml", uri = "http://www.w3.org/XML/1998/namespace")}).
 *
 * <p>A non-nullable field with the attribute absent triggers
 * {@link XmlParseException.Missing}. A nullable field gets {@code null} instead. For Java
 * record components, nullability is signalled with JSpecify {@code @NonNull}: components
 * without {@code @NonNull} are treated as nullable.
 *
 * <p>If {@link #name} is left empty, the attribute name defaults to the field's own
 * identifier — so {@code @XmlAttr String href} is equivalent to {@code @XmlAttr("href")
 * String href}. Use the explicit form when the XML name differs from the field name
 * ({@code @XmlAttr("xml:lang") String lang}).
 */
@Target({
    ElementType.RECORD_COMPONENT,
    ElementType.FIELD,
    ElementType.PARAMETER,
    ElementType.METHOD
})
@Retention(RetentionPolicy.SOURCE)
public @interface XmlAttr {
    String name() default "";
}
