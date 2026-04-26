package xmlfluss;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Routes raw text through a custom {@link Converter} before assignment. Use this for value
 * types the runtime does not coerce on its own (a domain {@code Money} class, for example).
 *
 * <p>The converter must have a no-arg constructor. The generated parser instantiates it once
 * per parser object and reuses it.
 *
 * <p>From Kotlin call sites, the {@code cls} parameter accepts a {@code KClass} literal
 * ({@code @XmlConverter(cls = MyConverter::class)}). The Kotlin compiler bridges
 * {@code KClass} literals to {@code Class} for Java-defined annotations automatically.
 */
@Target({
    ElementType.RECORD_COMPONENT,
    ElementType.FIELD,
    ElementType.PARAMETER,
    ElementType.METHOD
})
@Retention(RetentionPolicy.SOURCE)
public @interface XmlConverter {
    /** The converter class. */
    Class<? extends Converter<?>> cls();
}
