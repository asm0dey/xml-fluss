package xmlfluss.codegen.classify;

import org.junit.jupiter.api.Test;
import xmlfluss.codegen.model.ScalarKind;
import xmlfluss.codegen.model.TypeRef;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static xmlfluss.codegen.classify.TypeRefClassification.*;

class TypeRefClassificationTest {

    // ------------------------------------------------------------------ isJavaUtilList

    @Test
    void isJavaUtilList_parameterizedList_returnsTrue() {
        TypeRef t = TypeRef.parameterized("java.util", "List",
                List.of(TypeRef.of("java.lang", "String")));
        assertTrue(isJavaUtilList(t));
    }

    @Test
    void isJavaUtilList_rawList_returnsTrue() {
        assertTrue(isJavaUtilList(TypeRef.of("java.util", "List")));
    }

    @Test
    void isJavaUtilList_string_returnsFalse() {
        assertFalse(isJavaUtilList(TypeRef.of("java.lang", "String")));
    }

    // ------------------------------------------------------------------ isJavaUtilMap

    @Test
    void isJavaUtilMap_mapType_returnsTrue() {
        TypeRef t = TypeRef.parameterized("java.util", "Map",
                List.of(TypeRef.of("java.lang", "String"), TypeRef.of("java.lang", "Integer")));
        assertTrue(isJavaUtilMap(t));
    }

    @Test
    void isJavaUtilMap_list_returnsFalse() {
        assertFalse(isJavaUtilMap(TypeRef.of("java.util", "List")));
    }

    // ------------------------------------------------------------------ isOptional

    @Test
    void isOptional_optionalType_returnsTrue() {
        TypeRef t = TypeRef.parameterized("java.util", "Optional",
                List.of(TypeRef.of("java.lang", "String")));
        assertTrue(isOptional(t));
    }

    @Test
    void isOptional_string_returnsFalse() {
        assertFalse(isOptional(TypeRef.of("java.lang", "String")));
    }

    // ------------------------------------------------------------------ isString

    @Test
    void isString_string_returnsTrue() {
        assertTrue(isString(TypeRef.of("java.lang", "String")));
    }

    @Test
    void isString_integer_returnsFalse() {
        assertFalse(isString(TypeRef.of("java.lang", "Integer")));
    }

    // ------------------------------------------------------------------ scalarKind

    @Test
    void scalarKind_primitiveInt_returnsInt() {
        assertEquals(ScalarKind.INT, scalarKind(TypeRef.ofPrimitive("int")));
    }

    @Test
    void scalarKind_primitiveLong_returnsLong() {
        assertEquals(ScalarKind.LONG, scalarKind(TypeRef.ofPrimitive("long")));
    }

    @Test
    void scalarKind_primitiveDouble_returnsDouble() {
        assertEquals(ScalarKind.DOUBLE, scalarKind(TypeRef.ofPrimitive("double")));
    }

    @Test
    void scalarKind_primitiveBoolean_returnsBoolean() {
        assertEquals(ScalarKind.BOOLEAN, scalarKind(TypeRef.ofPrimitive("boolean")));
    }

    @Test
    void scalarKind_boxedInteger_returnsInt() {
        assertEquals(ScalarKind.INT, scalarKind(TypeRef.of("java.lang", "Integer")));
    }

    @Test
    void scalarKind_string_returnsString() {
        assertEquals(ScalarKind.STRING, scalarKind(TypeRef.of("java.lang", "String")));
    }

    @Test
    void scalarKind_bigDecimal_returnsBigDecimal() {
        assertEquals(ScalarKind.BIG_DECIMAL, scalarKind(TypeRef.of("java.math", "BigDecimal")));
    }

    @Test
    void scalarKind_localDate_returnsLocalDate() {
        assertEquals(ScalarKind.LOCAL_DATE, scalarKind(TypeRef.of("java.time", "LocalDate")));
    }

    @Test
    void scalarKind_localDateTime_returnsLocalDateTime() {
        assertEquals(ScalarKind.LOCAL_DATE_TIME, scalarKind(TypeRef.of("java.time", "LocalDateTime")));
    }

    @Test
    void scalarKind_instant_returnsInstant() {
        assertEquals(ScalarKind.INSTANT, scalarKind(TypeRef.of("java.time", "Instant")));
    }

    @Test
    void scalarKind_unknownType_returnsNull() {
        assertNull(scalarKind(TypeRef.of("com.example", "MyRecord")));
    }

    // ------------------------------------------------------------------ isScalarOrTemporal

