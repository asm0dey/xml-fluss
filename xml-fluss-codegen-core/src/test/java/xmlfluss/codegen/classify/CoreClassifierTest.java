package xmlfluss.codegen.classify;

import org.junit.jupiter.api.Test;
import xmlfluss.codegen.model.AttrVariant;
import xmlfluss.codegen.model.Coerce;
import xmlfluss.codegen.model.FieldSpec;
import xmlfluss.codegen.model.PathSeg;
import xmlfluss.codegen.model.PolyDispatch;
import xmlfluss.codegen.model.RecordSpec;
import xmlfluss.codegen.model.ScalarKind;
import xmlfluss.codegen.model.Source;
import xmlfluss.codegen.model.TagVariant;
import xmlfluss.codegen.model.TypeRef;
import xmlfluss.codegen.spi.FakeAnnotationView;
import xmlfluss.codegen.spi.FakeComponentSymbol;
import xmlfluss.codegen.spi.FakeDiagnosticReporter;
import xmlfluss.codegen.spi.FakeRecordSymbol;
import xmlfluss.codegen.spi.FakeSymbolProvider;
import xmlfluss.codegen.spi.FakeTypeSymbol;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CoreClassifierTest {

    private static final String FQ_XML_ATTR      = CoreClassifier.FQ_XML_ATTR;
    private static final String FQ_XML_FORMAT    = CoreClassifier.FQ_XML_FORMAT;
    private static final String FQ_XML_CONVERTER = CoreClassifier.FQ_XML_CONVERTER;

    // ------------------------------------------------------------------ helpers

    private static FakeRecordSymbol recordWith(List<FakeComponentSymbol> components,
                                               Map<String, String> nsMap) {
        return new FakeRecordSymbol(
                "com.example", "MyRecord", List.copyOf(components),
                nsMap, null, null, List.of(),
                FakeAnnotationView.builder().build(), "handle-MyRecord");
    }

    private static FakeRecordSymbol simpleRecord(FakeComponentSymbol... components) {
        return recordWith(List.of(components), Map.of());
    }

    /** Component with @XmlAttr (default name = component name). */
    private static FakeComponentSymbol attrComponent(String name, TypeRef type, boolean nullable) {
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .annotation(FQ_XML_ATTR)
                .build();
        return new FakeComponentSymbol(name, type, nullable, false, type, null, ann, "handle-" + name);
    }

    /** Component with @XmlAttr and explicit name override. */
    private static FakeComponentSymbol attrComponentWithName(String componentName, String attrName,
                                                              TypeRef type, boolean nullable) {
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .string(FQ_XML_ATTR, "name", attrName)
                .build();
        return new FakeComponentSymbol(componentName, type, nullable, false, type, null, ann,
                "handle-" + componentName);
    }

    // ------------------------------------------------------------------ simple String @XmlAttr

    @Test
    void xmlAttr_string_producesAsStringCoerce() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        FakeComponentSymbol c = attrComponent("id", strType, false);
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec spec = new CoreClassifier(sp).classify(rec);

        assertNotNull(spec, "should classify successfully");
        assertEquals(1, spec.fields().size());
        FieldSpec f = spec.fields().get(0);
        assertEquals("id", f.name());
        assertTrue(f.required());
        assertFalse(f.isList());
        assertInstanceOf(Coerce.AsString.class, f.coerce());
        assertInstanceOf(Source.Attr.class, f.source());
        Source.Attr src = (Source.Attr) f.source();
        assertNull(src.ns());
        assertEquals("id", src.name());
        assertTrue(diag.entries.isEmpty());
    }

    // ------------------------------------------------------------------ primitive int @XmlAttr

    @Test
    void xmlAttr_primitiveInt_producesScalarCoerce() {
        TypeRef intType = TypeRef.ofPrimitive("int");
        FakeComponentSymbol c = attrComponent("count", intType, false);
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNotNull(result);
        FieldSpec f = result.fields().get(0);
        assertTrue(f.required());
        assertInstanceOf(Coerce.Scalar.class, f.coerce());
        assertEquals(ScalarKind.INT, ((Coerce.Scalar) f.coerce()).kind());
        // fieldType and elemType should be primitive
        assertTrue(f.fieldType().primitive());
        assertTrue(f.elemType().primitive());
        assertEquals("int", f.elemTypeFq());
    }

    // ------------------------------------------------------------------ nullable Integer @XmlAttr

    @Test
    void xmlAttr_nullableInteger_requiredFalse() {
        TypeRef integerType = TypeRef.of("java.lang", "Integer");
        FakeComponentSymbol c = attrComponent("count", integerType, true);
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNotNull(result);
        FieldSpec f = result.fields().get(0);
        assertFalse(f.required());
        assertInstanceOf(Coerce.Scalar.class, f.coerce());
        assertEquals(ScalarKind.INT, ((Coerce.Scalar) f.coerce()).kind());
    }

    // ------------------------------------------------------------------ @XmlAttr name override

    @Test
    void xmlAttr_nameOverride_usesAnnotationName() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        FakeComponentSymbol c = attrComponentWithName("barField", "foo", strType, false);
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNotNull(result);
        FieldSpec f = result.fields().get(0);
        assertEquals("barField", f.name()); // component name
        Source.Attr src = (Source.Attr) f.source();
        assertEquals("foo", src.name()); // annotation name used
    }

    // ------------------------------------------------------------------ @XmlAttr on List → error

    @Test
    void xmlAttr_onList_reportsDiagnosticAndReturnsNull() {
        TypeRef listType = TypeRef.parameterized("java.util", "List",
                List.of(TypeRef.of("java.lang", "String")));
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .annotation(FQ_XML_ATTR)
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "items", listType, false, true, TypeRef.of("java.lang", "String"),
                null, ann, "handle-items");
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNull(result, "classify should return null when @XmlAttr field has an error");
        assertFalse(diag.entries.isEmpty(), "expected an error diagnostic");
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("@XmlAttr does not support List")));
    }

    // ------------------------------------------------------------------ @XmlAttr on non-scalar → error

    @Test
    void xmlAttr_onNonScalarType_reportsDiagnosticAndReturnsNull() {
        TypeRef customType = TypeRef.of("com.example", "MyBean");
        FakeComponentSymbol c = attrComponent("bean", customType, false);
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNull(result, "classify should return null when @XmlAttr field has an error");
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("@XmlAttr requires a scalar type")));
    }

    // ------------------------------------------------------------------ namespace prefix resolution

    @Test
    void xmlAttr_withNamespacePrefix_resolvesCorrectly() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .string(FQ_XML_ATTR, "name", "ns:lang")
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "lang", strType, false, false, strType, null, ann, "handle-lang");
        FakeRecordSymbol rec = recordWith(
                List.of(c),
                Map.of("ns", "http://example.com/ns"));
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNotNull(result);
        assertEquals(1, result.fields().size());
        Source.Attr src = (Source.Attr) result.fields().get(0).source();
        assertEquals("http://example.com/ns", src.ns());
        assertEquals("lang", src.name());
        assertTrue(diag.entries.isEmpty());
    }

    // ------------------------------------------------------------------ duplicate @XmlAttr keys → error

    @Test
    void xmlAttr_duplicateKeys_reportsDiagnosticAndClassifyReturnsNull() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        // Both components bind to XML attribute "id" (same local name, no namespace)
        FakeAnnotationView ann1 = FakeAnnotationView.builder()
                .string(FQ_XML_ATTR, "name", "id")
                .build();
        FakeAnnotationView ann2 = FakeAnnotationView.builder()
                .string(FQ_XML_ATTR, "name", "id")
                .build();
        FakeComponentSymbol c1 = new FakeComponentSymbol(
                "field1", strType, false, false, strType, null, ann1, "handle-c1");
        FakeComponentSymbol c2 = new FakeComponentSymbol(
                "field2", strType, false, false, strType, null, ann2, "handle-c2");
        FakeRecordSymbol rec = simpleRecord(c1, c2);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNull(result, "classify should return null on duplicate attr keys");
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("duplicate @XmlAttr name")));
    }

    // ------------------------------------------------------------------ @XmlAttr + @XmlConverter

    @Test
    void xmlAttr_withConverter_usesCustomCoerce() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        TypeRef converterType = TypeRef.of("com.example", "MyConverter");
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .annotation(FQ_XML_ATTR)
                .classRef(FQ_XML_CONVERTER, "cls", converterType)
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "value", strType, false, false, strType, null, ann, "handle-value");
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag)
                .register(FakeTypeSymbol.builder("com.example.MyConverter")
                        .publicNoArgCtor(true)
                        .implementsParameterized("xmlfluss.Converter", strType)
                        .build());

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNotNull(result);
        assertEquals(1, result.fields().size());
        FieldSpec f = result.fields().get(0);
        assertInstanceOf(Coerce.Custom.class, f.coerce());
        Coerce.Custom custom = (Coerce.Custom) f.coerce();
        assertEquals("com.example.MyConverter", custom.converterFq());
        assertTrue(diag.entries.isEmpty());
    }

    // ------------------------------------------------------------------ @XmlAttr on BigDecimal with @XmlFormat

    @Test
    void xmlAttr_bigDecimalWithFormat_producesDecimalCoerce() {
        TypeRef bdType = TypeRef.of("java.math", "BigDecimal");
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .annotation(FQ_XML_ATTR)
                .string(FQ_XML_FORMAT, "pattern", "#,##0.00")
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "amount", bdType, false, false, bdType, null, ann, "handle-amount");
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNotNull(result);
        FieldSpec f = result.fields().get(0);
        assertInstanceOf(Coerce.Decimal.class, f.coerce());
        assertEquals("#,##0.00", ((Coerce.Decimal) f.coerce()).pattern());
        assertTrue(diag.entries.isEmpty());
    }

    // ------------------------------------------------------------------ component without @XmlAttr becomes implicit child

    @Test
    void componentWithoutAnnotation_becomesImplicitChild() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        // Component with no xml annotations — becomes an implicit child
        FakeAnnotationView emptyAnn = FakeAnnotationView.builder().build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "title", strType, false, false, strType, null, emptyAnn, "handle-title");
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNotNull(result);
        assertEquals(1, result.fields().size());
        FieldSpec f = result.fields().get(0);
        assertEquals("title", f.name());
        assertInstanceOf(Source.Child.class, f.source());
        Source.Child src = (Source.Child) f.source();
        assertFalse(src.descendant());
        assertEquals(1, src.segments().size());
        assertInstanceOf(PathSeg.Element.class, src.segments().get(0));
        PathSeg.Element elem = (PathSeg.Element) src.segments().get(0);
        assertNull(elem.ns());
        assertEquals("title", elem.name());
        assertInstanceOf(Coerce.AsString.class, f.coerce());
        assertTrue(diag.entries.isEmpty());
    }

    // ------------------------------------------------------------------ RecordSpec structure

    @Test
    void classify_populatesRecordSpecFields() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        FakeComponentSymbol c = attrComponent("code", strType, false);
        FakeRecordSymbol rec = new FakeRecordSymbol(
                "com.example", "CodeRecord", List.of(c),
                Map.of("p", "http://ns.example.com"),
                "/root",
                null, List.of(),
                FakeAnnotationView.builder().build(), "handle-CodeRecord");
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNotNull(result);
        assertEquals("com.example", result.packageName());
        assertEquals("CodeRecord", result.simpleName());
        assertEquals("/root", result.recordPath());
        assertEquals(Map.of("p", "http://ns.example.com"), result.nsMap());
        assertEquals("handle-CodeRecord", result.originatingHandle());
    }

    // ------------------------------------------------------------------ unbound prefix → error

    @Test
    void xmlAttr_unboundNamespacePrefix_reportsDiagnosticAndReturnsNull() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .string(FQ_XML_ATTR, "name", "unknown:attr")
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "attr", strType, false, false, strType, null, ann, "handle-attr");
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNull(result, "classify should return null when @XmlAttr field has an error");
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("unbound NS prefix")));
    }

    // ------------------------------------------------------------------ @XmlFormat + @XmlConverter → error

    @Test
    void xmlAttr_formatAndConverter_reportsDiagnosticAndReturnsNull() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        TypeRef converterType = TypeRef.of("com.example", "MyConverter");
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .annotation(FQ_XML_ATTR)
                .string(FQ_XML_FORMAT, "pattern", "yyyy-MM-dd")
                .classRef(FQ_XML_CONVERTER, "cls", converterType)
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "date", strType, false, false, strType, null, ann, "handle-date");
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNull(result, "classify should return null when @XmlAttr field has an error");
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("@XmlFormat and @XmlConverter are mutually exclusive")));
    }

    // ------------------------------------------------------------------ LocalDate with @XmlFormat

    @Test
    void xmlAttr_localDateWithFormat_producesTemporalCoerce() {
        TypeRef dateType = TypeRef.of("java.time", "LocalDate");
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .annotation(FQ_XML_ATTR)
                .string(FQ_XML_FORMAT, "pattern", "dd/MM/yyyy")
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "dob", dateType, false, false, dateType, null, ann, "handle-dob");
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNotNull(result);
        FieldSpec f = result.fields().get(0);
        assertInstanceOf(Coerce.Temporal.class, f.coerce());
        Coerce.Temporal temporal = (Coerce.Temporal) f.coerce();
        assertEquals(ScalarKind.LOCAL_DATE, temporal.kind());
        assertEquals("dd/MM/yyyy", temporal.pattern());
    }

    // ------------------------------------------------------------------ @XmlFormat on non-formattable → error

    @Test
    void xmlAttr_formatOnString_reportsDiagnosticAndReturnsNull() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .annotation(FQ_XML_ATTR)
                .string(FQ_XML_FORMAT, "pattern", "some-pattern")
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "name", strType, false, false, strType, null, ann, "handle-name");
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNull(result, "classify should return null when @XmlAttr field has an error");
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("@XmlFormat on") && e.message().contains("LocalDate")));
    }

    // ------------------------------------------------------------------ temporal default pattern ""

    @Test
    void xmlAttr_instantWithoutFormat_producesTemporalWithEmptyPattern() {
        TypeRef instantType = TypeRef.of("java.time", "Instant");
        FakeComponentSymbol c = attrComponent("ts", instantType, false);
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNotNull(result);
        FieldSpec f = result.fields().get(0);
        assertInstanceOf(Coerce.Temporal.class, f.coerce());
        assertEquals("", ((Coerce.Temporal) f.coerce()).pattern());
    }

    // ================================================================== @XmlChild tests

    private static final String FQ_XML_CHILD = CoreClassifier.FQ_XML_CHILD;

    /** Component with @XmlChild using the component name (no explicit path). */
    private static FakeComponentSymbol childComponent(String name, TypeRef type, boolean nullable) {
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .annotation(FQ_XML_CHILD)
                .build();
        return new FakeComponentSymbol(name, type, nullable, false, type, null, ann, "handle-" + name);
    }

    /** Component with @XmlChild and explicit path override. */
    private static FakeComponentSymbol childComponentWithPath(String name, String path,
                                                               TypeRef type, boolean nullable) {
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .string(FQ_XML_CHILD, "path", path)
                .build();
        return new FakeComponentSymbol(name, type, nullable, false, type, null, ann, "handle-" + name);
    }

    /** List component with @XmlChild. */
    private static FakeComponentSymbol childListComponent(String name, TypeRef elemType) {
        TypeRef listType = TypeRef.parameterized("java.util", "List", List.of(elemType));
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .annotation(FQ_XML_CHILD)
                .build();
        return new FakeComponentSymbol(name, listType, false, true, elemType, null, ann, "handle-" + name);
    }

    // ------------------------------------------------------------------ implicit child → String

    @Test
    void implicitChild_stringField_producesAsStringCoerce() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        FakeAnnotationView emptyAnn = FakeAnnotationView.builder().build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "title", strType, false, false, strType, null, emptyAnn, "handle-title");
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNotNull(result);
        assertEquals(1, result.fields().size());
        FieldSpec f = result.fields().get(0);
        assertEquals("title", f.name());
        assertTrue(f.required());
        assertFalse(f.isList());
        assertInstanceOf(Coerce.AsString.class, f.coerce());
        Source.Child src = assertInstanceOf(Source.Child.class, f.source());
        assertFalse(src.descendant());
        assertEquals(1, src.segments().size());
        PathSeg.Element seg = assertInstanceOf(PathSeg.Element.class, src.segments().get(0));
        assertNull(seg.ns());
        assertEquals("title", seg.name());
        assertTrue(diag.entries.isEmpty());
    }

    // ------------------------------------------------------------------ @XmlChild explicit name

    @Test
    void xmlChild_explicitPath_usesAnnotationPath() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        FakeComponentSymbol c = childComponentWithPath("text", "body", strType, false);
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNotNull(result);
        FieldSpec f = result.fields().get(0);
        assertEquals("text", f.name());
        Source.Child src = assertInstanceOf(Source.Child.class, f.source());
        PathSeg.Element seg = assertInstanceOf(PathSeg.Element.class, src.segments().get(0));
        assertEquals("body", seg.name());
        assertTrue(diag.entries.isEmpty());
    }

    // ------------------------------------------------------------------ List<String> @XmlChild

    @Test
    void xmlChild_listOfString_producesListFieldWithAsStringCoerce() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        FakeComponentSymbol c = childListComponent("tags", strType);
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNotNull(result);
        assertEquals(1, result.fields().size());
        FieldSpec f = result.fields().get(0);
        assertEquals("tags", f.name());
        assertTrue(f.isList());
        assertInstanceOf(Coerce.AsString.class, f.coerce());
        // fieldType should be List<String>
        assertEquals("java.util.List", f.fieldType().qualifiedName());
        assertEquals(1, f.fieldType().typeArguments().size());
        assertEquals("java.lang.String", f.fieldType().typeArguments().get(0).qualifiedName());
        assertTrue(diag.entries.isEmpty());
    }

    // ------------------------------------------------------------------ nested record child

    @Test
    void xmlChild_nestedRecord_producesNestedCoerce() {
        // Build nested record
        TypeRef nestedType = TypeRef.of("com.example", "Author");
        FakeAnnotationView emptyAnn = FakeAnnotationView.builder().build();
        FakeComponentSymbol nestedC = new FakeComponentSymbol(
                "name", TypeRef.of("java.lang", "String"), false, false,
                TypeRef.of("java.lang", "String"), null, emptyAnn, "handle-name");
        FakeRecordSymbol nestedRecord = new FakeRecordSymbol(
                "com.example", "Author", List.of(nestedC),
                Map.of(), null, null, List.of(),
                FakeAnnotationView.builder().build(), "handle-Author");

        // Build parent record with nested child component
        FakeAnnotationView childAnn = FakeAnnotationView.builder()
                .annotation(FQ_XML_CHILD)
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "author", nestedType, false, false, nestedType, nestedRecord, childAnn,
                "handle-author");
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNotNull(result);
        assertEquals(1, result.fields().size());
        FieldSpec f = result.fields().get(0);
        assertEquals("author", f.name());
        assertFalse(f.isList());
        Coerce.Nested nested = assertInstanceOf(Coerce.Nested.class, f.coerce());
        assertEquals("com.example.Author", nested.typeFq());
        assertInstanceOf(Source.Child.class, f.source());
        assertTrue(diag.entries.isEmpty());
    }

    // ------------------------------------------------------------------ nested record list

    @Test
    void xmlChild_nestedRecordList_producesListField() {
        TypeRef authorType = TypeRef.of("com.example", "Author");
        FakeAnnotationView emptyAnn = FakeAnnotationView.builder().build();
        FakeComponentSymbol nestedC = new FakeComponentSymbol(
                "name", TypeRef.of("java.lang", "String"), false, false,
                TypeRef.of("java.lang", "String"), null, emptyAnn, "handle-name");
        FakeRecordSymbol nestedRecord = new FakeRecordSymbol(
                "com.example", "Author", List.of(nestedC),
                Map.of(), null, null, List.of(),
                FakeAnnotationView.builder().build(), "handle-Author");

        TypeRef listType = TypeRef.parameterized("java.util", "List", List.of(authorType));
        FakeAnnotationView childAnn = FakeAnnotationView.builder()
                .annotation(FQ_XML_CHILD)
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "authors", listType, false, true, authorType, nestedRecord, childAnn,
                "handle-authors");
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNotNull(result);
        FieldSpec f = result.fields().get(0);
        assertEquals("authors", f.name());
        assertTrue(f.isList());
        assertInstanceOf(Coerce.Nested.class, f.coerce());
        assertEquals("java.util.List", f.fieldType().qualifiedName());
        assertEquals(1, f.fieldType().typeArguments().size());
        assertEquals("com.example.Author", f.fieldType().typeArguments().get(0).qualifiedName());
        assertTrue(diag.entries.isEmpty());
    }

    // ------------------------------------------------------------------ nested record null propagates to parent

    @Test
    void xmlChild_nestedRecordReturnsNull_propagatesToParent() {
        // When a nested record's classification fails, the parent classify() must also return null.
        // Note: genuine A->B->A cycle detection via ensureNested.isInProgress is exercised only when
        // SPI fakes support mutable back-references; the immutable FakeComponentSymbol/FakeRecordSymbol
        // used here cannot wire up a real cycle. Instead, we test propagation by making the nested
        // record contain an unsupported field type, which causes classifyOne(nested) to return null.
        TypeRef typeB = TypeRef.of("com.example", "B");

        FakeAnnotationView emptyAnn = FakeAnnotationView.builder().build();
        FakeAnnotationView childAnn = FakeAnnotationView.builder()
                .annotation(FQ_XML_CHILD)
                .build();

        // B has an unsupported field type → classify(B) returns null
        FakeComponentSymbol bComponentUnsupported = new FakeComponentSymbol(
                "x", TypeRef.of("com.example", "Unknown"), false, false,
                TypeRef.of("com.example", "Unknown"), null, emptyAnn, "handle-B.x");
        FakeRecordSymbol recB = new FakeRecordSymbol(
                "com.example", "B", List.of(bComponentUnsupported),
                Map.of(), null, null, List.of(),
                emptyAnn, "handle-B");

        FakeComponentSymbol aComponentB = new FakeComponentSymbol(
                "b", typeB, false, false, typeB, recB, childAnn, "handle-A.b");
        FakeRecordSymbol recA = new FakeRecordSymbol(
                "com.example", "A", List.of(aComponentB),
                Map.of(), null, null, List.of(),
                emptyAnn, "handle-A");

        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(recA);

        assertNull(result, "classify should return null when nested record has an error");
        assertFalse(diag.entries.isEmpty(), "expected a diagnostic");
    }

    // ------------------------------------------------------------------ @XmlChild with namespace prefix

    @Test
    void xmlChild_withNamespacePrefix_resolvesCorrectly() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .string(FQ_XML_CHILD, "path", "ns:item")
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "item", strType, false, false, strType, null, ann, "handle-item");
        FakeRecordSymbol rec = recordWith(
                List.of(c),
                Map.of("ns", "http://example.com/ns"));
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNotNull(result);
        FieldSpec f = result.fields().get(0);
        Source.Child src = assertInstanceOf(Source.Child.class, f.source());
        PathSeg.Element seg = assertInstanceOf(PathSeg.Element.class, src.segments().get(0));
        assertEquals("http://example.com/ns", seg.ns());
        assertEquals("item", seg.name());
        assertTrue(diag.entries.isEmpty());
    }

    // ------------------------------------------------------------------ List<List<T>> → error

    @Test
    void xmlChild_listOfList_reportsDiagnosticAndReturnsNull() {
        TypeRef innerListType = TypeRef.parameterized("java.util", "List",
                List.of(TypeRef.of("java.lang", "String")));
        TypeRef outerListType = TypeRef.parameterized("java.util", "List",
                List.of(innerListType));
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .annotation(FQ_XML_CHILD)
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "nested", outerListType, false, true, innerListType, null, ann, "handle-nested");
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNull(result, "classify should return null for List<List<T>>");
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("List<List<T>> is not supported")));
    }

    // ------------------------------------------------------------------ descendant path "//items/item"

    @Test
    void xmlChild_descendantPath_parsesCorrectly() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .string(FQ_XML_CHILD, "path", "//items/item")
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "content", strType, false, false, strType, null, ann, "handle-content");
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNotNull(result);
        FieldSpec f = result.fields().get(0);
        Source.Child src = assertInstanceOf(Source.Child.class, f.source());
        assertTrue(src.descendant(), "expected descendant=true for '//' path");
        assertEquals(2, src.segments().size());
        PathSeg.Element first = assertInstanceOf(PathSeg.Element.class, src.segments().get(0));
        assertEquals("items", first.name());
        assertNull(first.ns());
        PathSeg.Element second = assertInstanceOf(PathSeg.Element.class, src.segments().get(1));
        assertEquals("item", second.name());
        assertNull(second.ns());
        assertTrue(diag.entries.isEmpty());
    }

    // ------------------------------------------------------------------ @XmlChild implicit name no annotation

    @Test
    void xmlChild_noAnnotation_usesComponentNameAsImplicitPath() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        FakeAnnotationView emptyAnn = FakeAnnotationView.builder().build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "description", strType, false, false, strType, null, emptyAnn, "handle-description");
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNotNull(result);
        FieldSpec f = result.fields().get(0);
        Source.Child src = assertInstanceOf(Source.Child.class, f.source());
        PathSeg.Element seg = assertInstanceOf(PathSeg.Element.class, src.segments().get(0));
        assertEquals("description", seg.name());
        assertFalse(src.descendant());
        assertTrue(diag.entries.isEmpty());
    }

    // ------------------------------------------------------------------ both @XmlAttr and implicit child co-exist

    @Test
    void record_withAttrAndImplicitChild_classifiesBothFields() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        FakeComponentSymbol attrComp = attrComponent("id", strType, false);
        FakeAnnotationView emptyAnn = FakeAnnotationView.builder().build();
        FakeComponentSymbol childComp = new FakeComponentSymbol(
                "body", strType, false, false, strType, null, emptyAnn, "handle-body");
        FakeRecordSymbol rec = simpleRecord(attrComp, childComp);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNotNull(result);
        assertEquals(2, result.fields().size());
        // First field: @XmlAttr
        FieldSpec attrField = result.fields().get(0);
        assertInstanceOf(Source.Attr.class, attrField.source());
        assertEquals("id", attrField.name());
        // Second field: implicit child
        FieldSpec childField = result.fields().get(1);
        assertInstanceOf(Source.Child.class, childField.source());
        assertEquals("body", childField.name());
        assertTrue(diag.entries.isEmpty());
    }

    // ------------------------------------------------------------------ List<Optional<T>> → error

    @Test
    void xmlChild_listOfOptional_reportsDiagnosticAndReturnsNull() {
        TypeRef optionalType = TypeRef.parameterized("java.util", "Optional",
                List.of(TypeRef.of("java.lang", "String")));
        TypeRef listType = TypeRef.parameterized("java.util", "List",
                List.of(optionalType));
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .annotation(FQ_XML_CHILD)
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "items", listType, false, true, optionalType, null, ann, "handle-items");
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNull(result, "classify should return null for List<Optional<T>>");
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("List<Optional<T>> is not supported")));
    }

    // ------------------------------------------------------------------ @XmlFormat on nested record → error

    @Test
    void xmlChild_xmlFormatOnNestedRecord_reportsDiagnosticAndReturnsNull() {
        // Build nested record
        TypeRef nestedType = TypeRef.of("com.example", "Author");
        FakeAnnotationView emptyAnn = FakeAnnotationView.builder().build();
        FakeComponentSymbol nestedC = new FakeComponentSymbol(
                "name", TypeRef.of("java.lang", "String"), false, false,
                TypeRef.of("java.lang", "String"), null, emptyAnn, "handle-name");
        FakeRecordSymbol nestedRecord = new FakeRecordSymbol(
                "com.example", "Author", List.of(nestedC),
                Map.of(), null, null, List.of(),
                FakeAnnotationView.builder().build(), "handle-Author");

        // Build parent record with component that has @XmlFormat on nested record
        FakeAnnotationView childAnnWithFormat = FakeAnnotationView.builder()
                .annotation(FQ_XML_CHILD)
                .string(FQ_XML_FORMAT, "pattern", "some-pattern")
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "author", nestedType, false, false, nestedType, nestedRecord, childAnnWithFormat,
                "handle-author");
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNull(result, "classify should return null when @XmlFormat is on a nested record");
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("has no effect on a nested record")));
    }

    // ------------------------------------------------------------------ multi-segment direct-axis path

    @Test
    void xmlChild_multiSegmentDirectPath_producesCorrectSegments() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        FakeComponentSymbol c = childComponentWithPath("leaf", "wrapper/leaf", strType, false);
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNotNull(result);
        FieldSpec f = result.fields().get(0);
        Source.Child src = assertInstanceOf(Source.Child.class, f.source());
        assertFalse(src.descendant());
        assertEquals(2, src.segments().size());
        PathSeg.Element first = assertInstanceOf(PathSeg.Element.class, src.segments().get(0));
        assertNull(first.ns());
        assertEquals("wrapper", first.name());
        PathSeg.Element second = assertInstanceOf(PathSeg.Element.class, src.segments().get(1));
        assertNull(second.ns());
        assertEquals("leaf", second.name());
        assertTrue(diag.entries.isEmpty());
    }

    // ------------------------------------------------------------------ AttrLeaf path "parent/@id"

    @Test
    void xmlChild_attrLeafPath_producesAttrLeafSegment() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        FakeComponentSymbol c = childComponentWithPath("id", "parent/@id", strType, false);
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNotNull(result);
        FieldSpec f = result.fields().get(0);
        Source.Child src = assertInstanceOf(Source.Child.class, f.source());
        assertFalse(src.descendant());
        assertEquals(2, src.segments().size());
        PathSeg.Element elem = assertInstanceOf(PathSeg.Element.class, src.segments().get(0));
        assertNull(elem.ns());
        assertEquals("parent", elem.name());
        PathSeg.AttrLeaf attr = assertInstanceOf(PathSeg.AttrLeaf.class, src.segments().get(1));
        assertNull(attr.ns());
        assertEquals("id", attr.name());
        assertTrue(diag.entries.isEmpty());
    }

    // ------------------------------------------------------------------ AttrLeaf at index 0 → error

    @Test
    void xmlChild_attrLeafAtIndex0_reportsDiagnosticAndReturnsNull() {
        // "@id" alone in @XmlChild — original rejects this: use @XmlAttr for record-level attributes
        TypeRef strType = TypeRef.of("java.lang", "String");
        FakeComponentSymbol c = childComponentWithPath("id", "@id", strType, false);
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNull(result, "classify should return null when AttrLeaf is at index 0");
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("use @XmlAttr for record-level attributes")));
    }

    // ------------------------------------------------------------------ AttrLeaf in middle → error

    @Test
    void xmlChild_attrLeafInMiddle_reportsDiagnosticAndReturnsNull() {
        // "a/@x/b" — PathParser itself rejects this: attribute must be the last step
        TypeRef strType = TypeRef.of("java.lang", "String");
        FakeComponentSymbol c = childComponentWithPath("x", "a/@x/b", strType, false);
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNull(result, "classify should return null when AttrLeaf is not the last segment");
        // The PathParser throws "attr must be last step in '...'" before CoreClassifier's own check
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("attr must be last step")));
    }

    // ------------------------------------------------------------------ @XmlAttr + @XmlChild → mutual exclusion error

    @Test
    void xmlAttrAndXmlChild_mutuallyExclusive_reportsDiagnosticAndReturnsNull() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        // A component with both @XmlAttr and @XmlChild — mutually exclusive
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .annotation(FQ_XML_ATTR)
                .annotation(FQ_XML_CHILD)
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "field", strType, false, false, strType, null, ann, "handle-field");
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNull(result, "classify should return null when @XmlAttr and @XmlChild both present");
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("@XmlAttr / @XmlChild / @XmlText / @XmlMap are mutually exclusive")));
    }

    // ------------------------------------------------------------------ @XmlFormat + @XmlConverter at dispatch level → error

    @Test
    void xmlChild_formatAndConverter_atDispatchLevel_reportsDiagnosticAndReturnsNull() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        TypeRef converterType = TypeRef.of("com.example", "MyConverter");
        // No binding annotation (implicit child) + @XmlFormat + @XmlConverter
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .annotation(FQ_XML_CHILD)
                .string(FQ_XML_FORMAT, "pattern", "yyyy-MM-dd")
                .classRef(FQ_XML_CONVERTER, "cls", converterType)
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "val", strType, false, false, strType, null, ann, "handle-val");
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNull(result, "classify should return null when @XmlFormat and @XmlConverter both present");
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("@XmlFormat and @XmlConverter are mutually exclusive")));
    }

    // ================================================================== @XmlText tests

    private static final String FQ_XML_TEXT = CoreClassifier.FQ_XML_TEXT;
    private static final String FQ_XML_MAP  = CoreClassifier.FQ_XML_MAP;

    /** Component with @XmlText (no preserveWhitespace override). */
    private static FakeComponentSymbol textComponent(String name, TypeRef type, boolean nullable) {
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .annotation(FQ_XML_TEXT)
                .build();
        return new FakeComponentSymbol(name, type, nullable, false, type, null, ann, "handle-" + name);
    }

    // ------------------------------------------------------------------ simple String @XmlText

    @Test
    void xmlText_string_producesTextSourceAndAsStringCoerce() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        FakeComponentSymbol c = textComponent("content", strType, false);
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNotNull(result, "should classify successfully");
        assertEquals(1, result.fields().size());
        FieldSpec f = result.fields().get(0);
        assertEquals("content", f.name());
        assertTrue(f.required());
        assertFalse(f.isList());
        assertInstanceOf(Coerce.AsString.class, f.coerce());
        Source.Text src = assertInstanceOf(Source.Text.class, f.source());
        assertFalse(src.preserveWhitespace());
        assertTrue(diag.entries.isEmpty());
    }

    // ------------------------------------------------------------------ @XmlText with preserveWhitespace=true

    @Test
    void xmlText_preserveWhitespace_setsFlag() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .bool(FQ_XML_TEXT, "preserveWhitespace", true)
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "body", strType, false, false, strType, null, ann, "handle-body");
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNotNull(result);
        FieldSpec f = result.fields().get(0);
        Source.Text src = assertInstanceOf(Source.Text.class, f.source());
        assertTrue(src.preserveWhitespace());
        assertTrue(diag.entries.isEmpty());
    }

    // ------------------------------------------------------------------ @XmlText with primitive int

    @Test
    void xmlText_primitiveInt_producesScalarCoerce() {
        TypeRef intType = TypeRef.ofPrimitive("int");
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .annotation(FQ_XML_TEXT)
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "count", intType, false, false, intType, null, ann, "handle-count");
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNotNull(result);
        FieldSpec f = result.fields().get(0);
        assertInstanceOf(Coerce.Scalar.class, f.coerce());
        assertEquals(ScalarKind.INT, ((Coerce.Scalar) f.coerce()).kind());
        assertTrue(f.fieldType().primitive());
        assertTrue(f.required());
        assertTrue(diag.entries.isEmpty());
    }

    // ------------------------------------------------------------------ @XmlText on List → error

    @Test
    void xmlText_onList_reportsDiagnosticAndReturnsNull() {
        TypeRef listType = TypeRef.parameterized("java.util", "List",
                List.of(TypeRef.of("java.lang", "String")));
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .annotation(FQ_XML_TEXT)
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "items", listType, false, true, TypeRef.of("java.lang", "String"),
                null, ann, "handle-items");
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNull(result);
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("@XmlText is not supported on List")));
    }

    // ------------------------------------------------------------------ multiple @XmlText → error

    @Test
    void xmlText_multipleFields_reportsDiagnosticAndReturnsNull() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        FakeComponentSymbol c1 = textComponent("text1", strType, false);
        FakeComponentSymbol c2 = textComponent("text2", strType, false);
        FakeRecordSymbol rec = simpleRecord(c1, c2);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNull(result);
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("@XmlText may appear at most once per record")));
    }

    // ------------------------------------------------------------------ @XmlText on non-scalar → error

    @Test
    void xmlText_onNonScalarType_reportsDiagnosticAndReturnsNull() {
        TypeRef customType = TypeRef.of("com.example", "MyBean");
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .annotation(FQ_XML_TEXT)
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "bean", customType, false, false, customType, null, ann, "handle-bean");
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNull(result);
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("@XmlText requires a scalar type")));
    }

    // ------------------------------------------------------------------ @XmlText nullable → required=false

    @Test
    void xmlText_nullableString_requiredFalse() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        FakeComponentSymbol c = textComponent("body", strType, true);
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNotNull(result);
        FieldSpec f = result.fields().get(0);
        assertFalse(f.required());
        assertInstanceOf(Source.Text.class, f.source());
        assertTrue(diag.entries.isEmpty());
    }

    // ================================================================== @XmlMap tests

    // ------------------------------------------------------------------ basic @XmlMap with attr key and empty value

    @Test
    void xmlMap_attrKeyEmptyValue_producesMapEntry() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        TypeRef mapType = TypeRef.parameterized("java.util", "Map",
                List.of(strType, strType));
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .string(FQ_XML_MAP, "entry", "item")
                .string(FQ_XML_MAP, "key",   "@k")
                .string(FQ_XML_MAP, "value", "")
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "entries", mapType, false, false, mapType, null, ann, "handle-entries");
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNotNull(result, "should classify successfully");
        assertEquals(1, result.fields().size());
        FieldSpec f = result.fields().get(0);
        assertEquals("entries", f.name());
        assertInstanceOf(Coerce.MapAggregate.class, f.coerce());
        Source.MapEntry me = assertInstanceOf(Source.MapEntry.class, f.source());
        assertNull(me.entryNs());
        assertEquals("item", me.entryLocal());

        // Key field
        assertNotNull(f.mapKeyField());
        Source.Attr keySrc = assertInstanceOf(Source.Attr.class, f.mapKeyField().source());
        assertNull(keySrc.ns());
        assertEquals("k", keySrc.name());
        assertInstanceOf(Coerce.AsString.class, f.mapKeyField().coerce());

        // Value field — empty/"." path encoded as a Source.Child self-step (single "."
        // element segment). This unifies APT and KSP semantics post-PR4: scalar values
        // read the entry-element's text via the self-step, and nested-record values can
        // be wired through the same Child branch in the classifier.
        assertNotNull(f.mapValueField());
        Source.Child valSrc = assertInstanceOf(Source.Child.class, f.mapValueField().source());
        assertFalse(valSrc.descendant());
        assertEquals(1, valSrc.segments().size());
        xmlfluss.codegen.model.PathSeg.Element seg =
                (xmlfluss.codegen.model.PathSeg.Element) valSrc.segments().get(0);
        assertEquals(".", seg.name());
        assertInstanceOf(Coerce.AsString.class, f.mapValueField().coerce());

        assertTrue(diag.entries.isEmpty());
    }

    // ------------------------------------------------------------------ @XmlMap with namespaced entry

    @Test
    void xmlMap_namespacedEntry_resolvesPrefix() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        TypeRef mapType = TypeRef.parameterized("java.util", "Map",
                List.of(strType, strType));
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .string(FQ_XML_MAP, "entry", "ns:item")
                .string(FQ_XML_MAP, "key",   "@id")
                .string(FQ_XML_MAP, "value", "")
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "map", mapType, false, false, mapType, null, ann, "handle-map");
        FakeRecordSymbol rec = recordWith(
                List.of(c),
                Map.of("ns", "http://example.com/ns"));
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNotNull(result);
        FieldSpec f = result.fields().get(0);
        Source.MapEntry me = assertInstanceOf(Source.MapEntry.class, f.source());
        assertEquals("http://example.com/ns", me.entryNs());
        assertEquals("item", me.entryLocal());
        assertTrue(diag.entries.isEmpty());
    }

    // ------------------------------------------------------------------ @XmlMap missing required attrs → error

    @Test
    void xmlMap_missingKeyOrValue_reportsDiagnosticAndReturnsNull() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        TypeRef mapType = TypeRef.parameterized("java.util", "Map",
                List.of(strType, strType));
        // key is missing (null from annotation)
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .string(FQ_XML_MAP, "entry", "item")
                // no "key" attr → stringValue returns null
                .string(FQ_XML_MAP, "value", "")
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "map", mapType, false, false, mapType, null, ann, "handle-map");
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNull(result);
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("@XmlMap on") && e.message().contains("missing 'key' or 'value'")));
    }

    // ------------------------------------------------------------------ @XmlMap bad entry name → error

    @Test
    void xmlMap_badEntryName_reportsDiagnosticAndReturnsNull() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        TypeRef mapType = TypeRef.parameterized("java.util", "Map",
                List.of(strType, strType));
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .string(FQ_XML_MAP, "entry", "@bad/entry")
                .string(FQ_XML_MAP, "key",   "@k")
                .string(FQ_XML_MAP, "value", "")
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "map", mapType, false, false, mapType, null, ann, "handle-map");
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNull(result);
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("@XmlMap entry") && e.message().contains("must be a single element name")));
    }

    // ------------------------------------------------------------------ @XmlMap on non-Map field → error

    @Test
    void xmlMap_onNonMapField_reportsDiagnosticAndReturnsNull() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .string(FQ_XML_MAP, "entry", "item")
                .string(FQ_XML_MAP, "key",   "@k")
                .string(FQ_XML_MAP, "value", "")
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "notMap", strType, false, false, strType, null, ann, "handle-notMap");
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNull(result);
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("@XmlMap requires Map<K, V> type")));
    }

    // ------------------------------------------------------------------ Map<K,V> without @XmlMap → error

    @Test
    void mapWithoutXmlMap_reportsDiagnosticAndReturnsNull() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        TypeRef mapType = TypeRef.parameterized("java.util", "Map",
                List.of(strType, strType));
        // No @XmlMap annotation
        FakeAnnotationView ann = FakeAnnotationView.builder().build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "entries", mapType, false, false, mapType, null, ann, "handle-entries");
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNull(result);
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("Map<K, V> but lacks @XmlMap")));
    }

    // ------------------------------------------------------------------ @XmlMap with child-path key

    @Test
    void xmlMap_childPathKey_producesChildSource() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        TypeRef mapType = TypeRef.parameterized("java.util", "Map",
                List.of(strType, strType));
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .string(FQ_XML_MAP, "entry", "item")
                .string(FQ_XML_MAP, "key",   "name")
                .string(FQ_XML_MAP, "value", "")
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "entries", mapType, false, false, mapType, null, ann, "handle-entries");
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNotNull(result);
        FieldSpec f = result.fields().get(0);
        assertNotNull(f.mapKeyField());
        assertInstanceOf(Source.Child.class, f.mapKeyField().source());
        assertTrue(diag.entries.isEmpty());
    }

    // ------------------------------------------------------------------ @XmlMap + @XmlFormat rejected

    @Test
    void xmlMap_withFormat_reportsDiagnosticAndReturnsNull() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        TypeRef mapType = TypeRef.parameterized("java.util", "Map", List.of(strType, strType));
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .string(FQ_XML_MAP, "entry", "item")
                .string(FQ_XML_MAP, "key",   "@k")
                .string(FQ_XML_MAP, "value", "")
                .annotation(FQ_XML_FORMAT)
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "entries", mapType, false, false, mapType, null, ann, "handle-entries");
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNull(result);
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("@XmlFormat / @XmlConverter not supported on @XmlMap")));
    }

    // ------------------------------------------------------------------ @XmlMap + @XmlConverter rejected

    @Test
    void xmlMap_withConverter_reportsDiagnosticAndReturnsNull() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        TypeRef mapType = TypeRef.parameterized("java.util", "Map", List.of(strType, strType));
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .string(FQ_XML_MAP, "entry", "item")
                .string(FQ_XML_MAP, "key",   "@k")
                .string(FQ_XML_MAP, "value", "")
                .classRef(FQ_XML_CONVERTER, "cls", TypeRef.of("com.example", "SomeConverter"))
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "entries", mapType, false, false, mapType, null, ann, "handle-entries");
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNull(result);
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("@XmlFormat / @XmlConverter not supported on @XmlMap")));
    }

    // ------------------------------------------------------------------ @XmlMap with nested record value uses Coerce.Nested

    @Test
    void xmlMap_withNestedRecordValue_usesNestedCoerce() {
        TypeRef strType  = TypeRef.of("java.lang", "String");
        TypeRef nestedType = TypeRef.of("com.example", "NestedRec");

        FakeAnnotationView emptyAnn = FakeAnnotationView.builder().build();
        FakeComponentSymbol nestedField = new FakeComponentSymbol(
                "val", strType, false, false, strType, null, emptyAnn, "handle-NestedRec.val");
        FakeRecordSymbol nestedRecord = new FakeRecordSymbol(
                "com.example", "NestedRec", List.of(nestedField),
                Map.of(), null, null, List.of(),
                emptyAnn, "handle-NestedRec");

        TypeRef mapType = TypeRef.parameterized("java.util", "Map", List.of(strType, nestedType));
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .string(FQ_XML_MAP, "entry", "item")
                .string(FQ_XML_MAP, "key",   "@k")
                .string(FQ_XML_MAP, "value", "nested")
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "entries", mapType, false, false, mapType, null, ann, "handle-entries");
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);
        sp.register(nestedRecord);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNotNull(result, "should classify successfully");
        assertTrue(diag.entries.isEmpty());
        FieldSpec f = result.fields().get(0);
        assertNotNull(f.mapValueField());
        Coerce.Nested nestedCoerce = assertInstanceOf(Coerce.Nested.class, f.mapValueField().coerce());
        assertEquals("com.example.NestedRec", nestedCoerce.typeFq());
    }

    // ------------------------------------------------------------------ @XmlMap with List<String> value preserves List in outer type

    @Test
    void xmlMap_withListValue_preservesListInOuterType() {
        TypeRef strType  = TypeRef.of("java.lang", "String");
        TypeRef listOfStr = TypeRef.parameterized("java.util", "List", List.of(strType));
        TypeRef mapType  = TypeRef.parameterized("java.util", "Map", List.of(strType, listOfStr));

        FakeAnnotationView ann = FakeAnnotationView.builder()
                .string(FQ_XML_MAP, "entry", "item")
                .string(FQ_XML_MAP, "key",   "@k")
                .string(FQ_XML_MAP, "value", "v")
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "entries", mapType, false, false, mapType, null, ann, "handle-entries");
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNotNull(result, "should classify successfully");
        assertTrue(diag.entries.isEmpty());
        FieldSpec f = result.fields().get(0);
        // Outer Map type should be Map<String, List<String>>
        TypeRef outerType = f.fieldType();
        assertEquals("java.util.Map", outerType.qualifiedName());
        List<TypeRef> typeArgs = outerType.typeArguments();
        assertEquals(2, typeArgs.size());
        assertEquals("java.lang.String", typeArgs.get(0).qualifiedName());
        // Value param must be List<String>, not String
        assertEquals("java.util.List", typeArgs.get(1).qualifiedName());
        assertEquals(1, typeArgs.get(1).typeArguments().size());
        assertEquals("java.lang.String", typeArgs.get(1).typeArguments().get(0).qualifiedName());
    }

    // ------------------------------------------------------------------ namespace inheritance in nested record

    @Test
    void xmlChild_nestedRecord_inheritsParentNamespace() {
        // Parent declares @XmlNs("p", "http://parent"); nested record has path "p:item"
        // which should resolve via the inherited namespace map.
        TypeRef nestedType = TypeRef.of("com.example", "Inner");
        FakeAnnotationView emptyAnn = FakeAnnotationView.builder().build();

        // Nested record's component uses implicit child (no annotation), no own @XmlNs declarations
        FakeComponentSymbol innerField = new FakeComponentSymbol(
                "value", TypeRef.of("java.lang", "String"), false, false,
                TypeRef.of("java.lang", "String"), null, emptyAnn, "handle-inner.value");
        FakeRecordSymbol nestedRecord = new FakeRecordSymbol(
                "com.example", "Inner", List.of(innerField),
                Map.of(), // no own namespace declarations — inherits from parent
                null, null, List.of(),
                FakeAnnotationView.builder().build(), "handle-Inner");

        // Parent record has @XmlNs("p" -> "http://parent") and a child component with path "p:item"
        FakeAnnotationView childAnn = FakeAnnotationView.builder()
                .string(FQ_XML_CHILD, "path", "p:item")
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "inner", nestedType, false, false, nestedType, nestedRecord, childAnn,
                "handle-inner");
        FakeRecordSymbol parentRec = recordWith(
                List.of(c),
                Map.of("p", "http://parent"));
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(parentRec);

        assertNotNull(result, "classification should succeed with inherited namespace");
        assertTrue(diag.entries.isEmpty(), "expected no diagnostics");
        FieldSpec f = result.fields().get(0);
        Source.Child src = assertInstanceOf(Source.Child.class, f.source());
        PathSeg.Element seg = assertInstanceOf(PathSeg.Element.class, src.segments().get(0));
        assertEquals("http://parent", seg.ns());
        assertEquals("item", seg.name());
    }

    // ================================================================== @XmlPolymorphic tests

    private static final String FQ_XML_POLYMORPHIC = CoreClassifier.FQ_XML_POLYMORPHIC;
    private static final String FQ_XML_SUBTYPE     = CoreClassifier.FQ_XML_SUBTYPE;

    /**
     * Builds a FakeRecordSymbol representing a sealed polymorphic parent.
     *
     * @param fq             fully-qualified name of the parent ("com.example.Animal")
     * @param discriminator  empty string for Tag mode; "@attr-name" for Attr mode
     * @param subtypes       the sealed subtypes
     */
    private static FakeRecordSymbol polyParent(String fq, String discriminator,
                                               List<FakeRecordSymbol> subtypes) {
        int dot = fq.lastIndexOf('.');
        String pkg = dot >= 0 ? fq.substring(0, dot) : "";
        String simple = dot >= 0 ? fq.substring(dot + 1) : fq;
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .string(FQ_XML_POLYMORPHIC, "discriminator", discriminator)
                .build();
        return new FakeRecordSymbol(
                pkg, simple,
                List.of(), // no components on the sealed parent itself
                Map.of(), null, null,
                List.copyOf(subtypes),
                ann, "handle-" + simple);
    }

    /**
     * Builds a FakeRecordSymbol representing an @XmlSubtype concrete record.
     *
     * @param fq    FQN of the subtype ("com.example.Cat")
     * @param name  value of @XmlSubtype.name (e.g. "cat" or the attr discriminator value)
     */
    private static FakeRecordSymbol subtype(String fq, String name) {
        int dot = fq.lastIndexOf('.');
        String pkg = dot >= 0 ? fq.substring(0, dot) : "";
        String simple = dot >= 0 ? fq.substring(dot + 1) : fq;
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .string(FQ_XML_SUBTYPE, "name", name)
                .build();
        return new FakeRecordSymbol(
                pkg, simple,
                List.of(), Map.of(), null, null, List.of(),
                ann, "handle-" + simple);
    }

    /**
     * Builds the owning record with a polymorphic field named {@code fieldName} typed as
     * {@code parentFq}.  The component carries {@code @XmlChild} (optionally with a path).
     */
    /** @param path null or empty for @XmlChild with no path; non-empty for an explicit path. */
    private static FakeComponentSymbol polyChildComponent(String fieldName, FakeRecordSymbol parent,
                                                           String path) {
        TypeRef parentType = TypeRef.of(parent.packageName(), parent.simpleName());
        FakeAnnotationView.Builder b = FakeAnnotationView.builder();
        if (path == null || path.isEmpty()) {
            b.annotation(FQ_XML_CHILD);
        } else {
            b.string(FQ_XML_CHILD, "path", path);
        }
        FakeAnnotationView ann = b.build();
        return new FakeComponentSymbol(
                fieldName, parentType, false, false, parentType, null, ann,
                "handle-" + fieldName);
    }

    // ------------------------------------------------------------------ Tag-mode polymorphic (happy path)

    @Test
    void xmlPolymorphic_tagMode_producesPolyChildWithTagDispatch() {
        FakeRecordSymbol cat = subtype("com.example.Cat", "cat");
        FakeRecordSymbol dog = subtype("com.example.Dog", "dog");
        FakeRecordSymbol animal = polyParent("com.example.Animal", "", List.of(cat, dog));

        FakeComponentSymbol petField = polyChildComponent("pet", animal, null);
        FakeRecordSymbol owner = simpleRecord(petField);

        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);
        sp.register(animal);
        sp.register(cat);
        sp.register(dog);

        RecordSpec result = new CoreClassifier(sp).classify(owner);

        assertNotNull(result, diag.entries::toString);
        assertTrue(diag.entries.isEmpty(), diag.entries::toString);
        assertEquals(1, result.fields().size());
        FieldSpec f = result.fields().get(0);
        assertEquals("pet", f.name());
        assertTrue(f.required());
        assertFalse(f.isList());
        assertEquals("com.example.Animal", f.elemTypeFq());

        Source.PolyChild src = assertInstanceOf(Source.PolyChild.class, f.source());
        PolyDispatch.Tag tag = assertInstanceOf(PolyDispatch.Tag.class, src.dispatch());
        assertEquals(2, tag.variants().size());

        TagVariant catV = tag.variants().get(0);
        assertNull(catV.ns());
        assertEquals("cat", catV.local());
        assertEquals("com.example.Cat", catV.subtypeFq());

        TagVariant dogV = tag.variants().get(1);
        assertNull(dogV.ns());
        assertEquals("dog", dogV.local());
        assertEquals("com.example.Dog", dogV.subtypeFq());

        assertInstanceOf(Coerce.Nested.class, f.coerce());
        assertEquals("com.example.Animal", ((Coerce.Nested) f.coerce()).typeFq());
    }

    // ------------------------------------------------------------------ Attr-mode polymorphic (happy path)

    @Test
    void xmlPolymorphic_attrMode_producesPolyChildWithAttrDispatch() {
        FakeRecordSymbol cat = subtype("com.example.Cat", "cat");
        FakeRecordSymbol dog = subtype("com.example.Dog", "dog");
        FakeRecordSymbol animal = polyParent("com.example.Animal", "@kind", List.of(cat, dog));

        FakeComponentSymbol petField = polyChildComponent("pet", animal, "entry");
        FakeRecordSymbol owner = simpleRecord(petField);

        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);
        sp.register(animal);
        sp.register(cat);
        sp.register(dog);

        RecordSpec result = new CoreClassifier(sp).classify(owner);

        assertNotNull(result, diag.entries::toString);
        assertTrue(diag.entries.isEmpty(), diag.entries::toString);
        FieldSpec f = result.fields().get(0);

        Source.PolyChild src = assertInstanceOf(Source.PolyChild.class, f.source());
        PolyDispatch.Attr attr = assertInstanceOf(PolyDispatch.Attr.class, src.dispatch());

        assertNull(attr.wrapNs());
        assertEquals("entry", attr.wrapLocal());
        assertNull(attr.attrNs());
        assertEquals("kind", attr.attrLocal());
        assertEquals(2, attr.variants().size());

        AttrVariant catV = attr.variants().get(0);
        assertEquals("cat", catV.value());
        assertEquals("com.example.Cat", catV.subtypeFq());

        AttrVariant dogV = attr.variants().get(1);
        assertEquals("dog", dogV.value());
        assertEquals("com.example.Dog", dogV.subtypeFq());
    }

    // ------------------------------------------------------------------ polymorphic + @XmlConverter → error

    @Test
    void xmlPolymorphic_withConverter_reportsDiagnosticAndReturnsNull() {
        FakeRecordSymbol cat = subtype("com.example.Cat", "cat");
        FakeRecordSymbol animal = polyParent("com.example.Animal", "", List.of(cat));

        TypeRef parentType = TypeRef.of("com.example", "Animal");
        TypeRef converterType = TypeRef.of("com.example", "AnimalConverter");
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .annotation(FQ_XML_CHILD)
                .classRef(FQ_XML_CONVERTER, "cls", converterType)
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "pet", parentType, false, false, parentType, null, ann, "handle-pet");
        FakeRecordSymbol owner = simpleRecord(c);

        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);
        sp.register(animal);
        sp.register(cat);

        RecordSpec result = new CoreClassifier(sp).classify(owner);

        assertNull(result, "should fail when @XmlConverter is on a polymorphic field");
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("@XmlConverter not supported on polymorphic field")));
    }

    // ------------------------------------------------------------------ polymorphic + @XmlFormat → error

    @Test
    void xmlPolymorphic_withFormat_reportsDiagnosticAndReturnsNull() {
        FakeRecordSymbol cat = subtype("com.example.Cat", "cat");
        FakeRecordSymbol animal = polyParent("com.example.Animal", "", List.of(cat));

        TypeRef parentType = TypeRef.of("com.example", "Animal");
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .annotation(FQ_XML_CHILD)
                .string(FQ_XML_FORMAT, "pattern", "yyyy-MM-dd")
                .build();
        FakeComponentSymbol c = new FakeComponentSymbol(
                "pet", parentType, false, false, parentType, null, ann, "handle-pet");
        FakeRecordSymbol owner = simpleRecord(c);

        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);
        sp.register(animal);
        sp.register(cat);

        RecordSpec result = new CoreClassifier(sp).classify(owner);

        assertNull(result, "should fail when @XmlFormat is on a polymorphic field");
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("@XmlFormat not supported on polymorphic field")));
    }

    // ------------------------------------------------------------------ Tag-mode with non-empty path → error

    @Test
    void xmlPolymorphic_tagMode_withPath_reportsDiagnosticAndReturnsNull() {
        FakeRecordSymbol cat = subtype("com.example.Cat", "cat");
        FakeRecordSymbol animal = polyParent("com.example.Animal", "", List.of(cat));

        FakeComponentSymbol c = polyChildComponent("pet", animal, "wrapper");
        FakeRecordSymbol owner = simpleRecord(c);

        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);
        sp.register(animal);
        sp.register(cat);

        RecordSpec result = new CoreClassifier(sp).classify(owner);

        assertNull(result, "tag-mode with non-empty path should fail");
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("tag-mode @XmlChild path must be empty")));
    }

    // ------------------------------------------------------------------ Attr-mode with empty path → error

    @Test
    void xmlPolymorphic_attrMode_withoutPath_reportsDiagnosticAndReturnsNull() {
        FakeRecordSymbol cat = subtype("com.example.Cat", "cat");
        FakeRecordSymbol animal = polyParent("com.example.Animal", "@kind", List.of(cat));

        // No path on @XmlChild
        FakeComponentSymbol c = polyChildComponent("pet", animal, null);
        FakeRecordSymbol owner = simpleRecord(c);

        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);
        sp.register(animal);
        sp.register(cat);

        RecordSpec result = new CoreClassifier(sp).classify(owner);

        assertNull(result, "attr-mode without wrap path should fail");
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("attr-mode @XmlChild requires the wrapping element path")));
    }

    // ------------------------------------------------------------------ Bad discriminator (no '@') → error

    @Test
    void xmlPolymorphic_badDiscriminator_reportsDiagnosticAndReturnsNull() {
        FakeRecordSymbol cat = subtype("com.example.Cat", "cat");
        FakeRecordSymbol animal = polyParent("com.example.Animal", "kind", List.of(cat));

        FakeComponentSymbol c = polyChildComponent("pet", animal, "entry");
        FakeRecordSymbol owner = simpleRecord(c);

        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);
        sp.register(animal);
        sp.register(cat);

        RecordSpec result = new CoreClassifier(sp).classify(owner);

        assertNull(result, "discriminator without leading '@' should fail");
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("@XmlPolymorphic.discriminator must start with '@'")));
    }

    // ------------------------------------------------------------------ Duplicate subtype tag → error

    @Test
    void xmlPolymorphic_tagMode_duplicateTag_reportsDiagnosticAndReturnsNull() {
        // Both Cat and Dog declare @XmlSubtype.name = "animal" → duplicate
        FakeRecordSymbol cat = subtype("com.example.Cat", "animal");
        FakeRecordSymbol dog = subtype("com.example.Dog", "animal");
        FakeRecordSymbol animal = polyParent("com.example.Animal", "", List.of(cat, dog));

        FakeComponentSymbol c = polyChildComponent("pet", animal, null);
        FakeRecordSymbol owner = simpleRecord(c);

        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);
        sp.register(animal);
        sp.register(cat);
        sp.register(dog);

        RecordSpec result = new CoreClassifier(sp).classify(owner);

        assertNull(result, "duplicate subtype tags should fail");
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("duplicate @XmlSubtype tag")));
    }

    // ------------------------------------------------------------------ Missing @XmlSubtype → error

    @Test
    void xmlPolymorphic_missingSubtypeAnnotation_reportsDiagnosticAndReturnsNull() {
        // Cat has no @XmlSubtype
        FakeRecordSymbol cat = new FakeRecordSymbol(
                "com.example", "Cat", List.of(), Map.of(), null, null, List.of(),
                FakeAnnotationView.builder().build(), // no annotations at all
                "handle-Cat");
        FakeRecordSymbol animal = polyParent("com.example.Animal", "", List.of(cat));

        FakeComponentSymbol c = polyChildComponent("pet", animal, null);
        FakeRecordSymbol owner = simpleRecord(c);

        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);
        sp.register(animal);
        sp.register(cat);

        RecordSpec result = new CoreClassifier(sp).classify(owner);

        assertNull(result, "missing @XmlSubtype should fail");
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("is missing @XmlSubtype")));
    }

    // ================================================================== trie / predicate validation

    @Test
    void validateChildPaths_twoFieldsSamePath_reportsCollisionAndReturnsNull() {
        // Two @XmlChild fields targeting the same single-segment path.  Both end up at the
        // same trie node as non-list text entries, which trips the "multiple non-list text
        // fields" branch of validateTrie.
        TypeRef strType = TypeRef.of("java.lang", "String");
        FakeComponentSymbol a = childComponentWithPath("a", "x", strType, false);
        FakeComponentSymbol b = childComponentWithPath("b", "x", strType, false);
        FakeRecordSymbol rec = simpleRecord(a, b);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNull(result, "classify should return null when two child paths collide");
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("multiple non-list text fields [a,b] target same element")),
                "expected trie-collision diagnostic, got: " + diag.entries);
    }

    @Test
    void validateChildPaths_directVsDescendantSameHead_reportsCollisionAndReturnsNull() {
        // One field at "a/b" and one at "//a/b".  The direct field's head is "a"; the
        // descendant field's head is "a" too — they collide via the descendant-vs-direct
        // head check.
        TypeRef strType = TypeRef.of("java.lang", "String");
        FakeComponentSymbol direct = childComponentWithPath("direct", "a/b", strType, false);
        FakeComponentSymbol desc   = childComponentWithPath("desc",   "//a/b", strType, false);
        FakeRecordSymbol rec = simpleRecord(direct, desc);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNull(result, "classify should return null on direct/descendant head collision");
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("@XmlChild('a') and @XmlChild('//a') target the same head element 'a'; pick one")),
                "expected head-collision diagnostic, got: " + diag.entries);
    }

    @Test
    void validateChildPaths_indexPredicateOnDescendantHead_reportsDiagnosticAndReturnsNull() {
        // Positional predicate [N] is not allowed on the descendant-axis head segment.
        TypeRef strType = TypeRef.of("java.lang", "String");
        FakeComponentSymbol c = childComponentWithPath("first", "//item[1]", strType, false);
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNull(result, "classify should return null when [N] sits on descendant head");
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("positional predicate [1] is not supported on the descendant-axis segment")),
                "expected descendant-head-positional diagnostic, got: " + diag.entries);
    }

    @Test
    void validateChildPaths_multipleIndexBracketsOnSegment_reportsDiagnosticAndReturnsNull() {
        // Two positional predicates on the same segment should be rejected.
        TypeRef strType = TypeRef.of("java.lang", "String");
        FakeComponentSymbol c = childComponentWithPath("first", "item[1][2]", strType, false);
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNull(result, "classify should return null when a segment carries two index predicates");
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("only one positional predicate is allowed per segment")),
                "expected multi-index diagnostic, got: " + diag.entries);
    }

    // ================================================================== @XmlConverter validation lift (PR 4)

    private static FakeComponentSymbol attrWithConverter(String name, TypeRef type, boolean nullable,
                                                         TypeRef converterRef) {
        FakeAnnotationView ann = FakeAnnotationView.builder()
                .annotation(FQ_XML_ATTR)
                .classRef(FQ_XML_CONVERTER, "cls", converterRef)
                .build();
        return new FakeComponentSymbol(name, type, nullable, false, type, null, ann, "handle-" + name);
    }

    @Test
    void xmlConverter_validStringConverter_producesCustomCoerce() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        TypeRef converterRef = TypeRef.of("com.example", "MyConverter");
        FakeComponentSymbol c = attrWithConverter("value", strType, false, converterRef);
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag)
                .register(FakeTypeSymbol.builder("com.example.MyConverter")
                        .publicNoArgCtor(true)
                        .implementsParameterized("xmlfluss.Converter", strType)
                        .build());

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNotNull(result);
        FieldSpec f = result.fields().get(0);
        assertInstanceOf(Coerce.Custom.class, f.coerce());
        assertEquals("com.example.MyConverter", ((Coerce.Custom) f.coerce()).converterFq());
        assertTrue(diag.entries.isEmpty());
    }

    @Test
    void xmlConverter_integerConverterOnPrimitiveInt_acceptsBoxingMatch() {
        TypeRef intType = TypeRef.ofPrimitive("int");
        TypeRef integerBoxed = TypeRef.of("java.lang", "Integer");
        TypeRef converterRef = TypeRef.of("com.example", "IntC");
        FakeComponentSymbol c = attrWithConverter("count", intType, false, converterRef);
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag)
                .register(FakeTypeSymbol.builder("com.example.IntC")
                        .publicNoArgCtor(true)
                        .implementsParameterized("xmlfluss.Converter", integerBoxed)
                        .build());

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNotNull(result, "Converter<Integer> should be accepted on a primitive int field via boxing");
        FieldSpec f = result.fields().get(0);
        assertInstanceOf(Coerce.Custom.class, f.coerce());
        assertTrue(diag.entries.isEmpty());
    }

    @Test
    void xmlConverter_missingPublicNoArgCtor_reportsDiagnostic() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        TypeRef converterRef = TypeRef.of("com.example", "PrivateC");
        FakeComponentSymbol c = attrWithConverter("value", strType, false, converterRef);
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag)
                .register(FakeTypeSymbol.builder("com.example.PrivateC")
                        .publicNoArgCtor(false)
                        .implementsParameterized("xmlfluss.Converter", strType)
                        .build());

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNull(result, "classify should fail when converter has no public no-arg ctor");
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("must have a public no-arg constructor")),
                "expected no-arg ctor diagnostic, got: " + diag.entries);
    }

    @Test
    void xmlConverter_doesNotImplementConverterInterface_reportsDiagnostic() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        TypeRef converterRef = TypeRef.of("com.example", "NotAConverter");
        FakeComponentSymbol c = attrWithConverter("value", strType, false, converterRef);
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag)
                .register(FakeTypeSymbol.builder("com.example.NotAConverter")
                        .publicNoArgCtor(true)
                        .build());

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNull(result, "classify should fail when type does not implement xmlfluss.Converter<T>");
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("does not implement xmlfluss.Converter<T>")),
                "expected Converter<T> diagnostic, got: " + diag.entries);
    }

    @Test
    void xmlConverter_typeArgNotAssignableToFieldType_reportsDiagnostic() {
        TypeRef longType = TypeRef.of("java.lang", "Long");
        TypeRef strType = TypeRef.of("java.lang", "String");
        TypeRef converterRef = TypeRef.of("com.example", "StrC");
        FakeComponentSymbol c = attrWithConverter("value", longType, false, converterRef);
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag)
                .register(FakeTypeSymbol.builder("com.example.StrC")
                        .publicNoArgCtor(true)
                        .implementsParameterized("xmlfluss.Converter", strType)
                        .build());

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNull(result, "classify should fail when Converter<String> is wired to a Long field");
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("not assignable to field type")),
                "expected assignability diagnostic, got: " + diag.entries);
    }

    @Test
    void xmlConverter_clsReferencesUnknownType_reportsDiagnostic() {
        TypeRef strType = TypeRef.of("java.lang", "String");
        TypeRef converterRef = TypeRef.of("com.example", "Missing");
        FakeComponentSymbol c = attrWithConverter("value", strType, false, converterRef);
        FakeRecordSymbol rec = simpleRecord(c);
        FakeDiagnosticReporter diag = new FakeDiagnosticReporter();
        FakeSymbolProvider sp = new FakeSymbolProvider(diag);
        // Note: 'com.example.Missing' is intentionally not registered.

        RecordSpec result = new CoreClassifier(sp).classify(rec);

        assertNull(result, "classify should fail when @XmlConverter cls is not resolvable");
        assertTrue(diag.entries.stream().anyMatch(e ->
                e.message().contains("could not be resolved")),
                "expected unresolved-type diagnostic, got: " + diag.entries);
    }
}
