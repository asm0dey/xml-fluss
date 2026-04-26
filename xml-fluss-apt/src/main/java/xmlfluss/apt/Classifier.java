package xmlfluss.apt;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import xmlfluss.path.CompiledPath;
import xmlfluss.path.PathParseException;
import xmlfluss.path.PathParser;
import xmlfluss.path.Predicate;
import xmlfluss.path.Step;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.*;

/**
 * Walks a record element and produces a {@link Model.RecordSpec}. Validation errors are
 * reported via {@link Diagnostic.Kind#ERROR} and surfaced as a {@link ClassifierException}
 * so the caller can skip the record.
 */
final class Classifier {

    static final String PKG = "xmlfluss";

    static final String FQ_XML_RECORD = PKG + ".XmlRecord";
    static final String FQ_XML_ATTR = PKG + ".XmlAttr";
    static final String FQ_XML_CHILD = PKG + ".XmlChild";
    static final String FQ_XML_TEXT = PKG + ".XmlText";
    static final String FQ_XML_NS = PKG + ".XmlNs";
    static final String FQ_XML_NAMESPACES = PKG + ".XmlNamespaces";
    static final String FQ_XML_FORMAT = PKG + ".XmlFormat";
    static final String FQ_XML_CONVERTER = PKG + ".XmlConverter";
    static final String FQ_XML_MAP = PKG + ".XmlMap";
    static final String FQ_XML_POLYMORPHIC = PKG + ".XmlPolymorphic";
    static final String FQ_XML_SUBTYPE = PKG + ".XmlSubtype";
    static final String FQ_NON_NULL = "org.jspecify.annotations.NonNull";
    static final String FQ_NULLABLE = "org.jspecify.annotations.Nullable";
    static final String FQ_NULL_MARKED = "org.jspecify.annotations.NullMarked";
    static final String FQ_NULL_UNMARKED = "org.jspecify.annotations.NullUnmarked";
    static final String FQ_CONVERTER = PKG + ".Converter";

    static final class ClassifierException extends RuntimeException {
        ClassifierException(String message) { super(message); }
    }

    private final ProcessingEnvironment env;
    private final Types types;
    private final Elements elements;
    private final Model.NestedRegistry registry;

    Classifier(ProcessingEnvironment env, Model.NestedRegistry registry) {
        this.env = env;
        this.types = env.getTypeUtils();
        this.elements = env.getElementUtils();
        this.registry = registry;
    }

    Model.RecordSpec classifyTopLevel(TypeElement element) {
        if (element.getKind() != ElementKind.RECORD) {
            error(element, "@XmlRecord requires a record type, got " + element.getKind());
            throw new ClassifierException("not a record: " + element);
        }
        Map<String, String> nsMap = collectOwnNs(element);
        String path = readRecordPath(element);
        try {
            xmlfluss.runtime.Paths.INSTANCE.compile(path, nsMap);
        } catch (RuntimeException ex) {
            error(element, "@XmlRecord path '" + path + "' is invalid: " + ex.getMessage());
            throw new ClassifierException("bad record path");
        }
        return classify(element, path, nsMap);
    }

    private Model.RecordSpec classify(TypeElement element, String recordPath, Map<String, String> nsMap) {
        String fq = element.getQualifiedName().toString();
        String pkg = elements.getPackageOf(element).getQualifiedName().toString();
        String simple = element.getSimpleName().toString();

        List<? extends RecordComponentElement> components = element.getRecordComponents();
        List<Model.FieldSpec> fields = new ArrayList<>(components.size());

        ExecutableElement canonicalCtor = findCanonicalConstructor(element, components);

        int textCount = 0;
        Set<String> seenAttrKeys = new HashSet<>();
        Map<String, String> firstAttrBindingByKey = new HashMap<>();

        for (int i = 0; i < components.size(); i++) {
            RecordComponentElement rc = components.get(i);
            VariableElement ctorParam = canonicalCtor != null ? canonicalCtor.getParameters().get(i) : null;
            Model.FieldSpec spec = classifyComponent(element, rc, ctorParam, nsMap);
            fields.add(spec);
            if (spec.source() instanceof Model.Source.Text) {
                textCount++;
                if (textCount > 1) {
                    error(rc, "@XmlText may appear at most once per record");
                    throw new ClassifierException("multiple @XmlText in " + fq);
                }
            } else if (spec.source() instanceof Model.Source.Attr attr) {
                String key = qnameKey(attr.ns(), attr.name());
                if (!seenAttrKeys.add(key)) {
                    String first = firstAttrBindingByKey.get(key);
                    error(rc, "duplicate @XmlAttr name '" + attr.name() + "': '"
                            + first + "' and '" + spec.name() + "' both bind it");
                    throw new ClassifierException("duplicate @XmlAttr in " + fq);
                }
                firstAttrBindingByKey.put(key, spec.name());
            }
        }

        validateChildPaths(element, fq, fields);

        return new Model.RecordSpec(element, pkg, simple, recordPath, nsMap, fields);
    }

