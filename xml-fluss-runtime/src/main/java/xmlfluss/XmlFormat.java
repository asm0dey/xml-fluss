package xmlfluss;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Format string used when parsing a {@code LocalDate}, {@code LocalDateTime}, {@code Instant},
 * or {@code BigDecimal} field.
 *
 * <p>Date and time fields go through {@link java.time.format.DateTimeFormatter#ofPattern(String)}.
 * {@code BigDecimal} uses {@link java.text.DecimalFormat} in {@code parseBigDecimal} mode.
 * Without {@code XmlFormat} the field falls back to the ISO format (or to the
 * {@code BigDecimal(String)} constructor).
 */
@Target({
    ElementType.RECORD_COMPONENT,
    ElementType.FIELD,
    ElementType.PARAMETER,
    ElementType.METHOD
})
@Retention(RetentionPolicy.SOURCE)
public @interface XmlFormat {
    /** The format pattern. */
    String pattern();
}
