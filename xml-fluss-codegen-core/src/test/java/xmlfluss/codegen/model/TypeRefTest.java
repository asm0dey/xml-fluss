package xmlfluss.codegen.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TypeRefTest {

    @Test
    void ofClass_buildsNonPrimitiveNonNullable() {
        TypeRef t = TypeRef.of("java.util", "List");
        assertEquals("java.util", t.packageName());
        assertEquals("List", t.simpleName());
        assertEquals(List.of(), t.typeArguments());
        assertFalse(t.primitive());
        assertFalse(t.nullable());
        assertEquals("java.util.List", t.qualifiedName());
    }

    @Test
    void ofPrimitive_buildsPrimitive() {
        TypeRef t = TypeRef.ofPrimitive("int");
        assertTrue(t.primitive());
        assertEquals("", t.packageName());
        assertEquals("int", t.simpleName());
        assertEquals("int", t.qualifiedName());
    }

    @Test
    void parameterized_carriesArguments() {
        TypeRef str = TypeRef.of("java.lang", "String");
        TypeRef listOfStr = TypeRef.parameterized("java.util", "List", List.of(str));
        assertEquals(List.of(str), listOfStr.typeArguments());
        assertEquals("java.util.List", listOfStr.qualifiedName());
    }

    @Test
    void asNullable_returnsNullableCopy() {
        TypeRef base = TypeRef.of("java.lang", "String");
        TypeRef nullable = base.asNullable();
        assertFalse(base.nullable());
        assertTrue(nullable.nullable());
        assertEquals(base.packageName(), nullable.packageName());
        assertEquals(base.simpleName(), nullable.simpleName());
    }

    @Test
    void equals_isStructural() {
        TypeRef a = TypeRef.parameterized("java.util", "List",
                List.of(TypeRef.of("java.lang", "String")));
        TypeRef b = TypeRef.parameterized("java.util", "List",
                List.of(TypeRef.of("java.lang", "String")));
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void constructor_rejectsNullPackageName() {
        assertThrows(NullPointerException.class,
                () -> new TypeRef(null, "Foo", List.of(), false, false));
    }

    @Test
    void primitivesHaveEmptyPackage() {
        assertThrows(IllegalArgumentException.class,
                () -> new TypeRef("java.lang", "int", List.of(), true, false));
    }
}
