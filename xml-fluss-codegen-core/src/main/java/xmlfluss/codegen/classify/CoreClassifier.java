package xmlfluss.codegen.classify;

import org.jspecify.annotations.Nullable;
import xmlfluss.codegen.model.Coerce;
import xmlfluss.codegen.model.FieldSpec;
import xmlfluss.codegen.model.NestedRegistry;
import xmlfluss.codegen.model.PathSeg;
import xmlfluss.codegen.model.RecordSpec;
import xmlfluss.codegen.model.ScalarKind;
import xmlfluss.codegen.model.Source;
import xmlfluss.codegen.model.TypeRef;
import xmlfluss.codegen.spi.ComponentSymbol;
import xmlfluss.codegen.spi.RecordSymbol;
import xmlfluss.codegen.spi.SymbolProvider;
import xmlfluss.codegen.spi.TypeSymbol;
import xmlfluss.path.CompiledPath;
import xmlfluss.path.PathParseException;
import xmlfluss.path.PathParser;
import xmlfluss.path.Step;

import xmlfluss.codegen.model.AttrVariant;
import xmlfluss.codegen.model.PolyDispatch;
import xmlfluss.codegen.model.QKey;
import xmlfluss.codegen.model.TagVariant;
import xmlfluss.codegen.plan.TrieNode;
import xmlfluss.path.Predicate;

import java.util.*;

/**
 * Walks a {@link RecordSymbol} and produces a {@link RecordSpec}. Validation errors are
 * reported via {@link SymbolProvider#diagnostics()} and surfaced as a returned {@code null}
 * so the caller can skip the record.
 */
public final class CoreClassifier {

    // ------------------------------------------------------------------ FQ constants

    static final String FQ_XML_ATTR        = "xmlfluss.XmlAttr";
    static final String FQ_XML_CHILD       = "xmlfluss.XmlChild";
    static final String FQ_XML_TEXT        = "xmlfluss.XmlText";
    static final String FQ_XML_MAP         = "xmlfluss.XmlMap";
    static final String FQ_XML_FORMAT      = "xmlfluss.XmlFormat";
    static final String FQ_XML_CONVERTER   = "xmlfluss.XmlConverter";
    static final String FQ_CONVERTER       = "xmlfluss.Converter";
    static final String FQ_XML_POLYMORPHIC = "xmlfluss.XmlPolymorphic";
    static final String FQ_XML_SUBTYPE     = "xmlfluss.XmlSubtype";

    // ------------------------------------------------------------------ inner helpers

    /** Resolved XML qualified name. {@code ns} is null when there is no namespace. */
    private record QNameInfo(@Nullable String ns, String local) {}

    /** Validated converter reference. */
    private record ConverterInfo(TypeRef typeRef, String fqn) {}

    /** Outcome of resolving an {@code @XmlConverter} annotation. {@code error} signals a
     *  diagnostic was already reported. {@code info} is null when no converter applies. */
    private record ConverterResult(@Nullable ConverterInfo info, boolean error) {
        static final ConverterResult NONE = new ConverterResult(null, false);
        static final ConverterResult ERROR = new ConverterResult(null, true);
    }

    /** Coerce + boxed/elem type-name triple for scalar/text/attr fields. */
    private record ScalarBundle(TypeRef boxedTypeName, TypeRef elemTypeName, String elemFq, Coerce coerce) {}

    // ------------------------------------------------------------------ fields

    private final SymbolProvider sp;
    private final NestedRegistry registry = new NestedRegistry();

    public CoreClassifier(SymbolProvider sp) {
        this.sp = sp;
    }

    public NestedRegistry registry() {
        return registry;
    }

    // ------------------------------------------------------------------ public entry point

    /**
     * Top-level classification. Returns {@code null} when the record cannot be classified;
     * diagnostics are reported through {@code sp.diagnostics()} before returning.
     *
     * <p>Re-entrant: each invocation uses its own local {@code hadError} flag, so nested
     * record classification via {@link #ensureNested} works correctly.
     */
    public @Nullable RecordSpec classify(RecordSymbol record) {
        return classifyOne(record, Map.of());
    }

    /**
     * Internal per-record classification. {@code inheritedNs} carries the accumulated namespace
     * map from all enclosing records; the record's own {@code @XmlNs} declarations are merged on
     * top (nested overrides outer for the same prefix).
     */
    private @Nullable RecordSpec classifyOne(RecordSymbol record, Map<String, String> inheritedNs) {

        // Merge inherited namespaces with this record's own declarations (own overrides inherited)
        Map<String, String> nsMap = new LinkedHashMap<>(inheritedNs);
        nsMap.putAll(record.declaredNamespaces());

        String declaredPath = record.declaredPath();
        String recordPath = declaredPath != null ? declaredPath : "";

        // Eagerly validate the @XmlRecord(path=...) string against the runtime path parser so
        // syntax errors surface as a single ERROR diagnostic instead of as a stack trace at
        // emit time. Mirrors original Classifier.classifyTopLevel lines 74-81.
        if (!recordPath.isEmpty()) {
            try {
                xmlfluss.runtime.Paths.INSTANCE.compile(recordPath, nsMap);
            } catch (RuntimeException ex) {
                sp.diagnostics().error(record.nativeHandle(),
                        "@XmlRecord path '" + recordPath + "' is invalid: " + ex.getMessage());
                return null;
            }
        }

        List<FieldSpec> fields = new ArrayList<>();
        Set<String> seenAttrKeys = new HashSet<>();
        Map<String, String> firstAttrBindingByKey = new HashMap<>();
        boolean hadError = false;
        int textCount = 0;

        for (ComponentSymbol c : record.components()) {
            boolean hasXmlAttr  = c.annotations().has(FQ_XML_ATTR);
            boolean hasXmlChild = c.annotations().has(FQ_XML_CHILD);
            boolean hasXmlText  = c.annotations().has(FQ_XML_TEXT);
            boolean hasXmlMap   = c.annotations().has(FQ_XML_MAP);
            String  cName       = c.name();

            // Mutual exclusion: @XmlAttr / @XmlChild / @XmlText / @XmlMap
            int bindings = (hasXmlAttr ? 1 : 0) + (hasXmlChild ? 1 : 0)
                    + (hasXmlText ? 1 : 0) + (hasXmlMap ? 1 : 0);
            if (bindings > 1) {
                sp.diagnostics().error(c.nativeHandle(),
                        "@XmlAttr / @XmlChild / @XmlText / @XmlMap are mutually exclusive on '"
                                + cName + "'");
                hadError = true;
                continue;
            }

            // Reject nullable List fields. Kotlin lets users write `List<X>?`, but the parser
            // contract is "no matches → empty list". A nullable list would require the parser
            // to choose between null and an empty list, with no guidance from the source.
            // Java records have no syntactic equivalent so APT doesn't trip this; CoreClassifier
            // enforces it once for every host language.
            if (c.isList() && c.nullable()) {
                sp.diagnostics().error(c.nativeHandle(),
                        "List field '" + cName + "' must not be nullable; use empty list");
                hadError = true;
                continue;
            }

            // Mutual exclusion: @XmlFormat and @XmlConverter (checked here so all branches enforce it)
            boolean hasFormat    = c.annotations().has(FQ_XML_FORMAT);
            boolean hasConverter = c.annotations().has(FQ_XML_CONVERTER);
            if (hasFormat && hasConverter) {
                sp.diagnostics().error(c.nativeHandle(),
                        "@XmlFormat and @XmlConverter are mutually exclusive on '" + cName + "'");
                hadError = true;
                continue;
            }

            // Polymorphic dispatch: if the element type is a sealed parent annotated with
            // @XmlPolymorphic, and @XmlChild is present, route to classifyPolymorphic.
            // Mirror original Classifier.java lines 229-239.
            TypeRef checkType = c.isList() ? c.elementType() : c.type();
            RecordSymbol polyParent = sp.lookupRecord(checkType.qualifiedName());
            boolean isPolyParent = polyParent != null
                    && polyParent.annotations().has(FQ_XML_POLYMORPHIC);

            if (isPolyParent && hasXmlChild) {
                if (hasConverter) {
                    sp.diagnostics().error(c.nativeHandle(),
                            "@XmlConverter not supported on polymorphic field '" + cName + "'");
                    hadError = true;
                    continue;
                }
                FieldSpec spec = classifyPolymorphic(c, polyParent, nsMap);
                if (spec == null) {
                    hadError = true;
                } else {
                    fields.add(spec);
                }
                continue;
            }
            if (isPolyParent && hasConverter) {
                sp.diagnostics().error(c.nativeHandle(),
                        "@XmlConverter not supported on polymorphic field '" + cName + "'");
                hadError = true;
                continue;
            }

            if (hasXmlAttr) {
                // ---- @XmlAttr branch ----
                FieldSpec spec = classifyAttr(c, nsMap);
                if (spec != null) {
                    if (spec.source() instanceof Source.Attr attr) {
                        String key = qnameKey(attr.ns(), attr.name());
                        if (!seenAttrKeys.add(key)) {
                            String first = firstAttrBindingByKey.get(key);
                            sp.diagnostics().error(c.nativeHandle(),
                                    "duplicate @XmlAttr name '" + attr.name() + "': '"
                                            + first + "' and '" + spec.name() + "' both bind it");
                            hadError = true;
                            continue;
                        }
                        firstAttrBindingByKey.put(key, spec.name());
                    }
                    fields.add(spec);
                } else {
                    // classifyAttr already reported the diagnostic; abandon the whole record.
                    hadError = true;
                }
            } else if (hasXmlText) {
                // ---- @XmlText branch ----
                FieldSpec spec = classifyText(c);
                if (spec == null) {
                    hadError = true;
                } else {
                    textCount++;
                    if (textCount > 1) {
                        sp.diagnostics().error(c.nativeHandle(),
                                "@XmlText may appear at most once per record");
                        hadError = true;
                        continue;
                    }
                    fields.add(spec);
                }
            } else if (hasXmlMap) {
                // ---- @XmlMap branch ----
                FieldSpec spec = classifyMap(c, nsMap);
                if (spec == null) {
                    hadError = true;
                } else {
                    fields.add(spec);
                }
            } else if (TypeRefClassification.isJavaUtilMap(c.type())) {
                // Map<K,V> without @XmlMap → error (mirrors original Classifier.java lines 204-207)
                sp.diagnostics().error(c.nativeHandle(),
                        "Field '" + cName + "' is Map<K, V> but lacks @XmlMap");
                hadError = true;
            } else {
                // @XmlChild (explicit or implicit) branch — unannotated components fall
                // through with the field name as the default path. APT relies on this
                // shorthand for plain Java records (`record Doc(String body) {}` →
                // implicit `@XmlChild(path="body")`); KSP code paths that want the strict
                // "no binding rejected" diagnostic should emit it before reaching the
                // classifier, since the classifier can't tell host-language semantics
                // apart at this layer.
                FieldSpec spec = classifyChild(c, nsMap);
                if (spec == null) {
                    hadError = true;
                } else {
                    fields.add(spec);
                }
            }
        }

        if (hadError) {
            return null;
        }

        // Cross-field path checks: ambiguous overlaps between @XmlChild paths, descendant
        // collisions, @XmlMap entry conflicts, polymorphic tag/wrap clashes. Mirrors
        // original Classifier.java line 130.
        String ownerFq = record.qualifiedName();
        if (!validateChildPaths(record, ownerFq, fields)) {
            return null;
        }

        return new RecordSpec(
                record.packageName(),
                record.simpleName(),
                Objects.requireNonNull(recordPath),
                nsMap,
                fields,
                record.nativeHandle());
    }

