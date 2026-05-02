package xmlfluss.ksp

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.STRING

/**
 * KotlinPoet [ClassName]/[MemberName] handles for the runtime symbols the KSP
 * emitter targets. Lifted out of `XmlDslProcessor` so every emitter file in the
 * package can reach them without funneling through the processor instance.
 */

internal const val XML_RECORD_FQ = "xmlfluss.XmlRecord"

private const val XMLFLUSS_RUNTIME = "xmlfluss.runtime"
private const val KOTLIN_COLLECTIONS_PKG = "kotlin.collections"

internal val FLOW = ClassName("kotlinx.coroutines.flow", "Flow")
internal val FLOW_BUILDER = MemberName("kotlinx.coroutines.flow", "flow")
internal val INPUT_STREAM = ClassName("java.io", "InputStream")
internal val XML_READ_CURSOR = ClassName(XMLFLUSS_RUNTIME, "XmlReadCursor")
internal val COMPILED_PATH = ClassName("xmlfluss.path", "CompiledPath")
internal val PATHS_COMPILE = MemberName(ClassName(XMLFLUSS_RUNTIME, "Paths"), "compile")
internal val MISSING_EX = ClassName("xmlfluss", "XmlParseException", "Missing")
internal val MUTABLE_LIST = ClassName(KOTLIN_COLLECTIONS_PKG, "MutableList")
internal val MUTABLE_MAP = ClassName(KOTLIN_COLLECTIONS_PKG, "MutableMap")
internal val LINKED_MAP_OF = MemberName(KOTLIN_COLLECTIONS_PKG, "linkedMapOf")
internal val LOCATION = ClassName("xmlfluss", "Location")
internal val LOCATION_NULLABLE = LOCATION.copy(nullable = true)

internal val COERCE_INT = MemberName(ClassName(XMLFLUSS_RUNTIME, "Coercions"), "toInt")
internal val COERCE_LONG = MemberName(ClassName(XMLFLUSS_RUNTIME, "Coercions"), "toLong")
internal val COERCE_DOUBLE = MemberName(ClassName(XMLFLUSS_RUNTIME, "Coercions"), "toDouble")
internal val COERCE_BOOLEAN = MemberName(ClassName(XMLFLUSS_RUNTIME, "Coercions"), "toBoolean")
internal val COERCE_LOCAL_DATE = MemberName(ClassName(XMLFLUSS_RUNTIME, "Coercions"), "toLocalDate")
internal val COERCE_LOCAL_DATE_TIME = MemberName(ClassName(XMLFLUSS_RUNTIME, "Coercions"), "toLocalDateTime")
internal val COERCE_INSTANT = MemberName(ClassName(XMLFLUSS_RUNTIME, "Coercions"), "toInstant")
internal val COERCE_BIG_DECIMAL = MemberName(ClassName(XMLFLUSS_RUNTIME, "Coercions"), "toBigDecimal")

internal val STRING_NULLABLE = STRING.copy(nullable = true)

internal const val SKIP_CHILD = "c.skipChild()\n"
internal const val ELSE_SKIP_CHILD = "else -> $SKIP_CHILD"
internal const val TRUE_NL = "true\n"
