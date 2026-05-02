package xmlfluss.codegen.model;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * One record component classified for code generation.
 *
 * @param name          component name in the source record
 * @param required      true when the component is non-null (primitive or {@code @NonNull})
 * @param isList        true when the component is {@code List<E>}
 * @param boxedType     boxed type of the field (e.g. {@code Integer} for {@code int}); used
 *                      for nullable holder slots in generated code
 * @param fieldType     declared type of the field (e.g. {@code int} or {@code List<String>})
 * @param elemType      element type, ignoring any {@code List} wrapper
 * @param elemTypeFq    convenience FQN of {@code elemType}; emitters may also recompute
 *                      from {@code elemType} but caching avoids hot-path allocations
 * @param source        where the value is read from in the XML stream
 * @param coerce        how the raw value is converted to the field type
 * @param mapKeyField   synthetic key-side spec for {@code @XmlMap} fields, otherwise null
 * @param mapValueField synthetic value-side spec for {@code @XmlMap} fields, otherwise null
 */
public record FieldSpec(
        String name,
        boolean required,
        boolean isList,
        TypeRef boxedType,
        TypeRef fieldType,
        TypeRef elemType,
        String elemTypeFq,
        Source source,
        Coerce coerce,
        @Nullable FieldSpec mapKeyField,
        @Nullable FieldSpec mapValueField
) {
    public FieldSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(boxedType, "boxedType");
        Objects.requireNonNull(fieldType, "fieldType");
        Objects.requireNonNull(elemType, "elemType");
        Objects.requireNonNull(elemTypeFq, "elemTypeFq");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(coerce, "coerce");
    }

    /** Convenience constructor for non-map fields. */
    public FieldSpec(String name, boolean required, boolean isList, TypeRef boxedType, TypeRef fieldType,
                     TypeRef elemType, String elemTypeFq, Source source, Coerce coerce) {
        this(name, required, isList, boxedType, fieldType, elemType, elemTypeFq, source, coerce, null, null);
    }
}