    private ExecutableElement findCanonicalConstructor(TypeElement record,
                                                       List<? extends RecordComponentElement> components) {
        for (var enclosed : record.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.CONSTRUCTOR) continue;
            ExecutableElement c = (ExecutableElement) enclosed;
            if (c.getParameters().size() != components.size()) continue;
            boolean match = true;
            for (int i = 0; i < components.size(); i++) {
                if (!types.isSameType(c.getParameters().get(i).asType(), components.get(i).asType())) {
                    match = false;
                    break;
                }
            }
            if (match) return c;
        }
        return null;
    }

    private Model.FieldSpec classifyComponent(TypeElement owner,
                                              RecordComponentElement rc,
                                              VariableElement ctorParam,
                                              Map<String, String> nsMap) {
        String name = rc.getSimpleName().toString();

        AnnotationMirror attrAnn = findAnnotation(rc, ctorParam, FQ_XML_ATTR);
        AnnotationMirror childAnn = findAnnotation(rc, ctorParam, FQ_XML_CHILD);
        AnnotationMirror textAnn = findAnnotation(rc, ctorParam, FQ_XML_TEXT);
        AnnotationMirror mapAnn = findAnnotation(rc, ctorParam, FQ_XML_MAP);
        AnnotationMirror formatAnn = findAnnotation(rc, ctorParam, FQ_XML_FORMAT);
        AnnotationMirror converterAnn = findAnnotation(rc, ctorParam, FQ_XML_CONVERTER);

        int bindings = (attrAnn != null ? 1 : 0) + (childAnn != null ? 1 : 0)
                + (textAnn != null ? 1 : 0) + (mapAnn != null ? 1 : 0);
        if (bindings > 1) {
            error(rc, "@XmlAttr / @XmlChild / @XmlText / @XmlMap are mutually exclusive on '" + name + "'");
            throw new ClassifierException("multiple bindings on " + name);
        }

        if (formatAnn != null && converterAnn != null) {
            error(rc, "@XmlFormat and @XmlConverter are mutually exclusive on '" + name + "'");
            throw new ClassifierException("format+converter on " + name);
        }

        TypeMirror declaredType = rc.asType();
        boolean isList = isJavaUtilList(declaredType);
        boolean isMap = isJavaUtilMap(declaredType);
        TypeMirror elemType = isList ? listElement(declaredType, rc) : declaredType;

        if (isList && isJavaUtilList(elemType)) {
            error(rc, "List<List<T>> is not supported on '" + name + "'");
            throw new ClassifierException("nested list on " + name);
        }
        if (isList && isOptional(elemType)) {
            error(rc, "List<Optional<T>> is not supported on '" + name + "'");
            throw new ClassifierException("optional inside list on " + name);
        }

        // @XmlMap dispatch happens before normal field-shape checks (the field type is Map<K,V>).
        if (mapAnn != null) {
            if (formatAnn != null || converterAnn != null) {
                error(rc, "@XmlFormat / @XmlConverter not supported on @XmlMap field '" + name + "'");
                throw new ClassifierException("format/converter on map " + name);
            }
            if (!isMap) {
                error(rc, "@XmlMap requires Map<K, V> type for '" + name + "', got " + declaredType);
                throw new ClassifierException("xmlmap non-map " + name);
            }
            return classifyMapField(rc, ctorParam, name, declaredType, mapAnn, nsMap);
        }
        if (isMap) {
            error(rc, "Field '" + name + "' is Map<K, V> but lacks @XmlMap");
            throw new ClassifierException("map without xmlmap " + name);
        }

        boolean primitive = elemType.getKind().isPrimitive();
        boolean nullableField = computeNullable(rc, ctorParam, declaredType, owner, primitive, isList);
        boolean required = !nullableField;

        String formatPattern = null;
        if (formatAnn != null) {
            formatPattern = stringValue(formatAnn, "pattern");
            if (formatPattern == null) formatPattern = "";
        }

        TypeMirror converterTm = null;
        if (converterAnn != null) {
            AnnotationValue clsVal = annotationValue(converterAnn, "cls");
            if (clsVal == null || !(clsVal.getValue() instanceof TypeMirror tm)) {
                error(rc, "@XmlConverter on '" + name + "' is missing a 'cls' value");
                throw new ClassifierException("converter missing cls on " + name);
            }
            converterTm = tm;
        }

        // Polymorphic dispatch: declared type (or list-element type) is a sealed parent
        // annotated with @XmlPolymorphic.
        TypeElement polyParent = polymorphicParent(elemType);
        if (polyParent != null && childAnn != null && converterTm == null) {
            return classifyPolymorphic(rc, name, polyParent, isList,
                    required, formatAnn, childAnn, nsMap);
        }
        if (polyParent != null && converterTm != null) {
            error(rc, "@XmlConverter not supported on polymorphic field '" + name + "'");
            throw new ClassifierException("converter on poly " + name);
        }

        Model.Source source;
        if (attrAnn != null) {
            String aName = nonEmpty(stringValue(attrAnn, "name"), name);
            if (isList) {
                error(rc, "@XmlAttr does not support List on '" + name + "'");
                throw new ClassifierException("attr is list on " + name);
            }
            if (converterTm == null && !isScalarOrTemporal(elemType)) {
                error(rc, "@XmlAttr requires a scalar type on '" + name + "', got " + elemType);
                throw new ClassifierException("attr non-scalar on " + name);
            }
            QNameInfo qn = resolveQName(aName, nsMap, /*defaultNs=*/null, rc, "@XmlAttr", name);
            source = new Model.Source.Attr(qn.ns, qn.local);
        } else if (textAnn != null) {
            if (isList) {
                error(rc, "@XmlText is not supported on List<...> '" + name + "'");
                throw new ClassifierException("text on list on " + name);
            }
            boolean preserve = false;
            AnnotationValue v = annotationValue(textAnn, "preserveWhitespace");
            if (v != null && v.getValue() instanceof Boolean b) preserve = b;
            source = new Model.Source.Text(preserve);
        } else if (childAnn != null) {
            String pathRaw = stringValue(childAnn, "path");
            String useName = (pathRaw == null || pathRaw.isEmpty()) ? name : pathRaw;
            source = parseChildPath(useName, name, nsMap, rc);
        } else {
            QNameInfo qn = resolveQName(name, nsMap, nsMap.get(""), rc, "@XmlChild", name);
            source = new Model.Source.Child(List.of(new Model.PathSeg.Element(qn.ns, qn.local)), false);
        }

        if (formatAnn != null) {
            if (!isFormattableType(elemType)) {
                error(rc, "@XmlFormat on '" + name + "' is only supported for LocalDate, "
                        + "LocalDateTime, Instant, or BigDecimal; got " + elemType);
                throw new ClassifierException("format on unsupported type " + name);
            }
        }

        Model.Coerce coerce;
        TypeName elemTypeName;
        TypeName boxedTypeName;
        TypeName fieldTypeName;
        String elemFq;

        if (converterTm != null) {
            ConverterInfo info = validateConverter(rc, name, converterTm, elemType);
            coerce = new Model.Coerce.Custom(info.className, info.fqn);
            if (isScalarOrTemporal(elemType)) {
                Model.ScalarKind kind = scalarKind(elemType);
                boxedTypeName = boxedScalarTypeName(kind);
                elemTypeName = primitive ? primitiveTypeName(elemType) : boxedTypeName;
                elemFq = elemTypeFq(elemType);
            } else if (elemType.getKind() == TypeKind.DECLARED) {
                ClassName cn = ClassName.get((TypeElement) ((DeclaredType) elemType).asElement());
                elemTypeName = cn;
                boxedTypeName = cn;
                elemFq = ((TypeElement) ((DeclaredType) elemType).asElement()).getQualifiedName().toString();
            } else {
                error(rc, "@XmlConverter on '" + name + "' targets unsupported type " + elemType);
                throw new ClassifierException("converter on weird type " + name);
            }
        } else if (isScalarOrTemporal(elemType)) {
            Model.ScalarKind kind = scalarKind(elemType);
            coerce = scalarCoerce(kind, formatPattern, rc, name);
            boxedTypeName = boxedScalarTypeName(kind);
            elemTypeName = primitive ? primitiveTypeName(elemType) : boxedTypeName;
            elemFq = elemTypeFq(elemType);
        } else if (isRecordType(elemType)) {
            if (!(source instanceof Model.Source.Child)) {
                error(rc, "Nested record component '" + name + "' must use @XmlChild");
                throw new ClassifierException("nested non-child on " + name);
            }
            if (formatAnn != null) {
                error(rc, "@XmlFormat on '" + name + "' has no effect on a nested record");
                throw new ClassifierException("format on nested " + name);
            }
            String nestedFq = ensureNested(rc, name, (TypeElement) ((DeclaredType) elemType).asElement(),
                    nsMap, /*terminating=*/isList || nullableField);
            coerce = new Model.Coerce.Nested(nestedFq);
            ClassName nestedCn = ClassName.get((TypeElement) ((DeclaredType) elemType).asElement());
            elemTypeName = nestedCn;
            boxedTypeName = nestedCn;
            elemFq = nestedFq;
        } else {
            error(rc, "Unsupported field type '" + elemType + "' for component '" + name + "'");
            throw new ClassifierException("unsupported type on " + name);
        }

        if (isList) {
            fieldTypeName = ParameterizedTypeName.get(ClassName.get("java.util", "List"), elemTypeName);
        } else if (primitive) {
            fieldTypeName = primitiveTypeName(elemType);
        } else {
            fieldTypeName = elemTypeName;
        }

        return new Model.FieldSpec(
                name, required, isList, boxedTypeName, fieldTypeName, elemTypeName, elemFq,
                source, coerce);
    }

    /** Build polymorphic FieldSpec from sealed-parent + subtypes. */
    private Model.FieldSpec classifyPolymorphic(RecordComponentElement rc, String name, TypeElement polyParent,
                                                boolean isList,
                                                boolean required,
                                                AnnotationMirror formatAnn,
                                                AnnotationMirror childAnn,
                                                Map<String, String> nsMap) {
        if (formatAnn != null) {
            error(rc, "@XmlFormat not supported on polymorphic field '" + name + "'");
            throw new ClassifierException("format on poly " + name);
        }
        AnnotationMirror polyAnn = findAnnotation(polyParent, FQ_XML_POLYMORPHIC);
        String discriminator = polyAnn == null ? "" : nonEmpty(stringValue(polyAnn, "discriminator"), "");
        List<TypeElement> subtypes = sealedSubclasses(polyParent);
        if (subtypes.isEmpty()) {
            error(rc, "polymorphic field '" + name + "': sealed type "
                    + polyParent.getQualifiedName() + " has no permitted subclasses");
            throw new ClassifierException("no subtypes " + name);
        }
        for (TypeElement s : subtypes) {
            if (s.getKind() != ElementKind.RECORD) {
                error(rc, "polymorphic field '" + name + "': subtype " + s.getQualifiedName()
                        + " must be a record");
                throw new ClassifierException("subtype not record " + name);
            }
            if (findAnnotation(s, FQ_XML_SUBTYPE) == null) {
                error(rc, "polymorphic field '" + name + "': subtype " + s.getQualifiedName()
                        + " is missing @XmlSubtype");
                throw new ClassifierException("subtype missing annotation " + name);
            }
        }
        for (TypeElement s : subtypes) {
            ensureNested(rc, name, s, nsMap, /*terminating=*/true);
        }

        String pathRaw = stringValue(childAnn, "path");
        String rawPath = pathRaw == null ? "" : pathRaw;

        Model.PolyDispatch dispatch;
        if (discriminator.isEmpty()) {
            if (!rawPath.isEmpty()) {
                error(rc, "polymorphic field '" + name
                        + "': tag-mode @XmlChild path must be empty (got '" + rawPath + "')");
                throw new ClassifierException("tag-mode with path " + name);
            }
            List<Model.TagVariant> variants = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (TypeElement s : subtypes) {
                AnnotationMirror sa = findAnnotation(s, FQ_XML_SUBTYPE);
                String subName = stringValue(sa, "name");
                if (subName == null || subName.isEmpty()) {
                    error(rc, "polymorphic field '" + name + "': subtype " + s.getQualifiedName()
                            + " @XmlSubtype.name is empty");
                    throw new ClassifierException("subtype empty name " + name);
                }
                QNameInfo qn = resolveQName(subName, nsMap, nsMap.get(""), rc, "@XmlSubtype", name);
                String key = qnameKey(qn.ns, qn.local);
                if (!seen.add(key)) {
                    error(rc, "polymorphic field '" + name + "': duplicate @XmlSubtype tag '" + subName + "'");
                    throw new ClassifierException("dup subtype tag " + name);
                }
                variants.add(new Model.TagVariant(qn.ns, qn.local, s.getQualifiedName().toString()));
            }
            dispatch = new Model.PolyDispatch.Tag(variants);
        } else {
            if (!discriminator.startsWith("@")) {
                error(rc, "polymorphic field '" + name
                        + "': @XmlPolymorphic.discriminator must start with '@' (got '" + discriminator + "')");
                throw new ClassifierException("bad discriminator " + name);
            }
            String attrRaw = discriminator.substring(1);
            if (attrRaw.isEmpty() || attrRaw.contains("/")) {
                error(rc, "polymorphic field '" + name + "': bad discriminator '" + discriminator + "'");
                throw new ClassifierException("bad discriminator " + name);
            }
            QNameInfo aqn = resolveQName(attrRaw, nsMap, /*defaultNs=*/null, rc,
                    "@XmlPolymorphic discriminator", name);
            if (rawPath.isEmpty()) {
                error(rc, "polymorphic field '" + name
                        + "': attr-mode @XmlChild requires the wrapping element path");
                throw new ClassifierException("attr-mode no path " + name);
            }
            if (rawPath.startsWith("//") || rawPath.contains("/") || rawPath.startsWith("@")) {
                error(rc, "polymorphic field '" + name
                        + "': attr-mode @XmlChild path must be a single direct-child element (got '"
                        + rawPath + "')");
                throw new ClassifierException("attr-mode bad path " + name);
            }
            QNameInfo wqn = resolveQName(rawPath, nsMap, nsMap.get(""), rc, "@XmlChild", name);
            List<Model.AttrVariant> variants = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (TypeElement s : subtypes) {
                AnnotationMirror sa = findAnnotation(s, FQ_XML_SUBTYPE);
                String value = stringValue(sa, "name");
                if (value == null || value.isEmpty()) {
                    error(rc, "polymorphic field '" + name + "': subtype " + s.getQualifiedName()
                            + " @XmlSubtype.name is empty");
                    throw new ClassifierException("subtype empty name " + name);
                }
                if (!seen.add(value)) {
                    error(rc, "polymorphic field '" + name
                            + "': duplicate @XmlSubtype attr value '" + value + "'");
                    throw new ClassifierException("dup attr value " + name);
                }
                variants.add(new Model.AttrVariant(value, s.getQualifiedName().toString()));
            }
            dispatch = new Model.PolyDispatch.Attr(wqn.ns, wqn.local, aqn.ns, aqn.local, variants);
        }

        ClassName parentCn = ClassName.get(polyParent);
        TypeName fieldTypeName = isList
                ? ParameterizedTypeName.get(ClassName.get("java.util", "List"), parentCn)
                : parentCn;
        String elemFq = polyParent.getQualifiedName().toString();
        return new Model.FieldSpec(
                name, required, isList, parentCn, fieldTypeName, parentCn, elemFq,
                new Model.Source.PolyChild(dispatch),
                new Model.Coerce.Nested(elemFq));
    }

    /** Build a Map field spec, including synthetic key/value sub-specs. */
    private Model.FieldSpec classifyMapField(RecordComponentElement rc, VariableElement ctorParam,
                                             String name, TypeMirror declaredType,
                                             AnnotationMirror mapAnn, Map<String, String> nsMap) {
        String entry = stringValue(mapAnn, "entry");
        String keyPath = stringValue(mapAnn, "key");
        String valPath = stringValue(mapAnn, "value");
        if (entry == null || entry.isBlank() || entry.contains("/") || entry.startsWith("@")) {
            error(rc, "@XmlMap entry '" + entry + "' for '" + name
                    + "' must be a single element name (optional 'prefix:local')");
            throw new ClassifierException("bad map entry " + name);
        }
        if (keyPath == null || valPath == null) {
            error(rc, "@XmlMap on '" + name + "' is missing 'key' or 'value'");
            throw new ClassifierException("map missing key/value " + name);
        }
        QNameInfo eqn = resolveQName(entry, nsMap, nsMap.get(""), rc, "@XmlMap", name);

        DeclaredType mapDt = (DeclaredType) declaredType;
        if (mapDt.getTypeArguments().size() != 2) {
            error(rc, "@XmlMap on '" + name + "' requires Map<K, V> with two type arguments");
            throw new ClassifierException("map raw " + name);
        }
        TypeMirror keyTm = mapDt.getTypeArguments().get(0);
        TypeMirror valTm = mapDt.getTypeArguments().get(1);

        Model.FieldSpec keyField = buildSyntheticMapKvField(rc, "mk", keyTm, keyPath, nsMap, name, "key");
        Model.FieldSpec valField = buildSyntheticMapKvField(rc, "mv", valTm, valPath, nsMap, name, "value");

        boolean nullableField = computeNullable(rc, ctorParam, declaredType,
                /*owner=*/null, /*primitive=*/false, /*isList=*/false);

        TypeName keyTypeName = keyField.fieldType();
        TypeName valTypeName = valField.fieldType();
        TypeName mapType = ParameterizedTypeName.get(ClassName.get("java.util", "Map"),
                keyTypeName.box(), valTypeName.box());

        return new Model.FieldSpec(
                name, !nullableField, false, mapType, mapType, valTypeName,
                "java.util.Map",
                new Model.Source.MapEntry(eqn.ns, eqn.local),
                new Model.Coerce.MapAggregate(),
                keyField, valField);
    }

    private Model.FieldSpec buildSyntheticMapKvField(RecordComponentElement rc, String syntheticName,
                                                     TypeMirror type, String pathStr,
                                                     Map<String, String> nsMap,
                                                     String owner, String kind) {
        boolean isList = isJavaUtilList(type);
        if (isList && isJavaUtilList(((DeclaredType) type).getTypeArguments().get(0))) {
            error(rc, "@XmlMap '" + kind + "' of '" + owner + "': List<List<?>> not supported");
            throw new ClassifierException("nested list in map " + owner);
        }
        TypeMirror elemType = isList ? listElement(type, rc) : type;
        if (isJavaUtilMap(elemType)) {
            error(rc, "@XmlMap '" + kind + "' of '" + owner + "': nested Map not supported");
            throw new ClassifierException("nested map " + owner);
        }
        boolean nullable = !isList && nullableTypeArg(elemType);

        Model.Source source;
        if (pathStr.startsWith("@")) {
            String rest = pathStr.substring(1);
            if (rest.isEmpty() || rest.contains("/")) {
                error(rc, "@XmlMap '" + kind + "' of '" + owner + "': '@' path must be a single attribute name");
                throw new ClassifierException("map attr path bad " + owner);
            }
            QNameInfo aqn = resolveQName(rest, nsMap, /*defaultNs=*/null, rc,
                    "@XmlMap '" + kind + "'", syntheticName);
            source = new Model.Source.Attr(aqn.ns, aqn.local);
        } else {
            source = parseChildPath(pathStr, syntheticName, nsMap, rc);
        }

        Model.Coerce coerce;
        TypeName elemTypeName;
        TypeName boxedTypeName;
        String elemFq;
        if (isScalarOrTemporal(elemType)) {
            Model.ScalarKind sk = scalarKind(elemType);
            coerce = scalarCoerce(sk, null, rc, syntheticName);
            boxedTypeName = boxedScalarTypeName(sk);
            elemTypeName = boxedTypeName;
            elemFq = elemTypeFq(elemType);
        } else if (isRecordType(elemType)) {
            if (!(source instanceof Model.Source.Child)) {
                error(rc, "@XmlMap '" + kind + "' of '" + owner
                        + "': nested record requires an element path, not '@attr'");
                throw new ClassifierException("nested record from attr " + owner);
            }
            String nestedFq = ensureNested(rc, syntheticName,
                    (TypeElement) ((DeclaredType) elemType).asElement(), nsMap, /*terminating=*/true);
            coerce = new Model.Coerce.Nested(nestedFq);
            ClassName cn = ClassName.get((TypeElement) ((DeclaredType) elemType).asElement());
            elemTypeName = cn;
            boxedTypeName = cn;
            elemFq = nestedFq;
        } else {
            error(rc, "@XmlMap '" + kind + "' of '" + owner + "': unsupported type '" + elemType + "'");
            throw new ClassifierException("map kv unsupported type " + owner);
        }

        TypeName fieldTypeName;
        if (isList) {
            fieldTypeName = ParameterizedTypeName.get(ClassName.get("java.util", "List"), elemTypeName);
        } else {
            fieldTypeName = elemTypeName;
        }
        return new Model.FieldSpec(
                syntheticName, !nullable && !isList, isList, boxedTypeName, fieldTypeName,
                elemTypeName, elemFq, source, coerce);
    }

    /** {@code @org.jspecify.annotations.Nullable} on a type-arg position. */
    private boolean nullableTypeArg(TypeMirror tm) {
        if (tm == null) return false;
        for (AnnotationMirror am : tm.getAnnotationMirrors()) {
            if (FQ_NULLABLE.equals(annotationFq(am))) return true;
        }
        return false;
    }

    /**
     * Parses an @XmlChild path expression into a Source.Child. Supports:
     *   - "name", "prefix:name" — single-segment direct child
     *   - "wrapper/leaf" — multi-segment direct path
     *   - "//bar" — descendant axis
     *   - "//head/seg/.../@attr" — descendant + nested + attribute leaf
     *   - "name/@attr" — nested + attribute leaf
     */
    private Model.Source.Child parseChildPath(String path, String fieldName,
                                              Map<String, String> nsMap, Element where) {
        if (path == null || path.isBlank()) {
            error(where, "@XmlChild path empty for '" + fieldName + "'");
            throw new ClassifierException("empty path " + fieldName);
        }
        // Self-step shorthand. Historically tolerated by the loose splitter; treat as a single
        // literal segment so existing @XmlMap value="." paths keep compiling.
        if (path.equals(".")) {
            return new Model.Source.Child(
                    List.of(new Model.PathSeg.Element(nsMap.get(""), ".", null)),
                    false);
        }
        boolean descendant = path.startsWith("//");
        if (path.startsWith("/") && !descendant) {
            error(where, "@XmlChild path '" + path + "' for '" + fieldName + "': invalid syntax (absolute paths are not supported)");
            throw new ClassifierException("bad path syntax " + fieldName);
        }
        String defaultNs = nsMap.get("");
        Map<String, String> nsForParser = nsMap;
        PathParser parser = new PathParser(prefix -> nsForParser.get(prefix), defaultNs);
        CompiledPath compiled;
        try {
            compiled = parser.parse(path);
        } catch (PathParseException e) {
            error(where, "@XmlChild path '" + path + "' for '" + fieldName + "': "
                    + rewriteParseError(e.getMessage() == null ? "" : e.getMessage()));
            throw new ClassifierException("bad path " + fieldName);
        }
        List<Step> rawSteps = compiled.getSteps();
        // PathParser auto-prepends a descendant axis to every relative path; strip a leading
        // descendant either way and rely on our own boolean.
        List<Step> steps = !rawSteps.isEmpty() && rawSteps.get(0) instanceof Step.Descendant
                ? rawSteps.subList(1, rawSteps.size())
                : rawSteps;
        if (steps.isEmpty()) {
            error(where, "@XmlChild path '" + path + "' for '" + fieldName + "': empty after axis");
            throw new ClassifierException("empty after axis " + fieldName);
        }
        if (descendant && steps.get(0) instanceof Step.AttrLeaf) {
            error(where, "@XmlChild path '" + path + "' for '" + fieldName
                    + "': descendant axis head must be an element");
            throw new ClassifierException("descendant attr head " + fieldName);
        }
        List<Model.PathSeg> segs = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            boolean last = i == steps.size() - 1;
            if (s instanceof Step.Descendant) {
                error(where, "@XmlChild path '" + path + "' for '" + fieldName
                        + "': '//' is only allowed at the head of the path");
                throw new ClassifierException("inner descendant " + fieldName);
            } else if (s instanceof Step.AttrLeaf attr) {
                if (!last) {
                    error(where, "@XmlChild path '" + path + "' for '" + fieldName + "': '@' segment must be last");
                    throw new ClassifierException("@-not-last " + fieldName);
                }
                if (i == 0) {
                    error(where, "@XmlChild path '" + path + "' for '" + fieldName
                            + "': use @XmlAttr for record-level attributes");
                    throw new ClassifierException("@ at root " + fieldName);
                }
                segs.add(new Model.PathSeg.AttrLeaf(attr.getName().getNs(), attr.getName().getLocal()));
            } else {
                Step.Named named = (Step.Named) s;
                if ("*".equals(named.getName().getLocal())) {
                    error(where, "@XmlChild path '" + path + "' for '" + fieldName
                            + "': wildcard local-name '*' is not supported");
                    throw new ClassifierException("wildcard local " + fieldName);
                }
                if (PathParser.WILDCARD.equals(named.getName().getNs())) {
                    error(where, "@XmlChild path '" + path + "' for '" + fieldName
                            + "': wildcard namespace '{*}' is not supported");
                    throw new ClassifierException("wildcard ns " + fieldName);
                }
                Predicate pred = named.getPredicate();
                if (pred != null) validateChildPredicate(pred, path, fieldName, where);
                segs.add(new Model.PathSeg.Element(named.getName().getNs(), named.getName().getLocal(), pred));
            }
        }
        return new Model.Source.Child(segs, descendant);
    }

    private static String rewriteParseError(String msg) {
        String out = msg.replace("unbound namespace prefix", "unbound NS prefix");
        if (out.startsWith("expected local-name after ':'") || out.startsWith("expected local-name after '}'")) {
            out = "malformed (bad qname): " + out;
        }
        return out;
    }

    private void validateChildPredicate(Predicate p, String path, String fieldName, Element where) {
        if (p instanceof Predicate.Index idx) {
            error(where, "@XmlChild path '" + path + "' for '" + fieldName
                    + "': positional predicate [" + idx.getN() + "] is not supported inside @XmlChild. "
                    + "Move the positional filter to @XmlRecord (e.g. @XmlRecord(\"//... [" + idx.getN() + "]\")) "
                    + "or collect siblings into a List<T> field and pick by index in your code.");
            throw new ClassifierException("index predicate " + fieldName);
        } else if (p instanceof Predicate.AttrEq ae) {
            if (PathParser.WILDCARD.equals(ae.getName().getNs())) {
                error(where, "@XmlChild path '" + path + "' for '" + fieldName
                        + "': wildcard namespace in attribute predicate is not supported");
                throw new ClassifierException("wildcard pred ns " + fieldName);
            }
        } else if (p instanceof Predicate.And and) {
            validateChildPredicate(and.getL(), path, fieldName, where);
            validateChildPredicate(and.getR(), path, fieldName, where);
        } else if (p instanceof Predicate.Or or) {
            validateChildPredicate(or.getL(), path, fieldName, where);
            validateChildPredicate(or.getR(), path, fieldName, where);
        }
    }

    private void validateChildPaths(TypeElement owner, String ownerFq, List<Model.FieldSpec> fields) {
        TrieNode root = new TrieNode();
        Set<Model.QKey> directKeys = new LinkedHashSet<>();
        for (Model.FieldSpec f : fields) {
            if (!(f.source() instanceof Model.Source.Child sc)) continue;
            if (sc.descendant()) continue;
            insertIntoTrie(root, sc.segments(), f, owner, ownerFq);
            Model.PathSeg first = sc.segments().get(0);
            if (first instanceof Model.PathSeg.Element e) {
                directKeys.add(new Model.QKey(e.ns(), e.name()));
            }
        }
        validateTrie(root, owner, ownerFq);

        Set<Model.QKey> descendantHeads = new LinkedHashSet<>();
        for (Model.FieldSpec f : fields) {
            if (!(f.source() instanceof Model.Source.Child sc)) continue;
            if (!sc.descendant()) continue;
            Model.PathSeg first = sc.segments().get(0);
            if (first instanceof Model.PathSeg.Element e) {
                descendantHeads.add(new Model.QKey(e.ns(), e.name()));
            }
        }
        for (Model.QKey k : descendantHeads) {
            if (directKeys.contains(k)) {
                error(owner, ownerFq + ": @XmlChild('" + k.local()
                        + "') and @XmlChild('//" + k.local()
                        + "') target the same head element '" + k.local() + "'; pick one");
                throw new ClassifierException("head collision " + ownerFq);
            }
        }

        Set<Model.QKey> seenMapEntries = new LinkedHashSet<>();
        for (Model.FieldSpec f : fields) {
            if (!(f.source() instanceof Model.Source.MapEntry me)) continue;
            Model.QKey k = new Model.QKey(me.entryNs(), me.entryLocal());
            if (directKeys.contains(k)) {
                error(owner, ownerFq + ": @XmlMap entry '" + me.entryLocal()
                        + "' on field '" + f.name() + "' clashes with another @XmlChild's first segment");
                throw new ClassifierException("map vs child " + ownerFq);
            }
            if (descendantHeads.contains(k)) {
                error(owner, ownerFq + ": @XmlMap entry '" + me.entryLocal()
                        + "' on field '" + f.name() + "' clashes with a descendant @XmlChild('//"
                        + k.local() + "') head");
                throw new ClassifierException("map vs descendant " + ownerFq);
            }
            if (!seenMapEntries.add(k)) {
                error(owner, ownerFq + ": duplicate @XmlMap entry '" + me.entryLocal()
                        + "' on field '" + f.name() + "'");
                throw new ClassifierException("dup map entry " + ownerFq);
            }
        }

        Set<Model.QKey> seenPolyKeys = new LinkedHashSet<>();
        boolean sawTagMode = false;
        for (Model.FieldSpec f : fields) {
            if (!(f.source() instanceof Model.Source.PolyChild pc)) continue;
            Model.PolyDispatch d = pc.dispatch();
            if (d instanceof Model.PolyDispatch.Tag tag) {
                if (sawTagMode) {
                    error(owner, ownerFq
                            + ": more than one tag-mode polymorphic @XmlChild field at the same scope (field '"
                            + f.name() + "')");
                    throw new ClassifierException("multi tag-mode " + ownerFq);
                }
                sawTagMode = true;
                for (Model.TagVariant v : tag.variants()) {
                    Model.QKey k = new Model.QKey(v.ns(), v.local());
                    if (directKeys.contains(k) || seenMapEntries.contains(k) || !seenPolyKeys.add(k)) {
                        error(owner, ownerFq + ": polymorphic subtype tag '" + v.local()
                                + "' on field '" + f.name()
                                + "' clashes with another @XmlChild / @XmlMap / subtype");
                        throw new ClassifierException("poly tag clash " + ownerFq);
                    }
                    if (descendantHeads.contains(k)) {
                        error(owner, ownerFq + ": polymorphic subtype tag '" + v.local()
                                + "' on field '" + f.name() + "' clashes with a descendant @XmlChild('//"
                                + k.local() + "') head");
                        throw new ClassifierException("poly tag desc clash " + ownerFq);
                    }
                }
            } else {
                Model.PolyDispatch.Attr ad = (Model.PolyDispatch.Attr) d;
                Model.QKey k = new Model.QKey(ad.wrapNs(), ad.wrapLocal());
                if (directKeys.contains(k) || seenMapEntries.contains(k) || !seenPolyKeys.add(k)) {
                    error(owner, ownerFq + ": polymorphic wrap tag '" + ad.wrapLocal()
                            + "' on field '" + f.name()
                            + "' clashes with another @XmlChild / @XmlMap / subtype");
                    throw new ClassifierException("poly wrap clash " + ownerFq);
                }
                if (descendantHeads.contains(k)) {
                    error(owner, ownerFq + ": polymorphic wrap tag '" + ad.wrapLocal()
                            + "' on field '" + f.name() + "' clashes with a descendant @XmlChild('//"
                            + k.local() + "') head");
                    throw new ClassifierException("poly wrap desc clash " + ownerFq);
                }
            }
        }
    }

    /** Trie node for direct-child path validation. Mirrors KSP. */
    static final class TrieNode {
        final Map<Model.EdgeKey, TrieNode> children = new LinkedHashMap<>();
        final List<AttrEntry> attrEntries = new ArrayList<>();
        final List<Model.FieldSpec> textEntries = new ArrayList<>();
        final List<Model.FieldSpec> nestedEntries = new ArrayList<>();
    }

    record AttrEntry(String ns, String name, Model.FieldSpec field) {}

    private void insertIntoTrie(TrieNode root, List<Model.PathSeg> segments, Model.FieldSpec f,
                                TypeElement owner, String ownerFq) {
        TrieNode node = root;
        int i = 0;
        for (; i < segments.size(); i++) {
            if (!(segments.get(i) instanceof Model.PathSeg.Element e)) break;
            Model.EdgeKey edge = new Model.EdgeKey(new Model.QKey(e.ns(), e.name()), e.predicate());
            node = node.children.computeIfAbsent(edge, k -> new TrieNode());
        }
        if (i == segments.size()) {
            if (f.coerce() instanceof Model.Coerce.Nested) node.nestedEntries.add(f);
            else node.textEntries.add(f);
        } else if (i == segments.size() - 1 && segments.get(i) instanceof Model.PathSeg.AttrLeaf al) {
            node.attrEntries.add(new AttrEntry(al.ns(), al.name(), f));
        } else {
            error(owner, ownerFq + ": invalid path tail for field '" + f.name() + "'");
            throw new ClassifierException("bad tail " + ownerFq);
        }
    }

    private void validateTrie(TrieNode node, TypeElement owner, String ownerFq) {
        int mix = 0;
        if (!node.textEntries.isEmpty()) mix++;
        if (!node.nestedEntries.isEmpty()) mix++;
        if (!node.children.isEmpty()) mix++;
        if (mix > 1) {
            String texts = node.textEntries.stream().map(Model.FieldSpec::name).reduce((a, b) -> a + "," + b).orElse("");
            String nested = node.nestedEntries.stream().map(Model.FieldSpec::name).reduce((a, b) -> a + "," + b).orElse("");
            error(owner, ownerFq + ": cannot mix text/nested/descend at same element [text="
                    + texts + " nested=" + nested + " children=" + node.children.size() + "]");
            throw new ClassifierException("trie mix " + ownerFq);
        }
        long nonListText = node.textEntries.stream().filter(f -> !f.isList()).count();
        if (nonListText > 1) {
            String names = node.textEntries.stream().filter(f -> !f.isList())
                    .map(Model.FieldSpec::name).reduce((a, b) -> a + "," + b).orElse("");
            error(owner, ownerFq + ": multiple non-list text fields [" + names + "] target same element");
            throw new ClassifierException("multi text " + ownerFq);
        }
        long nonListNested = node.nestedEntries.stream().filter(f -> !f.isList()).count();
        if (nonListNested > 1) {
            String names = node.nestedEntries.stream().filter(f -> !f.isList())
                    .map(Model.FieldSpec::name).reduce((a, b) -> a + "," + b).orElse("");
            error(owner, ownerFq + ": multiple non-list nested fields [" + names + "] target same element");
            throw new ClassifierException("multi nested " + ownerFq);
        }
        if (node.nestedEntries.size() > 1
                && node.nestedEntries.stream().anyMatch(Model.FieldSpec::isList)
                && node.nestedEntries.stream().anyMatch(f -> !f.isList())) {
            error(owner, ownerFq + ": cannot mix list and non-list nested fields at same element");
            throw new ClassifierException("mix list nested " + ownerFq);
        }
        for (TrieNode c : node.children.values()) validateTrie(c, owner, ownerFq);
    }

    /** Returns nested record FQ; creates registry entry if new. */
    private String ensureNested(Element where, String fieldName, TypeElement nestedTe,
                                Map<String, String> nsMap, boolean terminating) {
        String nestedFq = nestedTe.getQualifiedName().toString();
        Map<String, String> nestedOwn = collectOwnNs(nestedTe);
        for (var e : nestedOwn.entrySet()) {
            String parentUri = nsMap.get(e.getKey());
            if (parentUri != null && !parentUri.equals(e.getValue())) {
                error(where, "Nested record '" + nestedFq + "' redeclares @XmlNs prefix '"
                        + e.getKey() + "' as '" + e.getValue() + "' but the enclosing record binds it to '"
                        + parentUri + "'");
                throw new ClassifierException("nested ns conflict on " + fieldName);
            }
        }
        Map<String, String> nestedNs = new LinkedHashMap<>(nsMap);
        nestedNs.putAll(nestedOwn);
        Model.RecordSpec already = registry.byFq.get(nestedFq);
        if (already != null) {
            if (!already.nsMap().equals(nestedNs)) {
                error(where, "Nested record '" + nestedFq + "' is reused with conflicting "
                        + "namespace maps: " + already.nsMap() + " vs " + nestedNs);
                throw new ClassifierException("nested ns reuse mismatch on " + fieldName);
            }
            return nestedFq;
        }
        if (!terminating && registry.inProgress.contains(nestedFq)) {
            String chain = String.join(" -> ", registry.inProgress) + " -> " + nestedFq;
            error(where, "recursive nested record '" + nestedFq
                    + "' is not supported by xml-fluss-apt (cycle: " + chain + ")");
            throw new ClassifierException("nested cycle on " + fieldName);
        }
        registry.helperName(nestedFq);
        registry.inProgress.add(nestedFq);
        try {
            Model.RecordSpec nestedSpec = classify(nestedTe, "", nestedNs);
            registry.byFq.put(nestedFq, nestedSpec);
        } finally {
            registry.inProgress.remove(nestedFq);
        }
        return nestedFq;
    }

    private List<TypeElement> sealedSubclasses(TypeElement parent) {
        List<TypeElement> out = new ArrayList<>();
        for (TypeMirror tm : parent.getPermittedSubclasses()) {
            if (tm.getKind() != TypeKind.DECLARED) continue;
            out.add((TypeElement) ((DeclaredType) tm).asElement());
        }
        return out;
    }

    private TypeElement polymorphicParent(TypeMirror tm) {
        if (tm == null || tm.getKind() != TypeKind.DECLARED) return null;
        TypeElement te = (TypeElement) ((DeclaredType) tm).asElement();
        if (!te.getModifiers().contains(Modifier.SEALED)) return null;
        if (findAnnotation(te, FQ_XML_POLYMORPHIC) == null) return null;
        return te;
    }

    private Model.Coerce scalarCoerce(Model.ScalarKind kind, String formatPattern,
                                      RecordComponentElement rc, String name) {
        return switch (kind) {
            case STRING -> new Model.Coerce.AsString();
            case INT, LONG, DOUBLE, BOOLEAN -> {
                if (formatPattern != null) {
                    error(rc, "@XmlFormat on '" + name + "' is only supported for LocalDate, "
                            + "LocalDateTime, Instant, or BigDecimal");
                    throw new ClassifierException("format on numeric/boolean " + name);
                }
                yield new Model.Coerce.Scalar(kind);
            }
            case LOCAL_DATE, LOCAL_DATE_TIME, INSTANT ->
                    new Model.Coerce.Temporal(kind, formatPattern == null ? "" : formatPattern);
            case BIG_DECIMAL ->
                    new Model.Coerce.Decimal(formatPattern == null ? "" : formatPattern);
        };
    }

    private String readRecordPath(TypeElement element) {
        AnnotationMirror am = findAnnotation(element, FQ_XML_RECORD);
        assert am != null : "@XmlRecord annotation must be present on " + element;
        String p = stringValue(am, "path");
        if (p == null) {
            error(element, "@XmlRecord requires a non-null 'path' value on " + element);
            throw new ClassifierException("no path in @XmlRecord");
        }
        return p;
    }

    private Map<String, String> collectOwnNs(TypeElement element) {
        Map<String, String> out = new LinkedHashMap<>();
        for (AnnotationMirror am : element.getAnnotationMirrors()) {
            String fq = annotationFq(am);
            if (FQ_XML_NS.equals(fq)) {
                addNs(element, am, out);
            } else if (FQ_XML_NAMESPACES.equals(fq)) {
                AnnotationValue v = annotationValue(am, "value");
                if (v == null) continue;
                Object o = v.getValue();
                if (!(o instanceof List<?> list)) continue;
                for (Object e : list) {
                    if (e instanceof AnnotationValue av && av.getValue() instanceof AnnotationMirror inner) {
                        addNs(element, inner, out);
                    }
                }
            }
        }
        return out;
    }

    private void addNs(TypeElement owner, AnnotationMirror am, Map<String, String> sink) {
        String prefix = stringValue(am, "prefix");
        String uri = stringValue(am, "uri");
        if (prefix == null) prefix = "";
        if (uri == null) {
            error(owner, "@XmlNs missing 'uri' value");
            throw new ClassifierException("no uri in @XmlNs");
        }
        String prior = sink.get(prefix);
        if (prior != null && !prior.equals(uri)) {
            error(owner, "@XmlNs prefix '" + prefix + "' bound to two URIs: '"
                    + prior + "' and '" + uri + "'");
            throw new ClassifierException("conflicting @XmlNs on owner");
        }
        sink.put(prefix, uri);
    }

    private record QNameInfo(String ns, String local) {}

    private QNameInfo resolveQName(String s, Map<String, String> nsMap, String defaultNs,
                                   Element where, String ctx, String fieldName) {
        int ci = s.indexOf(':');
        if (ci < 0) return new QNameInfo(defaultNs, s);
        String prefix = s.substring(0, ci);
        String local = s.substring(ci + 1);
        if (prefix.isEmpty() || local.isEmpty()) {
            error(where, ctx + " '" + s + "' on '" + fieldName + "' is malformed");
            throw new ClassifierException("malformed qname on " + fieldName);
        }
        String ns = nsMap.get(prefix);
        if (ns == null) {
            error(where, ctx + " '" + s + "' on '" + fieldName + "': unbound NS prefix '"
                    + prefix + "' (declare via @XmlNs)");
            throw new ClassifierException("unbound prefix on " + fieldName);
        }
        return new QNameInfo(ns, local);
    }

    private static String qnameKey(String ns, String local) {
        return (ns == null ? "" : "{" + ns + "}") + local;
    }

    private boolean isJavaUtilList(TypeMirror tm) {
        if (tm.getKind() != TypeKind.DECLARED) return false;
        TypeElement te = (TypeElement) ((DeclaredType) tm).asElement();
        return "java.util.List".contentEquals(te.getQualifiedName());
    }

    private boolean isJavaUtilMap(TypeMirror tm) {
        if (tm.getKind() != TypeKind.DECLARED) return false;
        TypeElement te = (TypeElement) ((DeclaredType) tm).asElement();
        return "java.util.Map".contentEquals(te.getQualifiedName());
    }

    private boolean isOptional(TypeMirror tm) {
        if (tm.getKind() != TypeKind.DECLARED) return false;
        TypeElement te = (TypeElement) ((DeclaredType) tm).asElement();
        return "java.util.Optional".contentEquals(te.getQualifiedName());
    }

    private TypeMirror listElement(TypeMirror listType, RecordComponentElement rc) {
        DeclaredType dt = (DeclaredType) listType;
        if (dt.getTypeArguments().isEmpty()) {
            error(rc, "Raw List is not supported; use List<T>");
            throw new ClassifierException("raw list");
        }
        return dt.getTypeArguments().get(0);
    }

    private boolean isScalarOrTemporal(TypeMirror tm) {
        if (tm.getKind().isPrimitive()) {
            return switch (tm.getKind()) {
                case INT, LONG, DOUBLE, BOOLEAN -> true;
                default -> false;
            };
        }
        if (tm.getKind() != TypeKind.DECLARED) return false;
        String fq = ((TypeElement) ((DeclaredType) tm).asElement()).getQualifiedName().toString();
        return switch (fq) {
            case "java.lang.String", "java.lang.Integer", "java.lang.Long", "java.lang.Double",
                 "java.lang.Boolean", "java.math.BigDecimal", "java.time.LocalDate",
                 "java.time.LocalDateTime", "java.time.Instant" -> true;
            default -> false;
        };
    }

    private boolean isFormattableType(TypeMirror tm) {
        if (tm.getKind().isPrimitive()) return false;
        if (tm.getKind() != TypeKind.DECLARED) return false;
        String fq = ((TypeElement) ((DeclaredType) tm).asElement()).getQualifiedName().toString();
        return switch (fq) {
            case "java.math.BigDecimal", "java.time.LocalDate",
                 "java.time.LocalDateTime", "java.time.Instant" -> true;
            default -> false;
        };
    }

    private Model.ScalarKind scalarKind(TypeMirror tm) {
        if (tm.getKind().isPrimitive()) {
            return switch (tm.getKind()) {
                case INT -> Model.ScalarKind.INT;
                case LONG -> Model.ScalarKind.LONG;
                case DOUBLE -> Model.ScalarKind.DOUBLE;
                case BOOLEAN -> Model.ScalarKind.BOOLEAN;
                default -> throw new IllegalStateException("non-supported primitive: " + tm.getKind());
            };
        }
        String fq = ((TypeElement) ((DeclaredType) tm).asElement()).getQualifiedName().toString();
        return switch (fq) {
            case "java.lang.String" -> Model.ScalarKind.STRING;
            case "java.lang.Integer" -> Model.ScalarKind.INT;
            case "java.lang.Long" -> Model.ScalarKind.LONG;
            case "java.lang.Double" -> Model.ScalarKind.DOUBLE;
            case "java.lang.Boolean" -> Model.ScalarKind.BOOLEAN;
            case "java.math.BigDecimal" -> Model.ScalarKind.BIG_DECIMAL;
            case "java.time.LocalDate" -> Model.ScalarKind.LOCAL_DATE;
            case "java.time.LocalDateTime" -> Model.ScalarKind.LOCAL_DATE_TIME;
            case "java.time.Instant" -> Model.ScalarKind.INSTANT;
            default -> throw new IllegalStateException("not a scalar: " + fq);
        };
    }

    private static TypeName boxedScalarTypeName(Model.ScalarKind kind) {
        return switch (kind) {
            case STRING -> ClassName.get("java.lang", "String");
            case INT -> ClassName.get("java.lang", "Integer");
            case LONG -> ClassName.get("java.lang", "Long");
            case DOUBLE -> ClassName.get("java.lang", "Double");
            case BOOLEAN -> ClassName.get("java.lang", "Boolean");
            case BIG_DECIMAL -> ClassName.get("java.math", "BigDecimal");
            case LOCAL_DATE -> ClassName.get("java.time", "LocalDate");
            case LOCAL_DATE_TIME -> ClassName.get("java.time", "LocalDateTime");
            case INSTANT -> ClassName.get("java.time", "Instant");
        };
    }

    private static TypeName primitiveTypeName(TypeMirror tm) {
        return switch (tm.getKind()) {
            case INT -> TypeName.INT;
            case LONG -> TypeName.LONG;
            case DOUBLE -> TypeName.DOUBLE;
            case BOOLEAN -> TypeName.BOOLEAN;
            default -> throw new IllegalStateException("not a supported primitive: " + tm.getKind());
        };
    }

    private static String elemTypeFq(TypeMirror tm) {
        if (tm.getKind().isPrimitive()) {
            return switch (tm.getKind()) {
                case INT -> "int";
                case LONG -> "long";
                case DOUBLE -> "double";
                case BOOLEAN -> "boolean";
                default -> tm.toString();
            };
        }
        if (tm.getKind() == TypeKind.DECLARED) {
            return ((TypeElement) ((DeclaredType) tm).asElement()).getQualifiedName().toString();
        }
        return tm.toString();
    }

    private boolean isRecordType(TypeMirror tm) {
        if (tm.getKind() != TypeKind.DECLARED) return false;
        var asElem = ((DeclaredType) tm).asElement();
        return asElem.getKind() == ElementKind.RECORD;
    }

    private boolean hasAnn(Element e, String fq) {
        if (e == null) return false;
        for (AnnotationMirror am : e.getAnnotationMirrors()) {
            if (fq.equals(annotationFq(am))) return true;
        }
        return false;
    }

    private boolean hasTypeUseAnn(TypeMirror tm, String fq) {
        if (tm == null) return false;
        for (AnnotationMirror am : tm.getAnnotationMirrors()) {
            if (fq.equals(annotationFq(am))) return true;
        }
        return false;
    }

    private boolean hasNonNull(Element e) { return hasAnn(e, FQ_NON_NULL); }
    private boolean hasNullable(Element e) { return hasAnn(e, FQ_NULLABLE); }

    /**
     * JSpecify-aware nullability:
     *  - primitives & lists are always required
     *  - explicit @NonNull → required
     *  - explicit @Nullable → nullable
     *  - inside @NullMarked scope (package or enclosing type): required by default
     *  - otherwise: nullable by default (legacy behaviour)
     */
    private boolean computeNullable(RecordComponentElement rc, VariableElement ctorParam,
                                    TypeMirror declaredType, TypeElement owner,
                                    boolean primitive, boolean isList) {
        if (primitive || isList) return false;
        // explicit overrides win
        if (hasNonNull(rc) || hasNonNull(ctorParam)
                || hasTypeUseAnn(declaredType, FQ_NON_NULL)
                || (ctorParam != null && hasTypeUseAnn(ctorParam.asType(), FQ_NON_NULL))) {
            return false;
        }
        if (hasNullable(rc) || hasNullable(ctorParam)
                || hasTypeUseAnn(declaredType, FQ_NULLABLE)
                || (ctorParam != null && hasTypeUseAnn(ctorParam.asType(), FQ_NULLABLE))) {
            return true;
        }
        if (rc.getAccessor() != null) {
            ExecutableElement acc = rc.getAccessor();
            if (hasNonNull(acc) || hasTypeUseAnn(acc.getReturnType(), FQ_NON_NULL)) return false;
            if (hasNullable(acc) || hasTypeUseAnn(acc.getReturnType(), FQ_NULLABLE)) return true;
        }
        // No explicit annotation — consult @NullMarked / @NullUnmarked scope.
        return !isNullMarkedScope(owner != null ? owner : (rc.getEnclosingElement() instanceof TypeElement te ? te : null));
    }

    private boolean isNullMarkedScope(TypeElement type) {
        // Walk type → enclosing types → package, looking for @NullMarked / @NullUnmarked.
        // The closest annotation wins.
        Deque<Element> chain = new ArrayDeque<>();
        Element cur = type;
        while (cur != null) {
            chain.addFirst(cur);
            cur = cur.getEnclosingElement();
        }
        Boolean state = null;
        for (Element e : chain) {
            if (hasAnn(e, FQ_NULL_MARKED)) state = true;
            else if (hasAnn(e, FQ_NULL_UNMARKED)) state = false;
        }
        if (state != null) return state;
        if (type != null) {
            PackageElement pkg = elements.getPackageOf(type);
            return hasAnn(pkg, FQ_NULL_MARKED);
        }
        return false;
    }

    private AnnotationMirror findAnnotation(Element e, String fq) {
        for (AnnotationMirror am : e.getAnnotationMirrors()) {
            if (fq.equals(annotationFq(am))) return am;
        }
        return null;
    }

    private AnnotationMirror findAnnotation(RecordComponentElement rc,
                                            VariableElement ctorParam,
                                            String fq) {
        AnnotationMirror a = findAnnotation(rc, fq);
        if (a != null) return a;
        if (ctorParam != null) return findAnnotation(ctorParam, fq);
        return null;
    }

    private static String annotationFq(AnnotationMirror am) {
        return ((TypeElement) am.getAnnotationType().asElement()).getQualifiedName().toString();
    }

    private static String stringValue(AnnotationMirror am, String key) {
        AnnotationValue v = annotationValue(am, key);
        if (v == null) return null;
        Object o = v.getValue();
        return o == null ? null : o.toString();
    }

    private static AnnotationValue annotationValue(AnnotationMirror am, String key) {
        for (var entry : am.getElementValues().entrySet()) {
            if (entry.getKey().getSimpleName().contentEquals(key)) return entry.getValue();
        }
        for (var member : am.getAnnotationType().asElement().getEnclosedElements()) {
            if (member.getKind() != ElementKind.METHOD) continue;
            if (!member.getSimpleName().contentEquals(key)) continue;
            ExecutableElement ee = (ExecutableElement) member;
            return ee.getDefaultValue();
        }
        return null;
    }

    private static String nonEmpty(String value, String fallback) {
        return (value == null || value.isEmpty()) ? fallback : value;
    }

    private record ConverterInfo(ClassName className, String fqn) {}

    private ConverterInfo validateConverter(RecordComponentElement rc, String fieldName,
                                            TypeMirror converterTm, TypeMirror elemType) {
        if (converterTm.getKind() != TypeKind.DECLARED) {
            error(rc, "@XmlConverter cls on '" + fieldName + "' must be a class type");
            throw new ClassifierException("converter not declared type on " + fieldName);
        }
        TypeElement converterTe = (TypeElement) ((DeclaredType) converterTm).asElement();
        boolean hasNoArg = false;
        for (var enc : converterTe.getEnclosedElements()) {
            if (enc.getKind() != ElementKind.CONSTRUCTOR) continue;
            ExecutableElement ee = (ExecutableElement) enc;
            if (!ee.getParameters().isEmpty()) continue;
            if (!ee.getModifiers().contains(Modifier.PUBLIC)) continue;
            hasNoArg = true;
            break;
        }
        if (!hasNoArg) {
            error(rc, "@XmlConverter on '" + fieldName + "': " + converterTe.getQualifiedName()
                    + " must have a public no-arg constructor");
            throw new ClassifierException("converter no public default ctor on " + fieldName);
        }
        TypeMirror converterT = findConverterTypeArg(converterTe);
        if (converterT == null) {
            error(rc, "@XmlConverter on '" + fieldName + "': " + converterTe.getQualifiedName()
                    + " does not implement " + FQ_CONVERTER + "<T>");
            throw new ClassifierException("converter doesn't implement Converter on " + fieldName);
        }
        TypeMirror targetBox = boxIfPrimitive(elemType);
        if (!types.isAssignable(converterT, targetBox)) {
            error(rc, "@XmlConverter on '" + fieldName + "': converter produces "
                    + converterT + " which is not assignable to field type " + elemType);
            throw new ClassifierException("converter type mismatch on " + fieldName);
        }
        ClassName cn = ClassName.get(converterTe);
        return new ConverterInfo(cn, converterTe.getQualifiedName().toString());
    }

    private TypeMirror findConverterTypeArg(TypeElement te) {
        ArrayDeque<TypeMirror> q = new ArrayDeque<>();
        q.add(te.asType());
        Set<String> seen = new HashSet<>();
        while (!q.isEmpty()) {
            TypeMirror tm = q.removeFirst();
            if (tm.getKind() != TypeKind.DECLARED) continue;
            DeclaredType dt = (DeclaredType) tm;
            TypeElement decl = (TypeElement) dt.asElement();
            String fq = decl.getQualifiedName().toString();
            if (!seen.add(fq)) continue;
            if (FQ_CONVERTER.equals(fq) && !dt.getTypeArguments().isEmpty()) {
                return dt.getTypeArguments().get(0);
            }
            q.addAll(types.directSupertypes(tm));
        }
        return null;
    }

    private TypeMirror boxIfPrimitive(TypeMirror tm) {
        if (!tm.getKind().isPrimitive()) return tm;
        return types.boxedClass((javax.lang.model.type.PrimitiveType) tm).asType();
    }

    private void error(Element where, String msg) {
        env.getMessager().printMessage(Diagnostic.Kind.ERROR, "xml-fluss-apt: " + msg, where);
    }
}
