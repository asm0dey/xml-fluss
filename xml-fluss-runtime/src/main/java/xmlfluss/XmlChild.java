package xmlfluss;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Reads a child element (or its attribute) into the annotated field / record component /
 * value parameter. Multi-segment paths walk intermediate wrapper elements; the descendant
 * axis skips them.
 *
 * <p>Path syntax:
 * <ul>
 *   <li>{@code name} — direct child named {@code name} of the record (or nested) element.</li>
 *   <li>{@code wrapper/name} — descend through {@code wrapper}, then match its direct child {@code name}.</li>
 *   <li>{@code name/@attr} — read attribute {@code attr} from the matched child element.</li>
 *   <li>{@code prefix:name} — resolve {@code prefix} against the class-level {@link XmlNs} declarations.</li>
 *   <li>{@code //name} — descendant axis. Matches {@code name} at any depth inside the enclosing element,
 *       so wrapper elements that exist only to group content can stay out of the model.</li>
 *   <li>{@code //head/seg/...} and {@code //head/.../@attr} — descendant axis followed by a direct sub-path.
 *       The first segment is matched at any depth; remaining segments walk that match's direct children
 *       (and an optional trailing {@code @attr} reads from the final element).</li>
 * </ul>
 *
 * <p>Field type rules:
 * <ul>
 *   <li>Scalars ({@code String}, {@code Int}/{@code int}, {@code Long}/{@code long}, {@code Double}/{@code double},
 *       {@code Boolean}/{@code boolean}), supported temporals ({@code LocalDate}, {@code LocalDateTime},
 *       {@code Instant}), and {@code BigDecimal} parse the matched element's text.</li>
 *   <li>Nullable fields get {@code null} on no match. Non-nullable fields throw
 *       {@link XmlParseException.Missing} on no match. Java record components signal
 *       non-nullability with JSpecify {@code @NonNull}.</li>
 *   <li>{@code List<T>} collects every match in document order. {@code T} may be a scalar or a record
 *       (Kotlin {@code data class}). The list itself is non-nullable; an empty list represents no matches.</li>
 *   <li>A field typed as a record / data class (or its nullable form) builds a nested instance from that
 *       element's attrs, children, and text. The nested type does not need its own {@link XmlRecord}; it
 *       inherits its enclosing record's {@link XmlNs} bindings.</li>
 * </ul>
 *
 * <p>If {@link #path} is left empty, the path defaults to the field's own identifier — so
 * {@code @XmlChild String title} is equivalent to {@code @XmlChild("title") String title}. Use the
 * explicit form for any other path shape (multi-segment, attribute leaf, descendant axis,
 * namespace-prefixed, or simply when the XML name differs from the field name).
 */
@Target({
    ElementType.RECORD_COMPONENT,
    ElementType.FIELD,
    ElementType.PARAMETER,
    ElementType.METHOD
})
@Retention(RetentionPolicy.SOURCE)
public @interface XmlChild {
    String path() default "";
}
