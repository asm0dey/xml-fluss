package xmlfluss.codegen.spi;

import org.jspecify.annotations.Nullable;
import xmlfluss.codegen.model.TypeRef;

/**
 * Neutral handle to a non-record class type. Used for {@code @XmlConverter} validation
 * (public no-arg constructor + {@code Converter<T>} interface implementation +
 * type-argument assignability). Each SPI impl resolves these against its host language's
 * symbol API.
 */
public interface TypeSymbol {

    String qualifiedName();

    /** True iff the type has a {@code public} no-argument constructor. */
    boolean hasPublicNoArgConstructor();

    /**
     * The type-argument {@code TypeRef} the type provides for the parameterized supertype
     * {@code supertypeFqn} at position {@code index}. Returns {@code null} when the type
     * does not (transitively) implement that supertype, or when the requested arg position
     * is out of range.
     *
     * <p>Example: for {@code class Foo implements Converter<String>}, calling
     * {@code typeArgumentOf("xmlfluss.Converter", 0)} returns the {@code TypeRef} for
     * {@code java.lang.String}.
     */
    @Nullable TypeRef typeArgumentOf(String supertypeFqn, int index);

    /** Opaque host-language handle for diagnostics; may be {@code null} for synthetic fakes. */
    @Nullable Object nativeHandle();
}