    @Test
    void isScalarOrTemporal_string_returnsTrue() {
        assertTrue(isScalarOrTemporal(TypeRef.of("java.lang", "String")));
    }

    @Test
    void isScalarOrTemporal_primitiveInt_returnsTrue() {
        assertTrue(isScalarOrTemporal(TypeRef.ofPrimitive("int")));
    }

    @Test
    void isScalarOrTemporal_arbitraryRecord_returnsFalse() {
        assertFalse(isScalarOrTemporal(TypeRef.of("com.example", "MyRecord")));
    }

    // ------------------------------------------------------------------ isFormattableType

    @Test
    void isFormattableType_bigDecimal_returnsTrue() {
        assertTrue(isFormattableType(TypeRef.of("java.math", "BigDecimal")));
    }

    @Test
    void isFormattableType_localDate_returnsTrue() {
        assertTrue(isFormattableType(TypeRef.of("java.time", "LocalDate")));
    }

    @Test
    void isFormattableType_localDateTime_returnsTrue() {
        assertTrue(isFormattableType(TypeRef.of("java.time", "LocalDateTime")));
    }

    @Test
    void isFormattableType_instant_returnsTrue() {
        assertTrue(isFormattableType(TypeRef.of("java.time", "Instant")));
    }

    @Test
    void isFormattableType_string_returnsFalse() {
        assertFalse(isFormattableType(TypeRef.of("java.lang", "String")));
    }

    @Test
    void isFormattableType_int_returnsFalse() {
        assertFalse(isFormattableType(TypeRef.ofPrimitive("int")));
    }

    @Test
    void isFormattableType_long_returnsFalse() {
        assertFalse(isFormattableType(TypeRef.ofPrimitive("long")));
    }

    @Test
    void isFormattableType_double_returnsFalse() {
        assertFalse(isFormattableType(TypeRef.ofPrimitive("double")));
    }

    @Test
    void isFormattableType_boolean_returnsFalse() {
        assertFalse(isFormattableType(TypeRef.ofPrimitive("boolean")));
    }

    // ------------------------------------------------------------------ boxedScalar

    @Test
    void boxedScalar_string_returnsJavaLangString() {
        TypeRef t = boxedScalar(ScalarKind.STRING);
        assertEquals("java.lang", t.packageName());
        assertEquals("String", t.simpleName());
    }

    @Test
    void boxedScalar_int_returnsJavaLangInteger() {
        TypeRef t = boxedScalar(ScalarKind.INT);
        assertEquals("java.lang", t.packageName());
        assertEquals("Integer", t.simpleName());
    }

    @Test
    void boxedScalar_long_returnsJavaLangLong() {
        TypeRef t = boxedScalar(ScalarKind.LONG);
        assertEquals("java.lang", t.packageName());
        assertEquals("Long", t.simpleName());
    }

    @Test
    void boxedScalar_double_returnsJavaLangDouble() {
        TypeRef t = boxedScalar(ScalarKind.DOUBLE);
        assertEquals("java.lang", t.packageName());
        assertEquals("Double", t.simpleName());
    }

    @Test
    void boxedScalar_boolean_returnsJavaLangBoolean() {
        TypeRef t = boxedScalar(ScalarKind.BOOLEAN);
        assertEquals("java.lang", t.packageName());
        assertEquals("Boolean", t.simpleName());
    }

    @Test
    void boxedScalar_bigDecimal_returnsJavaMathBigDecimal() {
        TypeRef t = boxedScalar(ScalarKind.BIG_DECIMAL);
        assertEquals("java.math", t.packageName());
        assertEquals("BigDecimal", t.simpleName());
    }

    @Test
    void boxedScalar_localDate_returnsJavaTimeLocalDate() {
        TypeRef t = boxedScalar(ScalarKind.LOCAL_DATE);
        assertEquals("java.time", t.packageName());
        assertEquals("LocalDate", t.simpleName());
    }

    @Test
    void boxedScalar_localDateTime_returnsJavaTimeLocalDateTime() {
        TypeRef t = boxedScalar(ScalarKind.LOCAL_DATE_TIME);
        assertEquals("java.time", t.packageName());
        assertEquals("LocalDateTime", t.simpleName());
    }

    @Test
    void boxedScalar_instant_returnsJavaTimeInstant() {
        TypeRef t = boxedScalar(ScalarKind.INSTANT);
        assertEquals("java.time", t.packageName());
        assertEquals("Instant", t.simpleName());
    }
}