    // ------------------------------------------------------------------ @XmlPolymorphic classification

    /**
     * Classifies a single component as {@code @XmlPolymorphic}. Mirrors original
     * {@code Classifier.classifyPolymorphic}. Returns {@code null} on validation error.
     *
     * @param polyParent  the sealed parent {@link RecordSymbol} already confirmed to carry
     *                    {@code @XmlPolymorphic}
     */
    private @Nullable FieldSpec classifyPolymorphic(ComponentSymbol c,
                                                     RecordSymbol polyParent,
                                                     Map<String, String> nsMap) {
        String name = c.name();

        // @XmlFormat is not supported on polymorphic fields
        if (c.annotations().has(FQ_XML_FORMAT)) {
            sp.diagnostics().error(c.nativeHandle(),
                    "@XmlFormat not supported on polymorphic field '" + name + "'");
            return null;
        }

        // discriminator comes from the *parent* type's @XmlPolymorphic annotation,
        // not from the field.  Mirrors original Classifier.java line 355.
        String parentDisc = polyParent.annotations().stringValue(FQ_XML_POLYMORPHIC, "discriminator");
        String discriminator = (parentDisc == null) ? "" : parentDisc;

        List<RecordSymbol> subtypes = polyParent.sealedSubtypes();
        if (subtypes.isEmpty()) {
            sp.diagnostics().error(c.nativeHandle(),
                    "polymorphic field '" + name + "': sealed type "
                            + polyParent.qualifiedName() + " has no permitted subclasses");
            return null;
        }

        for (RecordSymbol s : subtypes) {
            if (!s.annotations().has(FQ_XML_SUBTYPE)) {
                sp.diagnostics().error(c.nativeHandle(),
                        "polymorphic field '" + name + "': subtype " + s.qualifiedName()
                                + " is missing @XmlSubtype");
                return null;
            }
        }

        // Ensure every subtype is registered as a nested record
        for (RecordSymbol s : subtypes) {
            String nestedFq = ensureNested(c, name, s, nsMap, /*terminating=*/true);
            if (nestedFq == null) return null;
        }

        // Path from the field's @XmlChild annotation
        String pathRaw = c.annotations().stringValue(FQ_XML_CHILD, "path");
        String rawPath = (pathRaw == null) ? "" : pathRaw;

        PolyDispatch dispatch;
        if (discriminator.isEmpty()) {
            // ---- Tag mode ----
            if (!rawPath.isEmpty()) {
                sp.diagnostics().error(c.nativeHandle(),
                        "polymorphic field '" + name
                                + "': tag-mode @XmlChild path must be empty (got '" + rawPath + "')");
                return null;
            }
            List<TagVariant> variants = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (RecordSymbol s : subtypes) {
                String subName = s.annotations().stringValue(FQ_XML_SUBTYPE, "name");
                if (subName == null || subName.isEmpty()) {
                    sp.diagnostics().error(c.nativeHandle(),
                            "polymorphic field '" + name + "': subtype " + s.qualifiedName()
                                    + " @XmlSubtype.name is empty");
                    return null;
                }
                QNameInfo qn = resolveQName(subName, nsMap, nsMap.get(""), c, "@XmlSubtype", name);
                if (qn == null) return null;
                String key = qnameKey(qn.ns(), qn.local());
                if (!seen.add(key)) {
                    sp.diagnostics().error(c.nativeHandle(),
                            "polymorphic field '" + name + "': duplicate @XmlSubtype tag '"
                                    + subName + "'");
                    return null;
                }
                variants.add(new TagVariant(qn.ns(), qn.local(), s.qualifiedName()));
            }
            dispatch = new PolyDispatch.Tag(variants);
        } else {
            // ---- Attr mode ----
            if (!discriminator.startsWith("@")) {
                sp.diagnostics().error(c.nativeHandle(),
                        "polymorphic field '" + name
                                + "': @XmlPolymorphic.discriminator must start with '@' (got '"
                                + discriminator + "')");
                return null;
            }
            String attrRaw = discriminator.substring(1);
            if (attrRaw.isEmpty() || attrRaw.contains("/")) {
                sp.diagnostics().error(c.nativeHandle(),
                        "polymorphic field '" + name + "': bad discriminator '" + discriminator + "'");
                return null;
            }
            QNameInfo aqn = resolveQName(attrRaw, nsMap, /*defaultNs=*/null, c,
                    "@XmlPolymorphic discriminator", name);
            if (aqn == null) return null;
            if (rawPath.isEmpty()) {
                sp.diagnostics().error(c.nativeHandle(),
                        "polymorphic field '" + name
                                + "': attr-mode @XmlChild requires the wrapping element path");
                return null;
            }
            if (rawPath.startsWith("//") || rawPath.contains("/") || rawPath.startsWith("@")) {
                sp.diagnostics().error(c.nativeHandle(),
                        "polymorphic field '" + name
                                + "': attr-mode @XmlChild path must be a single direct-child"
                                + " element (got '" + rawPath + "')");
                return null;
            }
            QNameInfo wqn = resolveQName(rawPath, nsMap, nsMap.get(""), c, "@XmlChild", name);
            if (wqn == null) return null;
            List<AttrVariant> variants = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (RecordSymbol s : subtypes) {
                String value = s.annotations().stringValue(FQ_XML_SUBTYPE, "name");
                if (value == null || value.isEmpty()) {
                    sp.diagnostics().error(c.nativeHandle(),
                            "polymorphic field '" + name + "': subtype " + s.qualifiedName()
                                    + " @XmlSubtype.name is empty");
                    return null;
                }
                if (!seen.add(value)) {
                    sp.diagnostics().error(c.nativeHandle(),
                            "polymorphic field '" + name
                                    + "': duplicate @XmlSubtype attr value '" + value + "'");
                    return null;
                }
                variants.add(new AttrVariant(value, s.qualifiedName()));
            }
            dispatch = new PolyDispatch.Attr(wqn.ns(), wqn.local(), aqn.ns(), aqn.local(), variants);
        }

        // Build the FieldSpec.  The type is the sealed parent; Coerce is Nested(parentFq)
        // (mirroring original Classifier.java lines 452-460).
        String parentFq = polyParent.qualifiedName();
        TypeRef parentType = TypeRef.of(polyParent.packageName(), polyParent.simpleName());
        TypeRef fieldType = c.isList()
                ? TypeRef.parameterized("java.util", "List", List.of(parentType))
                : parentType;
        boolean required = !c.nullable();

        return new FieldSpec(
                name,
                required,
                c.isList(),
                parentType,
                fieldType,
                parentType,
                parentFq,
                new Source.PolyChild(dispatch),
                new Coerce.Nested(parentFq));
    }

