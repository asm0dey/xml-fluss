package xmlfluss.runtime

import xmlfluss.Location
import xmlfluss.XmlParseException
import xmlfluss.runtime.Coercions.requireString
import xmlfluss.runtime.Coercions.toLocalDate
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.ParsePosition
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * String-to-typed-value helpers used by generated parsers. Failures surface as
 * [XmlParseException.Coercion] (or [XmlParseException.Missing] from [requireString]), with the
 * source [Location] included in the message.
 */
object Coercions {
    /** Returns [value] when non-null, otherwise throws [XmlParseException.Missing] for [field]. */
    fun requireString(field: String, value: String?, loc: Location): String =
        value ?: throw XmlParseException.Missing(field, loc)

    /** Parses [value] as an `Int`. Accepts the same formats as [String.toIntOrNull]. */
    fun toInt(field: String, value: String, loc: Location): Int =
        value.toIntOrNull() ?: throw XmlParseException.Coercion(field, value, "Int", loc, NumberFormatException(value))

    /** Parses [value] as a `Long`. Accepts the same formats as [String.toLongOrNull]. */
    fun toLong(field: String, value: String, loc: Location): Long =
        value.toLongOrNull() ?: throw XmlParseException.Coercion(field, value, "Long", loc, NumberFormatException(value))

    /** Parses [value] as a `Double`. Accepts the same formats as [String.toDoubleOrNull]. */
    fun toDouble(field: String, value: String, loc: Location): Double =
        value.toDoubleOrNull() ?: throw XmlParseException.Coercion(field, value, "Double", loc, NumberFormatException(value))

    /**
     * Parses [value] as a `Boolean`. Accepts `true`/`false`, `1`/`0`, and `yes`/`no` (case
     * insensitive, surrounding whitespace ignored).
     */
    fun toBoolean(field: String, value: String, loc: Location): Boolean = when (value.trim().lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> throw XmlParseException.Coercion(field, value, "Boolean", loc, IllegalArgumentException(value))
    }

    /**
     * Parses [value] as a [LocalDate]. With an empty [pattern] the ISO format
     * (`DateTimeFormatter.ISO_LOCAL_DATE`) is used; otherwise the pattern is passed to
     * [DateTimeFormatter.ofPattern].
     */
    fun toLocalDate(field: String, value: String, pattern: String, loc: Location): LocalDate {
        val fmt = if (pattern.isEmpty()) DateTimeFormatter.ISO_LOCAL_DATE else DateTimeFormatter.ofPattern(pattern)
        return try { LocalDate.parse(value, fmt) }
        catch (e: Exception) { throw XmlParseException.Coercion(field, value, "LocalDate", loc, e) }
    }

    /** Parses [value] as a [LocalDateTime]. Pattern semantics match [toLocalDate]. */
    fun toLocalDateTime(field: String, value: String, pattern: String, loc: Location): LocalDateTime {
        val fmt = if (pattern.isEmpty()) DateTimeFormatter.ISO_LOCAL_DATE_TIME else DateTimeFormatter.ofPattern(pattern)
        return try { LocalDateTime.parse(value, fmt) }
        catch (e: Exception) { throw XmlParseException.Coercion(field, value, "LocalDateTime", loc, e) }
    }

    /**
     * Parses [value] as an [Instant]. An empty [pattern] uses [Instant.parse] (ISO 8601 with `Z`
     * zone). Otherwise the pattern must produce a temporal accepted by [Instant.from].
     */
    fun toInstant(field: String, value: String, pattern: String, loc: Location): Instant {
        return try {
            if (pattern.isEmpty()) Instant.parse(value)
            else Instant.from(DateTimeFormatter.ofPattern(pattern).parse(value))
        } catch (e: Exception) { throw XmlParseException.Coercion(field, value, "Instant", loc, e) }
    }

    /**
     * Parses [value] as a [BigDecimal]. An empty [pattern] uses the exact `BigDecimal(String)`
     * constructor. Otherwise [DecimalFormat] in `parseBigDecimal` mode parses the value, and
     * trailing input is rejected.
     */
    fun toBigDecimal(field: String, value: String, pattern: String, loc: Location): BigDecimal {
        return try {
            if (pattern.isEmpty()) BigDecimal(value)
            else {
                val df = DecimalFormat(pattern).apply { isParseBigDecimal = true }
                val pp = ParsePosition(0)
                val r = df.parse(value, pp) as? BigDecimal
                    ?: throw NumberFormatException("DecimalFormat returned non-BigDecimal")
                if (pp.index != value.length)
                    throw NumberFormatException("trailing input at ${pp.index}")
                r
            }
        } catch (e: Exception) { throw XmlParseException.Coercion(field, value, "BigDecimal", loc, e) }
    }
}
