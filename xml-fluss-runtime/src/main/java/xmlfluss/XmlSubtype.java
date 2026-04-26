package xmlfluss;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the discriminator value for a subtype of an {@link XmlPolymorphic} sealed parent.
 *
 * <p>In tag-name mode {@link #name} is the subtype's element local name (optionally
 * {@code prefix:local}, resolved against the enclosing record's {@link XmlNs} map; the default
 * namespace applies as it does for {@link XmlChild} element segments).
 *
 * <p>In attribute mode {@link #name} is the literal attribute value that selects this subtype.
 *
 * <p>Subtype names must be unique within their sealed parent.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface XmlSubtype {
    String name();
}