    // ------------------------------------------------------------------ @XmlAttr classification

    /**
     * Classifies a single component as {@code @XmlAttr}. Returns {@code null} either if the
     * component has no {@code @XmlAttr} annotation (silent skip) or if validation fails
     * (diagnostic already reported).
     */
    private @Nullable FieldSpec classifyAttr(ComponentSymbol c,
                                              Map<String, String> nsMap) {
        if (!c.annotations().has(FQ_XML_ATTR)) {
            return null;
        }

        String name = c.name();

        // Mutual exclusion: @XmlFormat and @XmlConverter
        boolean hasFormat    = c.annotations().has(FQ_XML_FORMAT);
        boolean hasConverter = c.annotations().has(FQ_XML_CONVERTER);

        if (hasFormat && hasConverter) {
            sp.diagnostics().error(c.nativeHandle(),
                    "@XmlFormat and @XmlConverter are mutually exclusive on '" + name + "'");
            return null;
        }

        // @XmlAttr does not support List
        if (c.isList()) {
            sp.diagnostics().error(c.nativeHandle(),
                    "@XmlAttr does not support List on '" + name + "'");
            return null;
        }

        // Resolve the attribute name (from annotation or fall back to component name)
        String rawAttrName = c.annotations().stringValue(FQ_XML_ATTR, "name");
        String attrName = (rawAttrName == null || rawAttrName.isEmpty()) ? name : rawAttrName;

        // For attributes the default namespace is null per XML spec.
        QNameInfo qn = resolveQName(attrName, nsMap, null, c, "@XmlAttr", name);
        if (qn == null) {
            return null;
        }

        TypeRef elemType = c.elementType();

        ConverterResult cr = resolveConverter(c, name, elemType);
        if (cr.error()) return null;
        ConverterInfo converterInfo = cr.info();

        if (converterInfo == null && !TypeRefClassification.isScalarOrTemporal(elemType)) {
            sp.diagnostics().error(c.nativeHandle(),
                    "@XmlAttr requires a scalar type on '" + name + "', got " + elemType.qualifiedName());
            return null;
        }

        String formatPattern = null;
        if (hasFormat) {
            if (!TypeRefClassification.isFormattableType(elemType)) {
                sp.diagnostics().error(c.nativeHandle(),
                        "@XmlFormat on '" + name + "' is only supported for LocalDate, "
                                + "LocalDateTime, Instant, or BigDecimal; got " + elemType.qualifiedName());
                return null;
            }
            String raw = c.annotations().stringValue(FQ_XML_FORMAT, "pattern");
            formatPattern = (raw != null) ? raw : "";
        }

        ScalarBundle sb = resolveScalarBundle(c, name, elemType, converterInfo, formatPattern);
        if (sb == null) return null;

        boolean primitive = elemType.primitive();
        TypeRef fieldType = primitive ? elemType : sb.elemTypeName();

        return new FieldSpec(
                name,
                !c.nullable(),
                false,
                sb.boxedTypeName(),
                fieldType,
                sb.elemTypeName(),
                sb.elemFq(),
                new Source.Attr(qn.ns(), qn.local()),
                sb.coerce());
    }

    // ------------------------------------------------------------------ @XmlText classification

    /**
     * Classifies a single component as {@code @XmlText}. Returns {@code null} on validation
     * error (diagnostic already reported).
     */
    private @Nullable FieldSpec classifyText(ComponentSymbol c) {
        String name = c.name();

        // @XmlText does not support List
        if (c.isList()) {
            sp.diagnostics().error(c.nativeHandle(),
                    "@XmlText is not supported on List<...> '" + name + "'");
            return null;
        }

        TypeRef elemType = c.elementType();

        ConverterResult cr = resolveConverter(c, name, elemType);
        if (cr.error()) return null;
        ConverterInfo converterInfo = cr.info();

        if (converterInfo == null && !TypeRefClassification.isScalarOrTemporal(elemType)) {
            sp.diagnostics().error(c.nativeHandle(),
                    "@XmlText requires a scalar type on '" + name + "', got "
                            + elemType.qualifiedName());
            return null;
        }

        boolean hasFormat = c.annotations().has(FQ_XML_FORMAT);
        String formatPattern = null;
        if (hasFormat) {
            if (converterInfo == null && !TypeRefClassification.isFormattableType(elemType)) {
                sp.diagnostics().error(c.nativeHandle(),
                        "@XmlFormat on '" + name + "' is only supported for LocalDate, "
                                + "LocalDateTime, Instant, or BigDecimal; got "
                                + elemType.qualifiedName());
                return null;
            }
            String raw = c.annotations().stringValue(FQ_XML_FORMAT, "pattern");
            formatPattern = (raw != null) ? raw : "";
        }

        Boolean preserveRaw = c.annotations().booleanValue(FQ_XML_TEXT, "preserveWhitespace");
        boolean preserve = preserveRaw != null && preserveRaw;

        ScalarBundle sb = resolveScalarBundle(c, name, elemType, converterInfo, formatPattern);
        if (sb == null) return null;

        boolean primitive = elemType.primitive();
        TypeRef fieldType = primitive ? elemType : sb.elemTypeName();

        return new FieldSpec(
                name,
                !c.nullable(),
                false,
                sb.boxedTypeName(),
                fieldType,
                sb.elemTypeName(),
                sb.elemFq(),
                new Source.Text(preserve),
                sb.coerce());
    }

    // ------------------------------------------------------------------ @XmlMap classification

