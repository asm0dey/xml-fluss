package xmlfluss.codegen.classify;

import org.jspecify.annotations.Nullable;
import xmlfluss.codegen.model.*;
import xmlfluss.codegen.plan.TrieNode;
import xmlfluss.codegen.spi.ComponentSymbol;
import xmlfluss.codegen.spi.RecordSymbol;
import xmlfluss.codegen.spi.SymbolProvider;
import xmlfluss.codegen.spi.TypeSymbol;
import xmlfluss.path.*;

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
    public static final String ON_FIELD = "' on field '";
    public static final String XML_CHILD = "@XmlChild";
    public static final String CLASHES_WITH_A_DESCENDANT_XML_CHILD = "' clashes with a descendant " + XML_CHILD + "('//";
    public static final String HEAD = "') head";
    public static final String XML_CONVERTER = "@XmlConverter";
    public static final String XML_FORMAT = "@XmlFormat";
    public static final String XML_FORMAT_AND_XML_CONVERTER_ARE_MUTUALLY_EXCLUSIVE_ON = XML_FORMAT + " and " + XML_CONVERTER + " are mutually exclusive on '";
    public static final String POLYMORPHIC_FIELD = "polymorphic field '";
    public static final String SUBTYPE = "': subtype ";
    public static final String JAVA_UTIL_PACKAGE = "java.util";
    public static final String GOT = "', got ";
    public static final String ON = " on '";
    public static final String XML_FORMAT_ON = XML_FORMAT + ON;
    public static final String PATTERN = "pattern";
    public static final String FOR = "' for '";
    public static final String XML_MAP = "@XmlMap";
    public static final String OF = "' of '";
    public static final String ENTRY = " entry '";
    public static final String PATH = " path '";
    public static final String CLASHES_WITH_ANOTHER = "' clashes with another ";
    public static final String NOT_SUPPORTED_ON_POLYMORPHIC_FIELD = " not supported on polymorphic field '";

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

    /** Mutable state shared across one record's component classification pass. */
    private static final class ComponentCtx {
        final List<FieldSpec> fields = new ArrayList<>();
        final Set<String> seenAttrKeys = new HashSet<>();
        final Map<String, String> firstAttrBindingByKey = new HashMap<>();
        int textCount = 0;
    }

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
        if (!validateRecordPath(record, recordPath, nsMap)) return null;

        List<FieldSpec> fields = classifyComponents(record, nsMap);
        if (fields == null) return null;

        String ownerFq = record.qualifiedName();
        if (!validateChildPaths(record, ownerFq, fields)) return null;

        return new RecordSpec(
                record.packageName(),
                record.simpleName(),
                Objects.requireNonNull(recordPath),
                nsMap,
                fields,
                record.nativeHandle());
    }

    /**
     * Eagerly validates the {@code @XmlRecord(path=...)} string against the runtime path parser so
     * syntax errors surface as a single ERROR diagnostic instead of a stack trace at emit time.
     * Mirrors original Classifier.classifyTopLevel lines 74-81.
     */
    private boolean validateRecordPath(RecordSymbol record, String recordPath, Map<String, String> nsMap) {
        if (recordPath.isEmpty()) return true;
        try {
            xmlfluss.runtime.Paths.INSTANCE.compile(recordPath, nsMap);
            return true;
        } catch (RuntimeException ex) {
            sp.diagnostics().error(record.nativeHandle(),
                    "@XmlRecord path '" + recordPath + "' is invalid: " + ex.getMessage());
            return false;
        }
    }

    /**
     * Classifies every component of {@code record}, accumulating fields and dup-attr/text-count
     * state in a {@link ComponentCtx}. Returns the collected fields, or {@code null} if any
     * component reported an error (all components are still visited so every diagnostic is
     * emitted in one pass).
     */
    private @Nullable List<FieldSpec> classifyComponents(RecordSymbol record, Map<String, String> nsMap) {
        ComponentCtx ctx = new ComponentCtx();
        boolean hadError = false;
        for (ComponentSymbol c : record.components()) {
            if (!classifyComponent(c, nsMap, ctx)) {
                hadError = true;
            }
        }
        return hadError ? null : ctx.fields;
    }

    /**
     * Classifies a single component, dispatching to the binding-specific branch. Returns
     * {@code true} on success (or no-op skip), {@code false} after any diagnostic.
     */
    private boolean classifyComponent(ComponentSymbol c, Map<String, String> nsMap, ComponentCtx ctx) {
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
                    "@XmlAttr / " + XML_CHILD + " / @XmlText / " + XML_MAP + " are mutually exclusive on '"
                            + cName + "'");
            return false;
        }

        // Reject nullable List fields. Kotlin lets users write `List<X>?`, but the parser
        // contract is "no matches → empty list". A nullable list would require the parser
        // to choose between null and an empty list, with no guidance from the source.
        // Java records have no syntactic equivalent so APT doesn't trip this; CoreClassifier
        // enforces it once for every host language.
        if (c.isList() && c.nullable()) {
            sp.diagnostics().error(c.nativeHandle(),
                    "List field '" + cName + "' must not be nullable; use empty list");
            return false;
        }

        // Mutual exclusion: @XmlFormat and @XmlConverter (checked here so all branches enforce it)
        boolean hasFormat    = c.annotations().has(FQ_XML_FORMAT);
        boolean hasConverter = c.annotations().has(FQ_XML_CONVERTER);
        if (hasFormat && hasConverter) {
            sp.diagnostics().error(c.nativeHandle(),
                    XML_FORMAT_AND_XML_CONVERTER_ARE_MUTUALLY_EXCLUSIVE_ON + cName + "'");
            return false;
        }

        // Polymorphic dispatch: if the element type is a sealed parent annotated with
        // @XmlPolymorphic, and @XmlChild is present, route to classifyPolymorphic.
        // Mirror original Classifier.java lines 229-239.
        TypeRef checkType = c.isList() ? c.elementType() : c.type();
        RecordSymbol polyParent = sp.lookupRecord(checkType.qualifiedName());
        boolean isPolyParent = polyParent != null
                && polyParent.annotations().has(FQ_XML_POLYMORPHIC);

        if (isPolyParent && hasConverter) {
            sp.diagnostics().error(c.nativeHandle(),
                    XML_CONVERTER + NOT_SUPPORTED_ON_POLYMORPHIC_FIELD + cName + "'");
            return false;
        }
        if (isPolyParent && hasXmlChild) {
            FieldSpec spec = classifyPolymorphic(c, polyParent, nsMap);
            if (spec == null) return false;
            ctx.fields.add(spec);
            return true;
        }

        if (hasXmlAttr) return tryClassifyAttr(c, nsMap, ctx);
        if (hasXmlText) return tryClassifyText(c, ctx);
        if (hasXmlMap)  return tryClassifyMap(c, nsMap, ctx);
        if (TypeRefClassification.isJavaUtilMap(c.type())) {
            // Map<K,V> without @XmlMap → error (mirrors original Classifier.java lines 204-207)
            sp.diagnostics().error(c.nativeHandle(),
                    "Field '" + cName + "' is Map<K, V> but lacks " + XML_MAP);
            return false;
        }
        // @XmlChild (explicit or implicit) branch — unannotated components fall through with
        // the field name as the default path. APT relies on this shorthand for plain Java
        // records (`record Doc(String body) {}` → implicit `@XmlChild(path="body")`); KSP code
        // paths that want the strict "no binding rejected" diagnostic should emit it before
        // reaching the classifier, since the classifier can't tell host-language semantics
        // apart at this layer.
        return tryClassifyChild(c, nsMap, ctx);
    }

    /** Attr branch: classifies, rejects duplicate {@code @XmlAttr} names, then appends. */
    private boolean tryClassifyAttr(ComponentSymbol c, Map<String, String> nsMap, ComponentCtx ctx) {
        FieldSpec spec = classifyAttr(c, nsMap);
        if (spec == null) return false;
        if (spec.source() instanceof Source.Attr attr) {
            String key = qnameKey(attr.ns(), attr.name());
            if (!ctx.seenAttrKeys.add(key)) {
                String first = ctx.firstAttrBindingByKey.get(key);
                sp.diagnostics().error(c.nativeHandle(),
                        "duplicate @XmlAttr name '" + attr.name() + "': '"
                                + first + "' and '" + spec.name() + "' both bind it");
                return false;
            }
            ctx.firstAttrBindingByKey.put(key, spec.name());
        }
        ctx.fields.add(spec);
        return true;
    }

    /** Text branch: classifies, rejects more than one {@code @XmlText} per record. */
    private boolean tryClassifyText(ComponentSymbol c, ComponentCtx ctx) {
        FieldSpec spec = classifyText(c);
        if (spec == null) return false;
        ctx.textCount++;
        if (ctx.textCount > 1) {
            sp.diagnostics().error(c.nativeHandle(),
                    "@XmlText may appear at most once per record");
            return false;
        }
        ctx.fields.add(spec);
        return true;
    }

    /** Map branch: classifies, then appends. */
    private boolean tryClassifyMap(ComponentSymbol c, Map<String, String> nsMap, ComponentCtx ctx) {
        FieldSpec spec = classifyMap(c, nsMap);
        if (spec == null) return false;
        ctx.fields.add(spec);
        return true;
    }

    /** Child branch (explicit or implicit @XmlChild): classifies, then appends. */
    private boolean tryClassifyChild(ComponentSymbol c, Map<String, String> nsMap, ComponentCtx ctx) {
        FieldSpec spec = classifyChild(c, nsMap);
        if (spec == null) return false;
        ctx.fields.add(spec);
        return true;
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

        List<RecordSymbol> subtypes = validatePolymorphicSubtypes(c, name, polyParent, nsMap);
        if (subtypes == null) return null;

        // Discriminator comes from the *parent* type's @XmlPolymorphic annotation, not the field.
        // Mirrors original Classifier.java line 355.
        String parentDisc = polyParent.annotations().stringValue(FQ_XML_POLYMORPHIC, "discriminator");
        String discriminator = (parentDisc == null) ? "" : parentDisc;

        String pathRaw = c.annotations().stringValue(FQ_XML_CHILD, "path");
        String rawPath = (pathRaw == null) ? "" : pathRaw;

        PolyDispatch dispatch = discriminator.isEmpty()
                ? buildTagDispatch(c, name, subtypes, rawPath, nsMap)
                : buildAttrDispatch(c, name, subtypes, rawPath, discriminator, nsMap);
        if (dispatch == null) return null;

        return buildPolyFieldSpec(c, name, polyParent, dispatch);
    }

    /**
     * Validates polymorphic-field-level constraints: rejects {@code @XmlFormat}, requires the sealed
     * parent to have at least one permitted subclass, requires every subclass to carry
     * {@code @XmlSubtype}, and registers each subclass as a nested record. Returns the subtype list
     * or {@code null} after a diagnostic.
     */
    private @Nullable List<RecordSymbol> validatePolymorphicSubtypes(ComponentSymbol c, String name,
                                                                     RecordSymbol polyParent,
                                                                     Map<String, String> nsMap) {
        if (c.annotations().has(FQ_XML_FORMAT)) {
            sp.diagnostics().error(c.nativeHandle(),
                    XML_FORMAT + NOT_SUPPORTED_ON_POLYMORPHIC_FIELD + name + "'");
            return null;
        }

        List<RecordSymbol> subtypes = polyParent.sealedSubtypes();
        if (subtypes.isEmpty()) {
            sp.diagnostics().error(c.nativeHandle(),
                    POLYMORPHIC_FIELD + name + "': sealed type "
                            + polyParent.qualifiedName() + " has no permitted subclasses");
            return null;
        }

        for (RecordSymbol s : subtypes) {
            if (!s.annotations().has(FQ_XML_SUBTYPE)) {
                sp.diagnostics().error(c.nativeHandle(),
                        POLYMORPHIC_FIELD + name + SUBTYPE + s.qualifiedName()
                                + " is missing @XmlSubtype");
                return null;
            }
        }

        for (RecordSymbol s : subtypes) {
            String nestedFq = ensureNested(c, name, s, nsMap, /*terminating=*/true);
            if (nestedFq == null) return null;
        }
        return subtypes;
    }

    /**
     * Tag-mode dispatch: rejects non-empty {@code @XmlChild} path, then collects one
     * {@link TagVariant} per subtype with qname-based duplicate detection.
     */
    private @Nullable PolyDispatch buildTagDispatch(ComponentSymbol c, String name,
                                                    List<RecordSymbol> subtypes, String rawPath,
                                                    Map<String, String> nsMap) {
        if (!rawPath.isEmpty()) {
            sp.diagnostics().error(c.nativeHandle(),
                    POLYMORPHIC_FIELD + name
                            + "': tag-mode " + XML_CHILD + " path must be empty (got '" + rawPath + "')");
            return null;
        }
        List<TagVariant> variants = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RecordSymbol s : subtypes) {
            String subName = readSubtypeName(c, name, s);
            if (subName == null) return null;
            QNameInfo qn = resolveQName(subName, nsMap, nsMap.get(""), c, "@XmlSubtype", name);
            if (qn == null) return null;
            if (!seen.add(qnameKey(qn.ns(), qn.local()))) {
                sp.diagnostics().error(c.nativeHandle(),
                        POLYMORPHIC_FIELD + name + "': duplicate @XmlSubtype tag '"
                                + subName + "'");
                return null;
            }
            variants.add(new TagVariant(qn.ns(), qn.local(), s.qualifiedName()));
        }
        return new PolyDispatch.Tag(variants);
    }

    /**
     * Attr-mode dispatch: validates the {@code @XmlPolymorphic.discriminator} syntax and the
     * wrapping {@code @XmlChild} path shape, then collects one {@link AttrVariant} per subtype with
     * raw-value duplicate detection.
     */
    private @Nullable PolyDispatch buildAttrDispatch(ComponentSymbol c, String name,
                                                     List<RecordSymbol> subtypes, String rawPath,
                                                     String discriminator, Map<String, String> nsMap) {
        if (!discriminator.startsWith("@")) {
            sp.diagnostics().error(c.nativeHandle(),
                    POLYMORPHIC_FIELD + name
                            + "': @XmlPolymorphic.discriminator must start with '@' (got '"
                            + discriminator + "')");
            return null;
        }
        String attrRaw = discriminator.substring(1);
        if (attrRaw.isEmpty() || attrRaw.contains("/")) {
            sp.diagnostics().error(c.nativeHandle(),
                    POLYMORPHIC_FIELD + name + "': bad discriminator '" + discriminator + "'");
            return null;
        }
        QNameInfo aqn = resolveQName(attrRaw, nsMap, /*defaultNs=*/null, c,
                "@XmlPolymorphic discriminator", name);
        if (aqn == null) return null;
        if (rawPath.isEmpty()) {
            sp.diagnostics().error(c.nativeHandle(),
                    POLYMORPHIC_FIELD + name
                            + "': attr-mode " + XML_CHILD + " requires the wrapping element path");
            return null;
        }
        if (rawPath.startsWith("//") || rawPath.contains("/") || rawPath.startsWith("@")) {
            sp.diagnostics().error(c.nativeHandle(),
                    POLYMORPHIC_FIELD + name
                            + "': attr-mode " + XML_CHILD + " path must be a single direct-child"
                            + " element (got '" + rawPath + "')");
            return null;
        }
        QNameInfo wqn = resolveQName(rawPath, nsMap, nsMap.get(""), c, XML_CHILD, name);
        if (wqn == null) return null;
        List<AttrVariant> variants = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RecordSymbol s : subtypes) {
            String value = readSubtypeName(c, name, s);
            if (value == null) return null;
            if (!seen.add(value)) {
                sp.diagnostics().error(c.nativeHandle(),
                        POLYMORPHIC_FIELD + name
                                + "': duplicate @XmlSubtype attr value '" + value + "'");
                return null;
            }
            variants.add(new AttrVariant(value, s.qualifiedName()));
        }
        return new PolyDispatch.Attr(wqn.ns(), wqn.local(), aqn.ns(), aqn.local(), variants);
    }

    /**
     * Reads {@code @XmlSubtype.name} from {@code s}, reporting a diagnostic and returning {@code null}
     * if missing or empty.
     */
    private @Nullable String readSubtypeName(ComponentSymbol c, String fieldName, RecordSymbol s) {
        String value = s.annotations().stringValue(FQ_XML_SUBTYPE, "name");
        if (value == null || value.isEmpty()) {
            sp.diagnostics().error(c.nativeHandle(),
                    POLYMORPHIC_FIELD + fieldName + SUBTYPE + s.qualifiedName()
                            + " @XmlSubtype.name is empty");
            return null;
        }
        return value;
    }

    /**
     * Builds the terminal {@link FieldSpec} for a polymorphic field. The declared type is the sealed
     * parent (wrapped in {@code List<>} when the component is a list); {@link Coerce} is
     * {@code Nested(parentFq)}. Mirrors original Classifier.java lines 452-460.
     */
    private FieldSpec buildPolyFieldSpec(ComponentSymbol c, String name,
                                         RecordSymbol polyParent, PolyDispatch dispatch) {
        String parentFq = polyParent.qualifiedName();
        TypeRef parentType = TypeRef.of(polyParent.packageName(), polyParent.simpleName());
        TypeRef fieldType = c.isList()
                ? TypeRef.parameterized(JAVA_UTIL_PACKAGE, "List", List.of(parentType))
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
                    XML_FORMAT_AND_XML_CONVERTER_ARE_MUTUALLY_EXCLUSIVE_ON + name + "'");
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
                    "@XmlAttr requires a scalar type on '" + name + GOT + elemType.qualifiedName());
            return null;
        }

        String formatPattern = null;
        if (hasFormat) {
            if (!TypeRefClassification.isFormattableType(elemType)) {
                sp.diagnostics().error(c.nativeHandle(),
                        XML_FORMAT_ON + name + "' is only supported for LocalDate, "
                                + "LocalDateTime, Instant, or BigDecimal; got " + elemType.qualifiedName());
                return null;
            }
            String raw = c.annotations().stringValue(FQ_XML_FORMAT, PATTERN);
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
                    "@XmlText requires a scalar type on '" + name + GOT
                            + elemType.qualifiedName());
            return null;
        }

        boolean hasFormat = c.annotations().has(FQ_XML_FORMAT);
        String formatPattern = null;
        if (hasFormat) {
            if (converterInfo == null && !TypeRefClassification.isFormattableType(elemType)) {
                sp.diagnostics().error(c.nativeHandle(),
                        XML_FORMAT_ON + name + "' is only supported for LocalDate, "
                                + "LocalDateTime, Instant, or BigDecimal; got "
                                + elemType.qualifiedName());
                return null;
            }
            String raw = c.annotations().stringValue(FQ_XML_FORMAT, PATTERN);
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
                    XML_FORMAT + " / " + XML_CONVERTER + " not supported on " + XML_MAP + " field '" + name + "'");
            return null;
        }

        // Must be Map<K,V>
        TypeRef declaredType = c.type();
        if (!TypeRefClassification.isJavaUtilMap(declaredType)) {
            sp.diagnostics().error(c.nativeHandle(),
                    XML_MAP + " requires Map<K, V> type for '" + name + GOT
                            + declaredType.qualifiedName());
            return null;
        }

        // Retrieve entry, key, value from @XmlMap annotation
        String entry  = c.annotations().stringValue(FQ_XML_MAP, "entry");
        String keyPath = c.annotations().stringValue(FQ_XML_MAP, "key");
        String valPath = c.annotations().stringValue(FQ_XML_MAP, "value");

        if (entry == null || entry.isBlank() || entry.contains("/") || entry.startsWith("@")) {
            sp.diagnostics().error(c.nativeHandle(),
                    XML_MAP + ENTRY + entry + FOR + name
                            + "' must be a single element name (optional 'prefix:local')");
            return null;
        }
        if (keyPath == null || valPath == null) {
            sp.diagnostics().error(c.nativeHandle(),
                    XML_MAP + ON + name + "' is missing 'key' or 'value'");
            return null;
        }

        QNameInfo eqn = resolveQName(entry, nsMap, nsMap.get(""), c, XML_MAP, name);
        if (eqn == null) {
            return null;
        }

        // Require exactly two type arguments (Map<K, V>)
        List<TypeRef> typeArgs = declaredType.typeArguments();
        if (typeArgs.size() != 2) {
            sp.diagnostics().error(c.nativeHandle(),
                    XML_MAP + ON + name + "' requires Map<K, V> with two type arguments");
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
        TypeRef mapType = TypeRef.parameterized(JAVA_UTIL_PACKAGE, "Map", List.of(keyParam, valParam));

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
        if (!validateMapKvType(c, type, owner, kind)) return null;

        boolean isList = TypeRefClassification.isJavaUtilList(type);
        TypeRef typeRef = type.typeArguments().isEmpty() ? TypeRef.of("java.lang", "Object") : type.typeArguments().get(0);
        TypeRef elemType = isList
                ? typeRef
                : type;

        if (isList && elemType.nullable()) {
            sp.diagnostics().error(c.nativeHandle(),
                    XML_MAP + " '" + kind + OF + owner
                            + "': nullable element inside List<…> not supported");
            return null;
        }
        boolean nullable = !isList && elemType.nullable();

        Source source = resolveMapKvSource(c, syntheticName, pathStr, nsMap, owner, kind);
        if (source == null) return null;

        ScalarBundle sb = resolveMapKvBundle(c, syntheticName, elemType, source, nullable, nsMap, owner, kind);
        if (sb == null) return null;

        TypeRef fieldTypeName = isList
                ? TypeRef.parameterized(JAVA_UTIL_PACKAGE, "List", List.of(sb.elemTypeName()))
                : sb.elemTypeName();

        return new FieldSpec(
                syntheticName,
                !nullable && !isList,
                isList,
                sb.boxedTypeName(),
                fieldTypeName,
                sb.elemTypeName(),
                sb.elemFq(),
                source,
                sb.coerce());
    }

    /**
     * Rejects {@code List<List<?>>} and {@code Map<…>} value types that map entries cannot represent.
     * Returns {@code true} when no guard fired.
     */
    private boolean validateMapKvType(ComponentSymbol c, TypeRef type, String owner, String kind) {
        if (TypeRefClassification.isJavaUtilList(type)) {
            TypeRef innerElem = type.typeArguments().isEmpty()
                    ? TypeRef.of("java.lang", "Object")
                    : type.typeArguments().get(0);
            if (TypeRefClassification.isJavaUtilList(innerElem)) {
                sp.diagnostics().error(c.nativeHandle(),
                        XML_MAP + " '" + kind + OF + owner + "': List<List<?>> not supported");
                return false;
            }
        }
        if (TypeRefClassification.isJavaUtilMap(type)) {
            sp.diagnostics().error(c.nativeHandle(),
                    XML_MAP + " '" + kind + OF + owner + "': nested Map not supported");
            return false;
        }
        return true;
    }

    /**
     * Resolves a synthetic kv-field's {@link Source}: {@code @attr}, the self-step shorthand
     * ({@code ""} or {@code "."}), or a full {@code @XmlChild}-style path.
     */
    private @Nullable Source resolveMapKvSource(ComponentSymbol c, String syntheticName, String pathStr,
                                                Map<String, String> nsMap, String owner, String kind) {
        if (pathStr.startsWith("@")) {
            String rest = pathStr.substring(1);
            if (rest.isEmpty() || rest.contains("/")) {
                sp.diagnostics().error(c.nativeHandle(),
                        XML_MAP + " '" + kind + OF + owner
                                + "': '@' path must be a single attribute name");
                return null;
            }
            QNameInfo aqn = resolveQName(rest, nsMap, null, c,
                    XML_MAP + " '" + kind + "'", syntheticName);
            if (aqn == null) return null;
            return new Source.Attr(aqn.ns(), aqn.local());
        }
        if (pathStr.isEmpty() || pathStr.equals(".")) {
            // Self-step shorthand: the entry element itself supplies the value (scalar →
            // its text content, nested record → the entire entry element). The Child path
            // carries a single "." element segment so downstream emit logic can recognise
            // the self-step in a uniform way; mirrors `parseChildPath`'s "." escape hatch
            // and the KSP-side `buildSyntheticMapKvField` that this code subsumes.
            return new Source.Child(List.of(new PathSeg.Element(nsMap.get(""), ".")), false);
        }
        return parseChildPath(pathStr, syntheticName, nsMap, c);
    }

    /**
     * Resolves coerce + boxed/elem-type triple for a synthetic kv-field. Dispatches across scalar,
     * nested record, and unsupported. Propagates value nullability onto the boxed scalar so an outer
     * {@code Map<K, V?>} sees the right element type.
     */
    private @Nullable ScalarBundle resolveMapKvBundle(ComponentSymbol c, String syntheticName, TypeRef elemType,
                                                      Source source, boolean nullable,
                                                      Map<String, String> nsMap, String owner, String kind) {
        if (TypeRefClassification.isScalarOrTemporal(elemType)) {
            ScalarKind sk = TypeRefClassification.scalarKind(elemType);
            assert sk != null;
            Coerce coerce = scalarCoerce(sk, null, c, syntheticName);
            TypeRef boxedTypeName = TypeRefClassification.boxedScalar(sk);
            if (nullable) boxedTypeName = boxedTypeName.asNullable();
            String elemFq = elemType.primitive() ? elemType.simpleName() : elemType.qualifiedName();
            return new ScalarBundle(boxedTypeName, boxedTypeName, elemFq, coerce);
        }
        RecordSymbol nested = sp.lookupRecord(elemType.qualifiedName());
        if (nested == null) {
            sp.diagnostics().error(c.nativeHandle(),
                    XML_MAP + " '" + kind + OF + owner
                            + "': unsupported type '" + elemType.qualifiedName() + "'");
            return null;
        }
        if (!(source instanceof Source.Child)) {
            sp.diagnostics().error(c.nativeHandle(),
                    XML_MAP + " '" + kind + OF + owner
                            + "': nested record requires an element path, not '@attr'");
            return null;
        }
        String nestedFq = ensureNested(c, syntheticName, nested, nsMap, true);
        if (nestedFq == null) return null;
        return new ScalarBundle(elemType, elemType, nestedFq, new Coerce.Nested(nestedFq));
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

        if (!validateChildListGuards(c, name)) return null;

        Source source = resolveChildSource(c, name, nsMap);
        if (source == null) return null;

        boolean hasFormat    = c.annotations().has(FQ_XML_FORMAT);
        boolean hasConverter = c.annotations().has(FQ_XML_CONVERTER);
        if (hasFormat && hasConverter) {
            sp.diagnostics().error(c.nativeHandle(),
                    XML_FORMAT_AND_XML_CONVERTER_ARE_MUTUALLY_EXCLUSIVE_ON + name + "'");
            return null;
        }
        String formatPattern = hasFormat
                ? Objects.requireNonNullElse(c.annotations().stringValue(FQ_XML_FORMAT, PATTERN), "")
                : null;

        TypeRef elemType = c.elementType();
        ScalarBundle sb = resolveChildBundle(c, name, elemType, hasFormat, formatPattern, hasConverter, nsMap);
        if (sb == null) return null;

        TypeRef fieldType = computeChildFieldType(c.isList(), elemType.primitive(), elemType, sb.elemTypeName());
        return new FieldSpec(
                name,
                !c.nullable(),
                c.isList(),
                sb.boxedTypeName(),
                fieldType,
                sb.elemTypeName(),
                sb.elemFq(),
                source,
                sb.coerce());
    }

    /**
     * Rejects raw {@code List}, {@code List<List<T>>}, and {@code List<Optional<T>>} on a child component.
     * Returns {@code true} when no guard fired.
     */
    private boolean validateChildListGuards(ComponentSymbol c, String name) {
        if (!c.isList()) return true;
        if (c.type().typeArguments().isEmpty()) {
            sp.diagnostics().error(c.nativeHandle(),
                    "Raw List is not supported; use List<T> on '" + name + "'");
            return false;
        }
        if (TypeRefClassification.isJavaUtilList(c.elementType())) {
            sp.diagnostics().error(c.nativeHandle(),
                    "List<List<T>> is not supported on '" + name + "'");
            return false;
        }
        if (TypeRefClassification.isOptional(c.elementType())) {
            sp.diagnostics().error(c.nativeHandle(),
                    "List<Optional<T>> is not supported on '" + name + "'");
            return false;
        }
        return true;
    }

    /**
     * Resolves a child component's {@link Source}: explicit {@code @XmlChild(path=...)} when present,
     * otherwise the component name as an implicit single-segment element.
     */
    private @Nullable Source resolveChildSource(ComponentSymbol c, String name, Map<String, String> nsMap) {
        if (c.annotations().has(FQ_XML_CHILD)) {
            String pathRaw = c.annotations().stringValue(FQ_XML_CHILD, "path");
            if (pathRaw != null && !pathRaw.isEmpty()) {
                return parseChildPath(pathRaw, name, nsMap, c);
            }
        }
        QNameInfo qn = resolveQName(name, nsMap, nsMap.get(""), c, XML_CHILD, name);
        if (qn == null) return null;
        return new Source.Child(List.of(new PathSeg.Element(qn.ns(), qn.local())), false);
    }

    /**
     * Resolves a child component's coerce + boxed/elem-type triple. Dispatches across the four
     * cases: explicit converter, scalar/temporal, nested record, unsupported. Mirrors original
     * Classifier.java lines 280-340.
     */
    private @Nullable ScalarBundle resolveChildBundle(ComponentSymbol c, String name, TypeRef elemType,
                                                      boolean hasFormat, @Nullable String formatPattern,
                                                      boolean hasConverter, Map<String, String> nsMap) {
        if (hasConverter) {
            ConverterResult cr = resolveConverter(c, name, elemType);
            if (cr.error()) return null;
            return resolveScalarBundle(c, name, elemType, cr.info(), null);
        }
        if (TypeRefClassification.isScalarOrTemporal(elemType)) {
            if (hasFormat && !TypeRefClassification.isFormattableType(elemType)) {
                reportUnsupportedFormatType(c, name, elemType);
                return null;
            }
            return resolveScalarBundle(c, name, elemType, null, formatPattern);
        }
        if (c.asNestedRecord() != null) {
            if (hasFormat) {
                sp.diagnostics().error(c.nativeHandle(),
                        XML_FORMAT_ON + name + "' has no effect on a nested record");
                return null;
            }
            RecordSymbol nested = Objects.requireNonNull(c.asNestedRecord());
            boolean terminating = c.isList() || c.nullable();
            String nestedFqn = ensureNested(c, name, nested, nsMap, terminating);
            if (nestedFqn == null) return null;
            return new ScalarBundle(elemType, elemType, nestedFqn, new Coerce.Nested(nestedFqn));
        }
        if (hasFormat) {
            reportUnsupportedFormatType(c, name, elemType);
        } else {
            sp.diagnostics().error(c.nativeHandle(),
                    "Unsupported field type '" + elemType.qualifiedName()
                            + "' for component '" + name + "'");
        }
        return null;
    }

    private void reportUnsupportedFormatType(ComponentSymbol c, String name, TypeRef elemType) {
        sp.diagnostics().error(c.nativeHandle(),
                XML_FORMAT_ON + name + "' is only supported for LocalDate, "
                        + "LocalDateTime, Instant, or BigDecimal; got " + elemType.qualifiedName());
    }

    /** Wraps {@code elemTypeName} in {@code List<>} for list components, otherwise returns the
     *  primitive {@code elemType} for primitives or the boxed {@code elemTypeName} for everything else. */
    private static TypeRef computeChildFieldType(boolean isList, boolean primitive,
                                                 TypeRef elemType, TypeRef elemTypeName) {
        if (isList) return TypeRef.parameterized(JAVA_UTIL_PACKAGE, "List", List.of(elemTypeName));
        if (primitive) return elemType;
        return elemTypeName;
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
                    XML_CHILD + " path empty for '" + fieldName + "'");
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
            reportPathError(owner, path, fieldName, "invalid syntax (absolute paths are not supported)");
            return null;
        }

        List<Step> steps = compilePathSteps(path, fieldName, owner, nsMap);
        if (steps == null) return null;

        if (descendant && steps.get(0) instanceof Step.AttrLeaf) {
            reportPathError(owner, path, fieldName, "descendant axis head must be an element");
            return null;
        }

        List<PathSeg> segs = convertSteps(steps, path, fieldName, owner, descendant);
        if (segs == null) return null;
        return new Source.Child(segs, descendant);
    }

    /** Reports a {@code @XmlChild path '<path>' for '<field>': <reason>} diagnostic. */
    private void reportPathError(ComponentSymbol owner, String path, String fieldName, String reason) {
        sp.diagnostics().error(owner.nativeHandle(),
                XML_CHILD + PATH + path + FOR + fieldName + "': " + reason);
    }

    /**
     * Parses {@code path} via {@link PathParser}, strips the auto-prepended descendant axis, and
     * returns the remaining steps. Returns {@code null} (with a diagnostic) on parse error or when
     * the result is empty after axis stripping.
     */
    private @Nullable List<Step> compilePathSteps(String path, String fieldName, ComponentSymbol owner,
                                                  Map<String, String> nsMap) {
        String defaultNs = nsMap.get("");
        PathParser parser = new PathParser(nsMap::get, defaultNs);
        CompiledPath compiled;
        try {
            compiled = parser.parse(path);
        } catch (PathParseException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            reportPathError(owner, path, fieldName, rewriteParseError(msg));
            return null;
        }

        List<Step> rawSteps = compiled.getSteps();
        // PathParser auto-prepends a descendant axis to every relative path; strip it and rely
        // on our own boolean.
        List<Step> steps = !rawSteps.isEmpty() && rawSteps.get(0) instanceof Step.Descendant
                ? rawSteps.subList(1, rawSteps.size())
                : rawSteps;

        if (steps.isEmpty()) {
            reportPathError(owner, path, fieldName, "empty after axis");
            return null;
        }
        return steps;
    }

    /**
     * Translates parser {@link Step}s into emitter {@link PathSeg}s, rejecting non-head descendants,
     * non-tail attribute leaves, head-attribute leaves, wildcards, and predicate violations.
     */
    private @Nullable List<PathSeg> convertSteps(List<Step> steps, String path, String fieldName,
                                                 ComponentSymbol owner, boolean descendant) {
        List<PathSeg> segs = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            boolean last = (i == steps.size() - 1);

            if (s instanceof Step.Descendant) {
                reportPathError(owner, path, fieldName, "'//' is only allowed at the head of the path");
                return null;
            } else if (s instanceof Step.AttrLeaf attr) {
                if (!last) {
                    reportPathError(owner, path, fieldName, "'@' segment must be last");
                    return null;
                }
                if (i == 0) {
                    reportPathError(owner, path, fieldName, "use @XmlAttr for record-level attributes");
                    return null;
                }
                segs.add(new PathSeg.AttrLeaf(attr.getName().getNs(), attr.getName().getLocal()));
            } else {
                PathSeg.Element elem = convertNamedStep((Step.Named) s, path, fieldName, owner,
                        descendant && i == 0);
                if (elem == null) return null;
                segs.add(elem);
            }
        }
        return segs;
    }

    /**
     * Converts a single {@link Step.Named} into a {@link PathSeg.Element}, rejecting wildcard
     * local-names, wildcard namespaces, invalid predicates, and more than one positional predicate.
     */
    private PathSeg.@Nullable Element convertNamedStep(Step.Named named, String path, String fieldName,
                                                       ComponentSymbol owner, boolean onDescHead) {
        if ("*".equals(named.getName().getLocal())) {
            reportPathError(owner, path, fieldName, "wildcard local-name '*' is not supported");
            return null;
        }
        if (PathParser.WILDCARD.equals(named.getName().getNs())) {
            reportPathError(owner, path, fieldName, "wildcard namespace '{*}' is not supported");
            return null;
        }
        List<Predicate> brackets = named.getBrackets();
        for (Predicate b : brackets) {
            if (childPredicateInvalid(b, path, fieldName, owner, onDescHead)) {
                return null;
            }
        }
        long indexBracketCount = brackets.stream().filter(CoreClassifier::containsIndex).count();
        if (indexBracketCount > 1) {
            reportPathError(owner, path, fieldName,
                    "only one positional predicate is allowed per segment (found multiple in '"
                            + named.getName().getLocal()
                            + "'). Express the second positional via @XmlRecord, or restructure your XML.");
            return null;
        }
        return new PathSeg.Element(named.getName().getNs(), named.getName().getLocal(), brackets);
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
                    XML_CONVERTER + ON + name + "' is missing a 'cls' value");
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
                    XML_CONVERTER + " cls on '" + fieldName + "' could not be resolved: " + converterFq);
            return null;
        }
        if (!converterType.hasPublicNoArgConstructor()) {
            sp.diagnostics().error(c.nativeHandle(),
                    XML_CONVERTER + ON + fieldName + "': " + converterFq
                            + " must have a public no-arg constructor");
            return null;
        }
        TypeRef produces = converterType.typeArgumentOf(FQ_CONVERTER, 0);
        if (produces == null) {
            sp.diagnostics().error(c.nativeHandle(),
                    XML_CONVERTER + ON + fieldName + "': " + converterFq
                            + " does not implement " + FQ_CONVERTER + "<T>");
            return null;
        }
        if (!converterArgAssignable(produces, target)) {
            sp.diagnostics().error(c.nativeHandle(),
                    XML_CONVERTER + ON + fieldName + "': converter produces "
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
                            XML_FORMAT_ON + fieldName + "' is only supported for LocalDate, "
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
                        XML_CHILD + PATH + path + FOR + fieldName
                                + "': positional predicate [" + idx.getN() + "] is not supported on the descendant-axis segment ('//<name>[N]'). "
                                + "Move the positional filter to a direct-axis segment (e.g. '//parent/item[" + idx.getN() + "]') or to @XmlRecord.");
                return true;
            }
        } else if (p instanceof Predicate.AttrEq ae) {
            if (PathParser.WILDCARD.equals(ae.getName().getNs())) {
                sp.diagnostics().error(where.nativeHandle(),
                        XML_CHILD + PATH + path + FOR + fieldName
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
                        XML_CHILD + PATH + path + FOR + fieldName
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
        Set<QKey> directKeys = buildDirectTrie(fields, owner, ownerFq);
        if (directKeys == null) return false;
        Set<QKey> descendantHeads = collectDescendantHeads(fields, directKeys, owner, ownerFq);
        if (descendantHeads == null) return false;
        Set<QKey> seenMapEntries = validateMapEntries(fields, directKeys, descendantHeads, owner, ownerFq);
        if (seenMapEntries == null) return false;
        return validatePolyChildren(fields, directKeys, seenMapEntries, descendantHeads, owner, ownerFq);
    }

    /**
     * Phase 1: build the direct (non-descendant) trie and collect head {@link QKey}s.
     * Returns the direct-key set, or {@code null} if a bad path tail or trie conflict was reported.
     */
    private @Nullable Set<QKey> buildDirectTrie(List<FieldSpec> fields, RecordSymbol owner, String ownerFq) {
        TrieNode root = new TrieNode();
        Set<QKey> directKeys = new LinkedHashSet<>();
        for (FieldSpec f : fields) {
            if (!(f.source() instanceof Source.Child sc)) continue;
            if (sc.descendant()) continue;
            if (!insertIntoTrie(root, sc.segments(), f, owner, ownerFq)) return null;
            PathSeg first = sc.segments().get(0);
            if (first instanceof PathSeg.Element e) {
                directKeys.add(new QKey(e.ns(), e.name()));
            }
        }
        if (trieIsInvalid(root, owner, ownerFq)) return null;
        return directKeys;
    }

    /**
     * Phase 2: collect descendant-axis heads and reject any head that also appears as a direct head.
     * Returns the descendant-head set, or {@code null} on collision.
     */
    private @Nullable Set<QKey> collectDescendantHeads(List<FieldSpec> fields, Set<QKey> directKeys,
                                                       RecordSymbol owner, String ownerFq) {
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
                        ownerFq + ": " + XML_CHILD + "('" + k.local()
                                + "') and " + XML_CHILD + "('//" + k.local()
                                + "') target the same head element '" + k.local() + "'; pick one");
                return null;
            }
        }
        return descendantHeads;
    }

    /**
     * Phase 3: validate {@code @XmlMap} entries against direct heads, descendant heads, and prior
     * map entries. Returns the seen-map-entry set, or {@code null} on the first conflict.
     */
    private @Nullable Set<QKey> validateMapEntries(List<FieldSpec> fields, Set<QKey> directKeys,
                                                   Set<QKey> descendantHeads,
                                                   RecordSymbol owner, String ownerFq) {
        Set<QKey> seenMapEntries = new LinkedHashSet<>();
        for (FieldSpec f : fields) {
            if (!(f.source() instanceof Source.MapEntry me)) continue;
            QKey k = new QKey(me.entryNs(), me.entryLocal());
            if (directKeys.contains(k)) {
                sp.diagnostics().error(owner.nativeHandle(),
                        ownerFq + ": " + XML_MAP + ENTRY + me.entryLocal()
                                + ON_FIELD + f.name() + CLASHES_WITH_ANOTHER + XML_CHILD + "'s first segment");
                return null;
            }
            if (descendantHeads.contains(k)) {
                sp.diagnostics().error(owner.nativeHandle(),
                        ownerFq + ": " + XML_MAP + ENTRY + me.entryLocal()
                                + ON_FIELD + f.name() + CLASHES_WITH_A_DESCENDANT_XML_CHILD
                                + k.local() + HEAD);
                return null;
            }
            if (!seenMapEntries.add(k)) {
                sp.diagnostics().error(owner.nativeHandle(),
                        ownerFq + ": duplicate " + XML_MAP + ENTRY + me.entryLocal()
                                + ON_FIELD + f.name() + "'");
                return null;
            }
        }
        return seenMapEntries;
    }

    /**
     * Phase 4: validate polymorphic tag/wrap names against direct heads, map entries, prior poly keys,
     * and descendant heads. Also rejects more than one tag-mode polymorphic field at the same scope.
     */
    private boolean validatePolyChildren(List<FieldSpec> fields, Set<QKey> directKeys,
                                         Set<QKey> seenMapEntries, Set<QKey> descendantHeads,
                                         RecordSymbol owner, String ownerFq) {
        Set<QKey> seenPolyKeys = new LinkedHashSet<>();
        boolean sawTagMode = false;
        for (FieldSpec f : fields) {
            if (!(f.source() instanceof Source.PolyChild pc)) continue;
            PolyDispatch d = pc.dispatch();
            if (d instanceof PolyDispatch.Tag tag) {
                if (sawTagMode) {
                    sp.diagnostics().error(owner.nativeHandle(),
                            ownerFq
                                    + ": more than one tag-mode polymorphic " + XML_CHILD + " field at the same scope (field '"
                                    + f.name() + "')");
                    return false;
                }
                sawTagMode = true;
                for (TagVariant v : tag.variants()) {
                    QKey k = new QKey(v.ns(), v.local());
                    if (polyKeysClash(k, f, "subtype",
                            directKeys, seenMapEntries, seenPolyKeys, descendantHeads, owner, ownerFq)) {
                        return false;
                    }
                }
            } else {
                PolyDispatch.Attr ad = (PolyDispatch.Attr) d;
                QKey k = new QKey(ad.wrapNs(), ad.wrapLocal());
                if (polyKeysClash(k, f, "wrap",
                        directKeys, seenMapEntries, seenPolyKeys, descendantHeads, owner, ownerFq)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Reports a clash for one polymorphic key against direct heads, map entries, prior poly keys, or
     * descendant heads. {@code polyKind} is the diagnostic label ({@code "subtype"} or {@code "wrap"}).
     * Returns {@code true} when no clash was found and {@code k} was added to {@code seenPolyKeys}.
     */
    private boolean polyKeysClash(QKey k, FieldSpec f, String polyKind,
                                  Set<QKey> directKeys, Set<QKey> seenMapEntries,
                                  Set<QKey> seenPolyKeys, Set<QKey> descendantHeads,
                                  RecordSymbol owner, String ownerFq) {
        if (directKeys.contains(k) || seenMapEntries.contains(k) || !seenPolyKeys.add(k)) {
            sp.diagnostics().error(owner.nativeHandle(),
                    ownerFq + ": polymorphic " + polyKind + " tag '" + k.local()
                            + ON_FIELD + f.name()
                            + CLASHES_WITH_ANOTHER + XML_CHILD + " / " + XML_MAP + " / subtype");
            return true;
        }
        if (descendantHeads.contains(k)) {
            sp.diagnostics().error(owner.nativeHandle(),
                    ownerFq + ": polymorphic " + polyKind + " tag '" + k.local()
                            + ON_FIELD + f.name() + CLASHES_WITH_A_DESCENDANT_XML_CHILD
                            + k.local() + HEAD);
            return true;
        }
        return false;
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
