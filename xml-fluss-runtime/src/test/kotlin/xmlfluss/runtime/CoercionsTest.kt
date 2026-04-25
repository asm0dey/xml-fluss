package xmlfluss.runtime

import xmlfluss.Location
import xmlfluss.XmlParseException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoercionsTest {
    private val loc = Location(1, 1, "/test")

    @Test fun requireString_passesNonNull() {
        assertEquals("x", Coercions.requireString("f", "x", loc))
    }

    @Test fun requireString_throwsMissingForNull() {
        val ex = assertFailsWith<XmlParseException.Missing> { Coercions.requireString("f", null, loc) }
        assertEquals("f", ex.field)
        assertEquals(loc, ex.loc)
    }

    @Test fun toInt_validParses() {
        assertEquals(42, Coercions.toInt("n", "42", loc))
        assertEquals(-7, Coercions.toInt("n", "-7", loc))
    }

    @Test fun toInt_whitespaceRejected() {
        // toIntOrNull rejects surrounding whitespace; document the contract.
        val ex = assertFailsWith<XmlParseException.Coercion> { Coercions.toInt("n", " 42 ", loc) }
        assertEquals("n", ex.field)
        assertEquals(" 42 ", ex.raw)
        assertEquals("Int", ex.type)
        assertEquals(loc, ex.loc)
    }

    @Test fun toInt_nonNumericThrows() {
        val ex = assertFailsWith<XmlParseException.Coercion> { Coercions.toInt("n", "abc", loc) }
        assertEquals("n", ex.field)
        assertEquals("abc", ex.raw)
        assertEquals("Int", ex.type)
        assertEquals(loc, ex.loc)
    }

    @Test fun toLong_validAndInvalid() {
        assertEquals(9_999_999_999L, Coercions.toLong("l", "9999999999", loc))
        val ex = assertFailsWith<XmlParseException.Coercion> { Coercions.toLong("l", "x", loc) }
        assertEquals("Long", ex.type)
        assertEquals("x", ex.raw)
    }

    @Test fun toDouble_validAndInvalid() {
        assertEquals(3.14, Coercions.toDouble("d", "3.14", loc))
        val ex = assertFailsWith<XmlParseException.Coercion> { Coercions.toDouble("d", "nope", loc) }
        assertEquals("Double", ex.type)
    }

    @Test fun toBoolean_truthy() {
        assertTrue(Coercions.toBoolean("b", "true", loc))
        assertTrue(Coercions.toBoolean("b", "TRUE", loc))
        assertTrue(Coercions.toBoolean("b", "1", loc))
        assertTrue(Coercions.toBoolean("b", "yes", loc))
        assertTrue(Coercions.toBoolean("b", "  Yes  ", loc))
    }

    @Test fun toBoolean_falsy() {
        assertEquals(false, Coercions.toBoolean("b", "false", loc))
        assertEquals(false, Coercions.toBoolean("b", "False", loc))
        assertEquals(false, Coercions.toBoolean("b", "0", loc))
        assertEquals(false, Coercions.toBoolean("b", "no", loc))
        assertEquals(false, Coercions.toBoolean("b", "  NO  ", loc))
    }

    @Test fun toBoolean_invalidThrows() {
        val ex = assertFailsWith<XmlParseException.Coercion> { Coercions.toBoolean("b", "maybe", loc) }
        assertEquals("Boolean", ex.type)
        assertEquals("maybe", ex.raw)
    }

    @Test fun toLocalDate_iso() {
        assertEquals(LocalDate.of(2026, 4, 25), Coercions.toLocalDate("d", "2026-04-25", "", loc))
    }

    @Test fun toLocalDate_customPattern() {
        assertEquals(LocalDate.of(2026, 4, 25), Coercions.toLocalDate("d", "25/04/2026", "dd/MM/yyyy", loc))
    }

    @Test fun toLocalDate_invalidThrows() {
        val ex = assertFailsWith<XmlParseException.Coercion> { Coercions.toLocalDate("d", "not-a-date", "", loc) }
        assertEquals("LocalDate", ex.type)
    }

    @Test fun toLocalDateTime_iso() {
        assertEquals(LocalDateTime.of(2026, 4, 25, 10, 30), Coercions.toLocalDateTime("dt", "2026-04-25T10:30:00", "", loc))
    }

    @Test fun toLocalDateTime_invalidThrows() {
        val ex = assertFailsWith<XmlParseException.Coercion> { Coercions.toLocalDateTime("dt", "garbage", "", loc) }
        assertEquals("LocalDateTime", ex.type)
    }

    @Test fun toInstant_iso() {
        assertEquals(Instant.parse("2026-04-25T10:30:00Z"), Coercions.toInstant("i", "2026-04-25T10:30:00Z", "", loc))
    }

    @Test fun toInstant_customPattern() {
        val pattern = "yyyy-MM-dd'T'HH:mm:ssXXX"
        val expected = Instant.parse("2026-04-25T10:30:00Z")
        assertEquals(expected, Coercions.toInstant("i", "2026-04-25T10:30:00+00:00", pattern, loc))
    }

    @Test fun toInstant_invalidThrows() {
        val ex = assertFailsWith<XmlParseException.Coercion> { Coercions.toInstant("i", "no", "", loc) }
        assertEquals("Instant", ex.type)
    }

    @Test fun toBigDecimal_exactCtor() {
        assertEquals(BigDecimal("123.45"), Coercions.toBigDecimal("m", "123.45", "", loc))
    }

    @Test fun toBigDecimal_decimalFormatPattern() {
        assertEquals(BigDecimal("1234.50"), Coercions.toBigDecimal("m", "1,234.50", "#,##0.00", loc))
    }

    @Test fun toBigDecimal_trailingInputRejected() {
        val ex = assertFailsWith<XmlParseException.Coercion> {
            Coercions.toBigDecimal("m", "1,234.50abc", "#,##0.00", loc)
        }
        assertEquals("BigDecimal", ex.type)
        assertEquals("1,234.50abc", ex.raw)
    }

    @Test fun toBigDecimal_invalidThrows() {
        val ex = assertFailsWith<XmlParseException.Coercion> { Coercions.toBigDecimal("m", "x", "", loc) }
        assertEquals("BigDecimal", ex.type)
    }
}