    /**
     * Classifies a single component as {@code @XmlMap}. Returns {@code null} on validation
     * error (diagnostic already reported).
     */
    private @Nullable FieldSpec classifyMap(ComponentSymbol c,
                                             Map<String, String> nsMap) {
        String name = c.name();

        // @XmlFormat / @XmlConverter not supported on @XmlMap
        if (c.annotations().has(FQ_XML_FORMAT) || c.annotations().has(FQ_XML_CONVERTER)) {
            sp.diagnostics().error(c.nativeHandle(),
                    "@XmlFormat / @XmlConverter not supported on @XmlMap field '" + name + "'");
            return null;
        }

        // Must be Map<K,V>
        TypeRef declaredType = c.type();
        if (!TypeRefClassification.isJavaUtilMap(declaredType)) {
            sp.diagnostics().error(c.nativeHandle(),
                    "@XmlMap requires Map<K, V> type for '" + name + "', got "
                            + declaredType.qualifiedName());
            return null;
        }

        // Retrieve entry, key, value from @XmlMap annotation
        String entry  = c.annotations().stringValue(FQ_XML_MAP, "entry");
        String keyPath = c.annotations().stringValue(FQ_XML_MAP, "key");
        String valPath = c.annotations().stringValue(FQ_XML_MAP, "value");

        if (entry == null || entry.isBlank() || entry.contains("/") || entry.startsWith("@")) {
            sp.diagnostics().error(c.nativeHandle(),
                    "@XmlMap entry '" + entry + "' for '" + name
                            + "' must be a single element name (optional 'prefix:local')");
            return null;
        }
        if (keyPath == null || valPath == null) {
            sp.diagnostics().error(c.nativeHandle(),
                    "@XmlMap on '" + name + "' is missing 'key' or 'value'");
            return null;
        }

        QNameInfo eqn = resolveQName(entry, nsMap, nsMap.get(""), c, "@XmlMap", name);
        if (eqn == null) {
            return null;
        }

        // Require exactly two type arguments (Map<K, V>)
        List<TypeRef> typeArgs = declaredType.typeArguments();
        if (typeArgs.size() != 2) {
            sp.diagnostics().error(c.nativeHandle(),
                    "@XmlMap on '" + name + "' requires Map<K, V> with two type arguments");
            return null;
        }
        TypeRef keyType = typeArgs.get(0);
        TypeRef valType = typeArgs.get(1);

        // Build synthetic key/value sub-specs
        FieldSpec keyField = buildSyntheticMapKvField(c, "mk", keyType, keyPath, nsMap, name, "key");
        if (keyField == null) return null;
        FieldSpec valField = buildSyntheticMapKvField(c, "mv", valType, valPath, nsMap, name, "value");
        if (valField == null) return null;

        boolean required = !c.nullable();

        // Outer field: type is Map<K, V> — preserve List<X> values as-is
        TypeRef keyParam = keyField.isList() ? keyField.fieldType() : keyField.boxedType();
        TypeRef valParam = valField.isList() ? valField.fieldType() : valField.boxedType();
        TypeRef mapType = TypeRef.parameterized("java.util", "Map", List.of(keyParam, valParam));

        return new FieldSpec(
                name,
                required,
                false,
                mapType,
                mapType,
                valField.elemType(),
                "java.util.Map",
                new Source.MapEntry(eqn.ns(), eqn.local()),
                new Coerce.MapAggregate(),
                keyField,
                valField);
    }

    /**
     * Builds a synthetic key or value sub-spec for an {@code @XmlMap} field.
     *
     * @param c           the owner map component (used for diagnostics)
     * @param syntheticName  "mk" or "mv"
     * @param type        the K or V type
     * @param pathStr     the path expression from the annotation
     * @param nsMap       namespace map for prefix resolution
     * @param owner       the map field name (for diagnostics)
     * @param kind        "key" or "value" (for diagnostics)
     */
    private @Nullable FieldSpec buildSyntheticMapKvField(ComponentSymbol c,
                                                          String syntheticName,
                                                          TypeRef type,
                                                          String pathStr,
                                                          Map<String, String> nsMap,
                                                          String owner,
                                                          String kind) {
        // Nested List<List<?>> not supported inside map
        if (TypeRefClassification.isJavaUtilList(type)) {
            TypeRef innerElem = type.typeArguments().isEmpty()
                    ? TypeRef.of("java.lang", "Object")
                    : type.typeArguments().get(0);
            if (TypeRefClassification.isJavaUtilList(innerElem)) {
                sp.diagnostics().error(c.nativeHandle(),
                        "@XmlMap '" + kind + "' of '" + owner + "': List<List<?>> not supported");
                return null;
            }
        }
        // Nested Map inside map not supported
        if (TypeRefClassification.isJavaUtilMap(type)) {
            sp.diagnostics().error(c.nativeHandle(),
                    "@XmlMap '" + kind + "' of '" + owner + "': nested Map not supported");
            return null;
        }

        boolean isList = TypeRefClassification.isJavaUtilList(type);
        TypeRef elemType = isList
                ? (type.typeArguments().isEmpty()
                        ? TypeRef.of("java.lang", "Object")
                        : type.typeArguments().get(0))
                : type;

        // Reject nullable element inside Map's List value (e.g. `Map<K, List<V?>>`). Mirrors
        // the KSP-side rule that a List value's element nullability is meaningless when the
        // streaming aggregator can never emit null — the parser collects only matched values.
        if (isList && elemType.nullable()) {
            sp.diagnostics().error(c.nativeHandle(),
                    "@XmlMap '" + kind + "' of '" + owner
                            + "': nullable element inside List<…> not supported");
            return null;
        }

        // nullable = type arg is nullable (no @Nullable on box in TypeRef here — use nullable flag)
        boolean nullable = !isList && elemType.nullable();

        Source source;
        if (pathStr.startsWith("@")) {
            String rest = pathStr.substring(1);
            if (rest.isEmpty() || rest.contains("/")) {
                sp.diagnostics().error(c.nativeHandle(),
                        "@XmlMap '" + kind + "' of '" + owner
                                + "': '@' path must be a single attribute name");
                return null;
            }
            QNameInfo aqn = resolveQName(rest, nsMap, null, c,
                    "@XmlMap '" + kind + "'", syntheticName);
            if (aqn == null) return null;
            source = new Source.Attr(aqn.ns(), aqn.local());
        } else if (pathStr.isEmpty() || pathStr.equals(".")) {
            // Self-step shorthand: the entry element itself supplies the value (scalar →
            // its text content, nested record → the entire entry element). The Child path
            // carries a single "." element segment so downstream emit logic can recognise
            // the self-step in a uniform way; mirrors `parseChildPath`'s "." escape hatch
            // and the KSP-side `buildSyntheticMapKvField` that this code subsumes.
            source = new Source.Child(
                    List.of(new PathSeg.Element(nsMap.get(""), ".")),
                    false);
        } else {
            Source.Child parsed = parseChildPath(pathStr, syntheticName, nsMap, c);
            if (parsed == null) return null;
            source = parsed;
        }

        Coerce coerce;
        TypeRef elemTypeName;
        TypeRef boxedTypeName;
        String elemFq;

        if (TypeRefClassification.isScalarOrTemporal(elemType)) {
            ScalarKind sk = TypeRefClassification.scalarKind(elemType);
            assert sk != null;
            coerce = scalarCoerce(sk, null, c, syntheticName);
            if (coerce == null) return null;
            boxedTypeName = TypeRefClassification.boxedScalar(sk);
            // Propagate the synthetic kv-field's nullability onto its boxed type so the
            // outer Map<K, V> reported by buildSyntheticMapKvField sees `Integer?` for a
            // declared `Map<String, Int?>` instead of plain `Integer`.
            if (nullable) boxedTypeName = boxedTypeName.asNullable();
            elemTypeName = boxedTypeName;
            elemFq = elemType.primitive() ? elemType.simpleName() : elemType.qualifiedName();
        } else {
            // Check if the element type is a known nested record via the SPI
            RecordSymbol nested = sp.lookupRecord(elemType.qualifiedName());
            if (nested != null) {
                // Nested record value
                if (!(source instanceof Source.Child)) {
                    sp.diagnostics().error(c.nativeHandle(),
                            "@XmlMap '" + kind + "' of '" + owner
                                    + "': nested record requires an element path, not '@attr'");
                    return null;
                }
                String nestedFq = ensureNested(c, syntheticName, nested, nsMap, true);
                if (nestedFq == null) return null;
                coerce = new Coerce.Nested(nestedFq);
                boxedTypeName = elemType;
                elemTypeName = elemType;
                elemFq = nestedFq;
            } else {
                sp.diagnostics().error(c.nativeHandle(),
                        "@XmlMap '" + kind + "' of '" + owner
                                + "': unsupported type '" + elemType.qualifiedName() + "'");
                return null;
            }
        }

        TypeRef fieldTypeName;
        if (isList) {
            fieldTypeName = TypeRef.parameterized("java.util", "List", List.of(elemTypeName));
        } else {
            fieldTypeName = elemTypeName;
        }

        return new FieldSpec(
                syntheticName,
                !nullable && !isList,
                isList,
                boxedTypeName,
                fieldTypeName,
                elemTypeName,
                elemFq,
                source,
                coerce);
    }

    // ------------------------------------------------------------------ @XmlChild classification

