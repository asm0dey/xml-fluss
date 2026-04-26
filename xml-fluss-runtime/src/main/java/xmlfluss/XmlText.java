package xmlfluss;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Reads the text content of the record (or nested) element itself.
 *
 * <p>One {@code XmlText} field per class. Whitespace is trimmed by default. Coercion to
 * scalars / temporals via {@link XmlFormat} / {@link XmlConverter} applies the same way it
 * does for {@link XmlChild} text reads.
 */
@Target({
    ElementType.RECORD_COMPONENT,
    ElementType.FIELD,
    ElementType.PARAMETER,
    ElementType.METHOD
})
@Retention(RetentionPolicy.SOURCE)
public @interface XmlText {
    /** When {@code true}, the raw text is returned untouched. */
    @SuppressWarnings("unused") boolean preserveWhitespace() default false;
}
