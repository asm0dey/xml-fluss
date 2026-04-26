package xmlfluss;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a sealed parent (Kotlin {@code sealed class}/{@code interface} or Java
 * {@code sealed interface}/{@code abstract sealed class}) whose record / data class subtypes
 * are dispatched at parse time.
 *
 * <p>A field whose declared type is the annotated sealed parent (or its nullable form, or
 * {@code List<T>}) is bound via the existing {@link XmlChild} annotation. The processor reads
 * each subtype's {@link XmlSubtype} tag and generates a dispatch table that picks the right
 * subtype helper for every matched element.
 *
 * <p>Two dispatch modes:
 *
 * <ul>
 *   <li><b>Tag-name</b> (default, {@link #discriminator} = {@code ""}): each subtype matches a
 *       distinct child element directly under the enclosing record/subrecord. The field's
 *       {@link XmlChild} annotation must carry no path (use bare {@code @XmlChild}); the union
 *       of subtype tags acts as the field's match set. At most one tag-mode field per enclosing
 *       scope.</li>
 *   <li><b>Attribute</b> ({@link #discriminator} = {@code "@attr"} or {@code "@prefix:local"}):
 *       every subtype matches the same wrapping element. The field's {@link XmlChild} path is
 *       the wrapping element's single-segment tag (no descendant axis, no nested wrappers). At
 *       parse time the processor reads the discriminator attribute on each match and routes by
 *       its value to the matching subtype's {@link XmlSubtype} entry. Unknown values skip the
 *       element.</li>
 * </ul>
 *
 * <p>Subtypes must be record / data class types with a primary / canonical constructor (the
 * same shape nested {@link XmlChild} record fields already use). They inherit the enclosing
 * record's {@link XmlNs} bindings.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface XmlPolymorphic {
    /** Empty for tag-name mode; {@code "@local"} or {@code "@prefix:local"} for attribute mode. */
    String discriminator() default "";
}