    /**
     * Classifies a single component as {@code @XmlChild} (explicit or implicit). Returns
     * {@code null} on validation error (diagnostic already reported).
     *
     * <p>Components without any XML binding annotation fall into this branch as implicit children
     * whose path is simply the component name (mirroring Classifier.java lines 267-270).
     */
    private @Nullable FieldSpec classifyChild(ComponentSymbol c,
                                               Map<String, String> nsMap) {
        String name = c.name();

        // ---- Raw List guard (mirrors original Classifier "Raw List is not supported") ----
        if (c.isList() && c.type().typeArguments().isEmpty()) {
            sp.diagnostics().error(c.nativeHandle(),
                    "Raw List is not supported; use List<T> on '" + name + "'");
            return null;
        }
        // ---- List<List<T>> and List<Optional<T>> guards ----
        if (c.isList() && TypeRefClassification.isJavaUtilList(c.elementType())) {
            sp.diagnostics().error(c.nativeHandle(),
                    "List<List<T>> is not supported on '" + name + "'");
            return null;
        }
        if (c.isList() && TypeRefClassification.isOptional(c.elementType())) {
            sp.diagnostics().error(c.nativeHandle(),
                    "List<Optional<T>> is not supported on '" + name + "'");
            return null;
        }

        TypeRef elemType = c.elementType();

        // ---- Determine source ----
        Source source;
        boolean hasXmlChild = c.annotations().has(FQ_XML_CHILD);
        if (hasXmlChild) {
            String pathRaw = c.annotations().stringValue(FQ_XML_CHILD, "path");
            if (pathRaw == null || pathRaw.isEmpty()) {
                // No explicit path: use the component name as a single-segment child
                QNameInfo qn = resolveQName(name, nsMap, nsMap.get(""), c, "@XmlChild", name);
                if (qn == null) return null;
                source = new Source.Child(List.of(new PathSeg.Element(qn.ns(), qn.local())), false);
            } else {
                source = parseChildPath(pathRaw, name, nsMap, c);
                if (source == null) return null;
            }
        } else {
            // Implicit child: use component name
            QNameInfo qn = resolveQName(name, nsMap, nsMap.get(""), c, "@XmlChild", name);
            if (qn == null) return null;
            source = new Source.Child(List.of(new PathSeg.Element(qn.ns(), qn.local())), false);
        }

        // ---- @XmlFormat and @XmlConverter ----
        boolean hasFormat    = c.annotations().has(FQ_XML_FORMAT);
        boolean hasConverter = c.annotations().has(FQ_XML_CONVERTER);

        if (hasFormat && hasConverter) {
            sp.diagnostics().error(c.nativeHandle(),
                    "@XmlFormat and @XmlConverter are mutually exclusive on '" + name + "'");
            return null;
        }

        // Extract format pattern; detailed validation happens later after type determination
        String formatPattern = null;
        if (hasFormat) {
            String raw = c.annotations().stringValue(FQ_XML_FORMAT, "pattern");
            formatPattern = (raw != null) ? raw : "";
        }

        // ---- Coerce + type names (mirrors Classifier.java lines 280-340) ----
        boolean primitive = elemType.primitive();
        boolean required = !c.nullable();

        TypeRef boxedTypeName;
        TypeRef elemTypeName;
        TypeRef fieldType;
        String elemFq;
        Coerce coerce;

        if (hasConverter) {
            ConverterResult cr = resolveConverter(c, name, elemType);
            if (cr.error()) return null;
            ScalarBundle sb = resolveScalarBundle(c, name, elemType, cr.info(), null);
            if (sb == null) return null;
            coerce = sb.coerce();
            boxedTypeName = sb.boxedTypeName();
            elemTypeName = sb.elemTypeName();
            elemFq = sb.elemFq();
        } else if (TypeRefClassification.isScalarOrTemporal(elemType)) {
            if (hasFormat && !TypeRefClassification.isFormattableType(elemType)) {
                sp.diagnostics().error(c.nativeHandle(),
                        "@XmlFormat on '" + name + "' is only supported for LocalDate, "
                                + "LocalDateTime, Instant, or BigDecimal; got " + elemType.qualifiedName());
                return null;
            }
            ScalarBundle sb = resolveScalarBundle(c, name, elemType, null, formatPattern);
            if (sb == null) return null;
            coerce = sb.coerce();
            boxedTypeName = sb.boxedTypeName();
            elemTypeName = sb.elemTypeName();
            elemFq = sb.elemFq();
        } else if (c.asNestedRecord() != null) {
            // Nested record
            // @XmlFormat has no effect on nested records
            if (hasFormat) {
                sp.diagnostics().error(c.nativeHandle(),
                        "@XmlFormat on '" + name + "' has no effect on a nested record");
                return null;
            }
            RecordSymbol nested = c.asNestedRecord();
            // terminating = isList or nullable (consistent with original)
            boolean terminating = c.isList() || c.nullable();
            String nestedFqn = ensureNested(c, name, Objects.requireNonNull(nested), nsMap, terminating);
            if (nestedFqn == null) return null;
            coerce = new Coerce.Nested(nestedFqn);
            boxedTypeName = elemType;
            elemTypeName = elemType;
            elemFq = nestedFqn;
        } else {
            // Unsupported type
            if (hasFormat) {
                sp.diagnostics().error(c.nativeHandle(),
                        "@XmlFormat on '" + name + "' is only supported for LocalDate, "
                                + "LocalDateTime, Instant, or BigDecimal; got " + elemType.qualifiedName());
            } else {
                sp.diagnostics().error(c.nativeHandle(),
                        "Unsupported field type '" + elemType.qualifiedName()
                                + "' for component '" + name + "'");
            }
            return null;
        }

        // ---- Field type ----
        if (c.isList()) {
            fieldType = TypeRef.parameterized("java.util", "List", List.of(elemTypeName));
        } else if (primitive) {
            fieldType = elemType;
        } else {
            fieldType = elemTypeName;
        }

        return new FieldSpec(
                name,
                required,
                c.isList(),
                boxedTypeName,
                fieldType,
                elemTypeName,
                elemFq,
                source,
                coerce);
    }

    // ------------------------------------------------------------------ path parsing

