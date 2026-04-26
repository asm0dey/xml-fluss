package xmlfluss;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Container for repeated {@link XmlNs} annotations. The Kotlin and Java compilers synthesise
 * this when a class carries more than one {@link XmlNs}. You normally don't write it directly.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface XmlNamespaces {
    XmlNs[] value();
}
