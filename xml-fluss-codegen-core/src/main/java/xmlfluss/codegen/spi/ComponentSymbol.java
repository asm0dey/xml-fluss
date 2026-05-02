package xmlfluss.codegen.spi;

import org.jspecify.annotations.Nullable;
import xmlfluss.codegen.model.TypeRef;

/**
 * One record component in source. Reports its declared type, list-ness, nullability,
 * and annotations without depending on the host's symbol API.
 */
public interface ComponentSymbol {

    /** Component (record-component / property) name. */
    String name();

    /** Declared type — including the {@code List<E>} wrapper if present. */
    TypeRef type();

    /**
     * True when the component is declared nullable. Java side: {@code @Nullable}
     * (JSpecify or javax). Kotlin side: type ends with {@code ?}.
     */
    boolean nullable();

    /** True when {@link #type()} is {@code java.util.List<E>}. */
    boolean isList();

    /** Element type — {@code E} when {@link #isList()}, else equal to {@link #type()}. */
    TypeRef elementType();

    /**
     * If the component's element type is itself an {@code @XmlRecord}, the corresponding
     * {@link RecordSymbol}. Lets the classifier traverse nested records lazily.
     */
    @Nullable RecordSymbol asNestedRecord();

    /** Annotations on the component. */
    AnnotationView annotations();

    /**
     * Opaque handle to the source-language symbol, for {@link DiagnosticReporter}
     * to attach diagnostics. May be {@code null} for synthetic / fake components.
     */
    @Nullable Object nativeHandle();
}