    /**
     * Parses an {@code @XmlChild} path expression into a {@link Source.Child}.
     *
     * <p>Delegates to the runtime {@link PathParser} for segment parsing and namespace resolution.
     * Returns {@code null} after reporting a diagnostic on parse failure.
     */
    private Source.@Nullable Child parseChildPath(String path, String fieldName,
                                                   Map<String, String> nsMap,
                                                   ComponentSymbol owner) {
        if (path.isBlank()) {
            sp.diagnostics().error(owner.nativeHandle(),
                    "@XmlChild path empty for '" + fieldName + "'");
            return null;
        }

        // Self-step shorthand: treat as single literal segment (matches Classifier.java behaviour)
        if (path.equals(".")) {
            return new Source.Child(
                    List.of(new PathSeg.Element(nsMap.get(""), ".")),
                    false);
        }

        boolean descendant = path.startsWith("//");
        if (path.startsWith("/") && !descendant) {
            sp.diagnostics().error(owner.nativeHandle(),
                    "@XmlChild path '" + path + "' for '" + fieldName
                            + "': invalid syntax (absolute paths are not supported)");
            return null;
        }

        String defaultNs = nsMap.get("");
        PathParser parser = new PathParser(nsMap::get, defaultNs);
        CompiledPath compiled;
        try {
            compiled = parser.parse(path);
        } catch (PathParseException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            sp.diagnostics().error(owner.nativeHandle(),
                    "@XmlChild path '" + path + "' for '" + fieldName + "': "
                            + rewriteParseError(msg));
            return null;
        }

        List<Step> rawSteps = compiled.getSteps();
        // PathParser auto-prepends a descendant axis to every relative path; strip it and rely
        // on our own boolean.
        List<Step> steps = !rawSteps.isEmpty() && rawSteps.get(0) instanceof Step.Descendant
                ? rawSteps.subList(1, rawSteps.size())
                : rawSteps;

        if (steps.isEmpty()) {
            sp.diagnostics().error(owner.nativeHandle(),
                    "@XmlChild path '" + path + "' for '" + fieldName + "': empty after axis");
            return null;
        }

        if (descendant && steps.get(0) instanceof Step.AttrLeaf) {
            sp.diagnostics().error(owner.nativeHandle(),
                    "@XmlChild path '" + path + "' for '" + fieldName
                            + "': descendant axis head must be an element");
            return null;
        }

        List<PathSeg> segs = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            boolean last = (i == steps.size() - 1);

            if (s instanceof Step.Descendant) {
                sp.diagnostics().error(owner.nativeHandle(),
                        "@XmlChild path '" + path + "' for '" + fieldName
                                + "': '//' is only allowed at the head of the path");
                return null;
            } else if (s instanceof Step.AttrLeaf attr) {
                if (!last) {
                    sp.diagnostics().error(owner.nativeHandle(),
                            "@XmlChild path '" + path + "' for '" + fieldName
                                    + "': '@' segment must be last");
                    return null;
                }
                if (i == 0) {
                    sp.diagnostics().error(owner.nativeHandle(),
                            "@XmlChild path '" + path + "' for '" + fieldName
                                    + "': use @XmlAttr for record-level attributes");
                    return null;
                }
                segs.add(new PathSeg.AttrLeaf(attr.getName().getNs(), attr.getName().getLocal()));
            } else {
                Step.Named named = (Step.Named) s;
                if ("*".equals(named.getName().getLocal())) {
                    sp.diagnostics().error(owner.nativeHandle(),
                            "@XmlChild path '" + path + "' for '" + fieldName
                                    + "': wildcard local-name '*' is not supported");
                    return null;
                }
                if (PathParser.WILDCARD.equals(named.getName().getNs())) {
                    sp.diagnostics().error(owner.nativeHandle(),
                            "@XmlChild path '" + path + "' for '" + fieldName
                                    + "': wildcard namespace '{*}' is not supported");
                    return null;
                }
                boolean onDescHead = descendant && i == 0;
                List<Predicate> brackets = named.getBrackets();
                for (Predicate b : brackets) {
                    if (childPredicateInvalid(b, path, fieldName, owner, onDescHead)) {
                        return null;
                    }
                }
                long indexBracketCount = brackets.stream().filter(CoreClassifier::containsIndex).count();
                if (indexBracketCount > 1) {
                    sp.diagnostics().error(owner.nativeHandle(),
                            "@XmlChild path '" + path + "' for '" + fieldName
                                    + "': only one positional predicate is allowed per segment (found multiple in '"
                                    + named.getName().getLocal() + "'). Express the second positional via @XmlRecord, or restructure your XML.");
                    return null;
                }
                segs.add(new PathSeg.Element(named.getName().getNs(), named.getName().getLocal(),
                        brackets));
            }
        }

        return new Source.Child(segs, descendant);
    }

    // ------------------------------------------------------------------ ensureNested

    /**
     * Ensures a nested record is registered in the {@link NestedRegistry}, classifying it
     * recursively if not yet done. Returns the nested record's FQN, or {@code null} on error.
     *
     * @param terminating when {@code true}, a cycle back to this record is acceptable
     *                    (e.g. for list or nullable fields that act as leaf terminators).
     */
    private @Nullable String ensureNested(ComponentSymbol owner, String fieldName,
                                           RecordSymbol nested, Map<String, String> outerNsMap,
                                           boolean terminating) {
        String fq = nested.qualifiedName();

        // Merge outer namespace map with the nested record's own @XmlNs declarations.
        // Nested overrides outer for the same prefix; conflicts are reported as errors
        // (mirroring original Classifier.java ensureNested lines 895-903).
        Map<String, String> nestedOwn = nested.declaredNamespaces();
        for (Map.Entry<String, String> e : nestedOwn.entrySet()) {
            String parentUri = outerNsMap.get(e.getKey());
            if (parentUri != null && !parentUri.equals(e.getValue())) {
                sp.diagnostics().error(owner.nativeHandle(),
                        "Nested record '" + fq + "' redeclares @XmlNs prefix '"
                                + e.getKey() + "' as '" + e.getValue()
                                + "' but the enclosing record binds it to '"
                                + parentUri + "'");
                return null;
            }
        }
        Map<String, String> mergedNs = new LinkedHashMap<>(outerNsMap);
        mergedNs.putAll(nestedOwn);

        // Already classified?
        if (registry.get(fq) != null) {
            return fq;
        }

        // Cycle detection. A non-terminating cycle (e.g. a single non-list, non-nullable
        // record field referring back to its own type) is unparseable and must be flagged.
        // A terminating cycle (List<Self> or Self?) is acceptable because the list/null
        // branch terminates the recursion at runtime; in that case return the FQN early
        // so the outer field can wire up the helper without recursing into classifyOne
        // again — recursing would otherwise loop forever since the nested spec is still
        // mid-construction (registry.get(fq) is null until classifyOne completes).
        if (registry.isInProgress(fq)) {
            if (terminating) {
                return fq;
            }
            sp.diagnostics().error(owner.nativeHandle(),
                    "circular nested record reference at '" + fieldName + "' (cycle involves '"
                            + fq + "')");
            return null;
        }

        // Allocate helper name and mark as in-progress
        registry.helperName(fq);
        registry.markInProgress(fq);
        try {
            RecordSpec nestedSpec = classifyOne(nested, mergedNs);
            if (nestedSpec == null) {
                // Error already reported inside classifyOne(nested, ...)
                return null;
            }
            registry.put(fq, nestedSpec);
        } finally {
            registry.unmarkInProgress(fq);
        }
        return fq;
    }

    // ------------------------------------------------------------------ private helpers

    /**
     * Resolves a raw QName string against the ns map, using {@code defaultNs} when no
     * prefix is present. Returns {@code null} after reporting a diagnostic on error.
     */
    private @Nullable QNameInfo resolveQName(String s, Map<String, String> nsMap,
                                              @Nullable String defaultNs,
                                              ComponentSymbol owner,
                                              String ctx, String fieldName) {
        int ci = s.indexOf(':');
        if (ci < 0) {
            return new QNameInfo(defaultNs, s);
        }
        String prefix = s.substring(0, ci);
        String local  = s.substring(ci + 1);
        if (prefix.isEmpty() || local.isEmpty()) {
            sp.diagnostics().error(owner.nativeHandle(),
                    ctx + " '" + s + "' on '" + fieldName + "' is malformed");
            return null;
        }
        String ns = nsMap.get(prefix);
        if (ns == null) {
            sp.diagnostics().error(owner.nativeHandle(),
                    ctx + " '" + s + "' on '" + fieldName + "': unbound NS prefix '"
                            + prefix + "' (declare via @XmlNs)");
            return null;
        }
        return new QNameInfo(ns, local);
    }

    /** Read and validate an {@code @XmlConverter} on {@code c}. Returns {@code NONE} when the
     *  component has no converter, {@code ERROR} after reporting a diagnostic, or a populated
     *  {@link ConverterResult} when validation succeeds. */
    private ConverterResult resolveConverter(ComponentSymbol c, String name, TypeRef elemType) {
        if (!c.annotations().has(FQ_XML_CONVERTER)) {
            return ConverterResult.NONE;
        }
        TypeRef converterTypeRef = c.annotations().classValue(FQ_XML_CONVERTER, "cls");
        if (converterTypeRef == null) {
            sp.diagnostics().error(c.nativeHandle(),
                    "@XmlConverter on '" + name + "' is missing a 'cls' value");
            return ConverterResult.ERROR;
        }
        ConverterInfo info = validateConverter(c, name, converterTypeRef, elemType);
        return info == null ? ConverterResult.ERROR : new ConverterResult(info, false);
    }

    /** Compute coerce + type-name triple for a scalar/text/attr field, factoring in optional
     *  converter and {@code @XmlFormat} pattern. Returns {@code null} after reporting a
     *  diagnostic on scalar-coerce failure (caller propagates the null). */
    private @Nullable ScalarBundle resolveScalarBundle(ComponentSymbol c, String name,
                                                        TypeRef elemType,
                                                        @Nullable ConverterInfo converterInfo,
                                                        @Nullable String formatPattern) {
        boolean primitive = elemType.primitive();
        if (converterInfo != null) {
            Coerce coerce = new Coerce.Custom(converterInfo.typeRef(), converterInfo.fqn());
            if (TypeRefClassification.isScalarOrTemporal(elemType)) {
                ScalarKind kind = TypeRefClassification.scalarKind(elemType);
                assert kind != null;
                TypeRef boxed = TypeRefClassification.boxedScalar(kind);
                return new ScalarBundle(boxed,
                        primitive ? elemType : boxed,
                        primitive ? elemType.simpleName() : elemType.qualifiedName(),
                        coerce);
            }
            return new ScalarBundle(elemType, elemType, elemType.qualifiedName(), coerce);
        }
        ScalarKind kind = TypeRefClassification.scalarKind(elemType);
        assert kind != null;
        Coerce coerce = scalarCoerce(kind, formatPattern, c, name);
        if (coerce == null) return null;
        TypeRef boxed = TypeRefClassification.boxedScalar(kind);
        return new ScalarBundle(boxed,
                primitive ? elemType : boxed,
                primitive ? elemType.simpleName() : elemType.qualifiedName(),
                coerce);
    }

    /**
     * Validates the converter {@link TypeRef} via the {@link xmlfluss.codegen.spi.SymbolProvider}
     * SPI: the referenced type must be resolvable, must expose a public no-arg constructor,
     * must (transitively) implement {@code xmlfluss.Converter<T>}, and that {@code T} must be
     * assignable to the declared field type (with primitive boxing applied to the field type).
     *
     * <p>Diagnostic wording matches the pre-lift {@code AptConverterValidator} verbatim so the
     * existing assertions in {@code ConverterTest} continue to pass after the lift.
     */
    private @Nullable ConverterInfo validateConverter(ComponentSymbol c, String fieldName,
                                                      TypeRef converter, TypeRef target) {
        String converterFq = converter.qualifiedName();
        TypeSymbol converterType = sp.lookupType(converterFq);
        if (converterType == null) {
            sp.diagnostics().error(c.nativeHandle(),
                    "@XmlConverter cls on '" + fieldName + "' could not be resolved: " + converterFq);
            return null;
        }
        if (!converterType.hasPublicNoArgConstructor()) {
            sp.diagnostics().error(c.nativeHandle(),
                    "@XmlConverter on '" + fieldName + "': " + converterFq
                            + " must have a public no-arg constructor");
            return null;
        }
        TypeRef produces = converterType.typeArgumentOf(FQ_CONVERTER, 0);
        if (produces == null) {
            sp.diagnostics().error(c.nativeHandle(),
                    "@XmlConverter on '" + fieldName + "': " + converterFq
                            + " does not implement " + FQ_CONVERTER + "<T>");
            return null;
        }
        if (!converterArgAssignable(produces, target)) {
            sp.diagnostics().error(c.nativeHandle(),
                    "@XmlConverter on '" + fieldName + "': converter produces "
                            + produces.qualifiedName()
                            + " which is not assignable to field type " + target.qualifiedName());
            return null;
        }
        return new ConverterInfo(converter, converterFq);
    }

    /** Mirror of the original {@code Classifier.assignableTo}: equality on FQN with primitive boxing. */
    private static boolean converterArgAssignable(TypeRef converterArg, TypeRef target) {
        String boxed = TypeRefClassification.boxIfPrimitiveFqn(target.qualifiedName());
        return converterArg.qualifiedName().equals(boxed)
                || converterArg.qualifiedName().equals(target.qualifiedName());
    }

    /**
     * Computes the {@link Coerce} for scalar/temporal types. Returns {@code null} after
     * reporting a diagnostic when validation fails.
     */
    private @Nullable Coerce scalarCoerce(ScalarKind kind, @Nullable String formatPattern,
                                           ComponentSymbol owner, String fieldName) {
        return switch (kind) {
            case STRING -> new Coerce.AsString();
            case INT, LONG, DOUBLE, BOOLEAN -> {
                if (formatPattern != null) {
                    sp.diagnostics().error(owner.nativeHandle(),
                            "@XmlFormat on '" + fieldName + "' is only supported for LocalDate, "
                                    + "LocalDateTime, Instant, or BigDecimal");
                    yield null;
                }
                yield new Coerce.Scalar(kind);
            }
            case LOCAL_DATE, LOCAL_DATE_TIME, INSTANT ->
                    new Coerce.Temporal(kind, formatPattern == null ? "" : formatPattern);
            case BIG_DECIMAL ->
                    new Coerce.Decimal(formatPattern == null ? "" : formatPattern);
        };
    }

    /**
     * Rewrites a raw {@link PathParseException} message to match the original
     * {@code Classifier.java rewriteParseError} wording.
     */
    private static String rewriteParseError(String msg) {
        String out = msg.replace("unbound namespace prefix", "unbound NS prefix");
        if (out.startsWith("expected local-name after ':'")
                || out.startsWith("expected local-name after '}'")) {
            out = "malformed (bad qname): " + out;
        }
        return out;
    }

    /**
     * Builds a key string for an attribute QName used for duplicate detection.
     * Mirrors the original {@code Classifier.qnameKey}.
     */
    private static String qnameKey(@Nullable String ns, String local) {
        return (ns == null ? "" : "{" + ns + "}") + local;
    }

    // ------------------------------------------------------------------ predicate / trie validation

    /**
     * Validates a single bracket predicate against its surrounding path context. Returns
     * {@code false} after reporting a diagnostic when the predicate is unsupported.
     *
     * <p>Mirrors original {@code Classifier.childPredicateInvalid}: rejects positional
     * {@code [N]} predicates on the descendant-axis head segment, and rejects wildcard
     * namespaces in attribute predicates. Boolean combinators recurse on both sides.
     */
    private boolean childPredicateInvalid(Predicate p, String path, String fieldName,
                                          ComponentSymbol where, boolean onDescendantHead) {
        if (p instanceof Predicate.Index idx) {
            if (onDescendantHead) {
                sp.diagnostics().error(where.nativeHandle(),
                        "@XmlChild path '" + path + "' for '" + fieldName
                                + "': positional predicate [" + idx.getN() + "] is not supported on the descendant-axis segment ('//<name>[N]'). "
                                + "Move the positional filter to a direct-axis segment (e.g. '//parent/item[" + idx.getN() + "]') or to @XmlRecord.");
                return true;
            }
        } else if (p instanceof Predicate.AttrEq ae) {
            if (PathParser.WILDCARD.equals(ae.getName().getNs())) {
                sp.diagnostics().error(where.nativeHandle(),
                        "@XmlChild path '" + path + "' for '" + fieldName
                                + "': wildcard namespace in attribute predicate is not supported");
                return true;
            }
        } else if (p instanceof Predicate.And and) {
            if (childPredicateInvalid(and.getL(), path, fieldName, where, onDescendantHead)) return true;
            return childPredicateInvalid(and.getR(), path, fieldName, where, onDescendantHead);
        } else if (p instanceof Predicate.Or or) {
            // Mirrors the KSP-side rule (and Classifier.validateChildPredicate): positional
            // [N] inside an `or` is not expressible as a streaming match; the emitter
            // assumes positional filters live in a single linear chain. Reject up front so
            // users see the actionable rewrite hint rather than an internal stack trace.
            if (containsIndex(or.getL()) || containsIndex(or.getR())) {
                sp.diagnostics().error(where.nativeHandle(),
                        "@XmlChild path '" + path + "' for '" + fieldName
                                + "': positional predicate inside 'or' is not supported. "
                                + "Use chained brackets ('[@x=\"v\"][N]') or 'and' to combine filters.");
                return true;
            }
            if (childPredicateInvalid(or.getL(), path, fieldName, where, onDescendantHead)) return true;
            return childPredicateInvalid(or.getR(), path, fieldName, where, onDescendantHead);
        }
        return false;
    }

    /** Returns {@code true} when {@code p} or any sub-predicate is a positional {@code [N]}. */
    private static boolean containsIndex(Predicate p) {
        if (p instanceof Predicate.Index) return true;
        if (p instanceof Predicate.And and) return containsIndex(and.getL()) || containsIndex(and.getR());
        if (p instanceof Predicate.Or or) return containsIndex(or.getL()) || containsIndex(or.getR());
        return false;
    }

    /**
     * Cross-field validation over all classified {@link Source.Child}, {@link Source.MapEntry},
     * and {@link Source.PolyChild} sources for one record. Returns {@code true} when no
     * conflicts were reported.
     *
     * <p>Mirrors original {@code Classifier.validateChildPaths}: builds a trie of the direct
     * (non-descendant) child paths and rejects (a) descendant-axis heads that collide with a
     * direct head; (b) {@code @XmlMap} entries that clash with a child or descendant head;
     * (c) duplicate map entries; (d) more than one tag-mode polymorphic field at the same
     * scope; (e) polymorphic tag/wrap names that clash with siblings.
     */
    private boolean validateChildPaths(RecordSymbol owner, String ownerFq, List<FieldSpec> fields) {
        TrieNode root = new TrieNode();
        Set<QKey> directKeys = new LinkedHashSet<>();
        for (FieldSpec f : fields) {
            if (!(f.source() instanceof Source.Child sc)) continue;
            if (sc.descendant()) continue;
            if (!insertIntoTrie(root, sc.segments(), f, owner, ownerFq)) return false;
            PathSeg first = sc.segments().get(0);
            if (first instanceof PathSeg.Element e) {
                directKeys.add(new QKey(e.ns(), e.name()));
            }
        }
        if (trieIsInvalid(root, owner, ownerFq)) return false;

        Set<QKey> descendantHeads = new LinkedHashSet<>();
        for (FieldSpec f : fields) {
            if (!(f.source() instanceof Source.Child sc)) continue;
            if (!sc.descendant()) continue;
            PathSeg first = sc.segments().get(0);
            if (first instanceof PathSeg.Element e) {
                descendantHeads.add(new QKey(e.ns(), e.name()));
            }
        }
        for (QKey k : descendantHeads) {
            if (directKeys.contains(k)) {
                sp.diagnostics().error(owner.nativeHandle(),
                        ownerFq + ": @XmlChild('" + k.local()
                                + "') and @XmlChild('//" + k.local()
                                + "') target the same head element '" + k.local() + "'; pick one");
                return false;
            }
        }

        Set<QKey> seenMapEntries = new LinkedHashSet<>();
        for (FieldSpec f : fields) {
            if (!(f.source() instanceof Source.MapEntry me)) continue;
            QKey k = new QKey(me.entryNs(), me.entryLocal());
            if (directKeys.contains(k)) {
                sp.diagnostics().error(owner.nativeHandle(),
                        ownerFq + ": @XmlMap entry '" + me.entryLocal()
                                + "' on field '" + f.name() + "' clashes with another @XmlChild's first segment");
                return false;
            }
            if (descendantHeads.contains(k)) {
                sp.diagnostics().error(owner.nativeHandle(),
                        ownerFq + ": @XmlMap entry '" + me.entryLocal()
                                + "' on field '" + f.name() + "' clashes with a descendant @XmlChild('//"
                                + k.local() + "') head");
                return false;
            }
            if (!seenMapEntries.add(k)) {
                sp.diagnostics().error(owner.nativeHandle(),
                        ownerFq + ": duplicate @XmlMap entry '" + me.entryLocal()
                                + "' on field '" + f.name() + "'");
                return false;
            }
        }

        Set<QKey> seenPolyKeys = new LinkedHashSet<>();
        boolean sawTagMode = false;
        for (FieldSpec f : fields) {
            if (!(f.source() instanceof Source.PolyChild pc)) continue;
            PolyDispatch d = pc.dispatch();
            if (d instanceof PolyDispatch.Tag tag) {
                if (sawTagMode) {
                    sp.diagnostics().error(owner.nativeHandle(),
                            ownerFq
                                    + ": more than one tag-mode polymorphic @XmlChild field at the same scope (field '"
                                    + f.name() + "')");
                    return false;
                }
                sawTagMode = true;
                for (TagVariant v : tag.variants()) {
                    QKey k = new QKey(v.ns(), v.local());
                    if (directKeys.contains(k) || seenMapEntries.contains(k) || !seenPolyKeys.add(k)) {
                        sp.diagnostics().error(owner.nativeHandle(),
                                ownerFq + ": polymorphic subtype tag '" + v.local()
                                        + "' on field '" + f.name()
                                        + "' clashes with another @XmlChild / @XmlMap / subtype");
                        return false;
                    }
                    if (descendantHeads.contains(k)) {
                        sp.diagnostics().error(owner.nativeHandle(),
                                ownerFq + ": polymorphic subtype tag '" + v.local()
                                        + "' on field '" + f.name() + "' clashes with a descendant @XmlChild('//"
                                        + k.local() + "') head");
                        return false;
                    }
                }
            } else {
                PolyDispatch.Attr ad = (PolyDispatch.Attr) d;
                QKey k = new QKey(ad.wrapNs(), ad.wrapLocal());
                if (directKeys.contains(k) || seenMapEntries.contains(k) || !seenPolyKeys.add(k)) {
                    sp.diagnostics().error(owner.nativeHandle(),
                            ownerFq + ": polymorphic wrap tag '" + ad.wrapLocal()
                                    + "' on field '" + f.name()
                                    + "' clashes with another @XmlChild / @XmlMap / subtype");
                    return false;
                }
                if (descendantHeads.contains(k)) {
                    sp.diagnostics().error(owner.nativeHandle(),
                            ownerFq + ": polymorphic wrap tag '" + ad.wrapLocal()
                                    + "' on field '" + f.name() + "' clashes with a descendant @XmlChild('//"
                                    + k.local() + "') head");
                    return false;
                }
            }
        }
        return true;
    }

    /** Inserts a single field's segment chain into the trie. Returns {@code false} on bad-tail error. */
    private boolean insertIntoTrie(TrieNode root, List<PathSeg> segments, FieldSpec f,
                                    RecordSymbol owner, String ownerFq) {
        int i = 0;
        while (i < segments.size() && segments.get(i) instanceof PathSeg.Element) i++;
        boolean validElementsTail = i == segments.size();
        boolean validAttrTail = i == segments.size() - 1 && segments.get(i) instanceof PathSeg.AttrLeaf;
        if (!validElementsTail && !validAttrTail) {
            sp.diagnostics().error(owner.nativeHandle(),
                    ownerFq + ": invalid path tail for field '" + f.name() + "'");
            return false;
        }
        TrieNode.insert(root, segments, f);
        return true;
    }

    /**
     * Recursively validates a trie node and its children. Reports diagnostics for mixed
     * text/nested/descend at the same element, multiple non-list text/nested fields targeting
     * the same element, and mixed list/non-list nested fields. Returns {@code false} on the
     * first error encountered.
     */
    private boolean trieIsInvalid(TrieNode node, RecordSymbol owner, String ownerFq) {
        List<FieldSpec> texts = node.textEntries();
        List<FieldSpec> nested = node.nestedEntries();
        Map<?, TrieNode> children = node.children();
        int mix = 0;
        if (!texts.isEmpty()) mix++;
        if (!nested.isEmpty()) mix++;
        if (!children.isEmpty()) mix++;
        if (mix > 1) {
            String textNames = texts.stream().map(FieldSpec::name).reduce((a, b) -> a + "," + b).orElse("");
            String nestedNames = nested.stream().map(FieldSpec::name).reduce((a, b) -> a + "," + b).orElse("");
            sp.diagnostics().error(owner.nativeHandle(),
                    ownerFq + ": cannot mix text/nested/descend at same element [text="
                            + textNames + " nested=" + nestedNames + " children=" + children.size() + "]");
            return true;
        }
        long nonListText = texts.stream().filter(f -> !f.isList()).count();
        if (nonListText > 1) {
            String names = texts.stream().filter(f -> !f.isList())
                    .map(FieldSpec::name).reduce((a, b) -> a + "," + b).orElse("");
            sp.diagnostics().error(owner.nativeHandle(),
                    ownerFq + ": multiple non-list text fields [" + names + "] target same element");
            return true;
        }
        long nonListNested = nested.stream().filter(f -> !f.isList()).count();
        if (nonListNested > 1) {
            String names = nested.stream().filter(f -> !f.isList())
                    .map(FieldSpec::name).reduce((a, b) -> a + "," + b).orElse("");
            sp.diagnostics().error(owner.nativeHandle(),
                    ownerFq + ": multiple non-list nested fields [" + names + "] target same element");
            return true;
        }
        if (nested.size() > 1
                && nested.stream().anyMatch(FieldSpec::isList)
                && nested.stream().anyMatch(f -> !f.isList())) {
            sp.diagnostics().error(owner.nativeHandle(),
                    ownerFq + ": cannot mix list and non-list nested fields at same element");
            return true;
        }
        for (TrieNode c : children.values()) {
            if (trieIsInvalid(c, owner, ownerFq)) return true;
        }
        return false;
    }
}
