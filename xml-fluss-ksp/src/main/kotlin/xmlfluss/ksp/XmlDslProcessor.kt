package xmlfluss.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.MAP
import com.squareup.kotlinpoet.MUTABLE_MAP
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.writeTo
import java.time.LocalDate
import javax.annotation.processing.Generated

/**
 * KSP processor that turns `@XmlRecord` data classes into streaming parsers.
 *
 * For each annotated class the processor:
 *
 * 1. Resolves `@XmlNs` declarations and compiles the record path with
 *    [xmlfluss.runtime.Paths.compile].
 * 2. Walks the primary constructor parameters, classifying each as `@XmlAttr`, `@XmlChild`,
 *    `@XmlText`, `@XmlMap`, or polymorphic (sealed parent annotated with `@XmlPolymorphic` +
 *    `@XmlSubtype` data-class variants), and resolves the field's coercion (scalar, temporal,
 *    decimal, custom converter, nested data class, map aggregate, or sealed dispatch).
 * 3. Emits a `${ClassName}Parser` Kotlin object via KotlinPoet, exposing
 *    `parse(InputStream): Flow<T>` plus private helpers for nested types.
 *
 * Generated parsers drive [xmlfluss.runtime.XmlReadCursor]. No reflection is used at runtime.
 */
class XmlDslProcessor(env: SymbolProcessorEnvironment) : SymbolProcessor {

    private val codeGen: CodeGenerator = env.codeGenerator
    private val logger: KSPLogger = env.logger

    /**
     * Sealed marker for diagnostics that originate from user input (annotation
     * misuse, malformed paths, type-shape rules). These render as concise KSP
     * errors. Anything else escaping `generate` is treated as an internal bug
     * and reported with a full stacktrace.
     */
    private sealed class ProcessorValidationException(message: String) : RuntimeException(message)
    private class ValidationError(message: String) : ProcessorValidationException(message)

    private fun vError(msg: String): Nothing = throw ValidationError(msg)
    private inline fun vRequire(cond: Boolean, msg: () -> String) {
        if (!cond) throw ValidationError(msg())
    }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(XML_RECORD_FQ)
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        for (cls in symbols) {
            try {
                generate(cls)
            } catch (e: ProcessorValidationException) {
                logger.error("xml-dsl-ksp: ${e.message}", cls)
            } catch (e: Throwable) {
                logger.error("xml-dsl-ksp: ${e.stackTraceToString()}", cls)
            }
        }
        return emptyList()
    }

    private fun generate(cls: KSClassDeclaration) {
        if (Modifier.DATA !in cls.modifiers) {
            logger.error("@XmlRecord requires data class", cls)
            return
        }
        val recordAnn = annotationOf(cls, XML_RECORD_FQ)
            ?: vError("@XmlRecord annotation not found on ${cls.qualifiedName?.asString()}")
        val recordPath = stringArg(recordAnn, "path")
            ?: vError("@XmlRecord missing 'path' on ${cls.qualifiedName?.asString()}")

        val nsMap = collectNs(cls)
        val ctor = cls.primaryConstructor
            ?: vError("@XmlRecord class needs primary constructor: ${cls.qualifiedName?.asString()}")

        val registry = NestedTypeRegistry()
        val fields = ctor.parameters.map { classifyParam(cls, it, nsMap, registry) }
        if (fields.count { it.source is Source.Text } > 1) {
            vError("${cls.qualifiedName?.asString()}: multiple @XmlText fields not allowed")
        }
        validateChildPaths(cls, fields)

        emitFile(cls, recordPath, nsMap, fields, registry)
    }

    private fun collectNs(cls: KSClassDeclaration): Map<String, String> =
        cls.annotations
            .filter { fq(it) == XML_NS_FQ }
            .associate {
                val prefix = stringArg(it, "prefix") ?: vError("XmlNs.prefix missing on ${cls.qualifiedName?.asString()}")
                val uri = stringArg(it, "uri") ?: vError("XmlNs.uri missing on ${cls.qualifiedName?.asString()}")
                prefix to uri
            }

    private fun classifyParam(
        cls: KSClassDeclaration,
        p: KSValueParameter,
        nsMap: Map<String, String>,
        registry: NestedTypeRegistry,
    ): FieldSpec {
        val name = p.name?.asString() ?: vError("unnamed param in ${cls.qualifiedName?.asString()}")
        val typeRef = p.type.resolve()
        val typeFq = typeRef.declaration.qualifiedName?.asString() ?: typeRef.toString()
        val nullable = typeRef.isMarkedNullable

        val isList = typeFq == "kotlin.collections.List"
        val isMap = typeFq == "kotlin.collections.Map"
        if (isList && nullable) vError("List field '$name' must not be nullable; use empty list")

        val elemKsTypeEarly: KSType? = when {
            isMap -> null
            isList -> typeRef.arguments.firstOrNull()?.type?.resolve()
            else -> typeRef
        }
        val sealedPolyDecl: KSClassDeclaration? = elemKsTypeEarly
            ?.let { it.declaration as? KSClassDeclaration }
            ?.takeIf { Modifier.SEALED in it.modifiers && annotationOf(it, XML_POLYMORPHIC_FQ) != null }

        var source: Source? = null
        var formatPattern: String? = null
        var converterFq: String? = null
        var converterCls: ClassName? = null
        var mapAnnot: KSAnnotation? = null

        for (a in p.annotations) {
            when (fq(a)) {
                XML_ATTR_FQ -> {
                    vRequire(source == null) { "multiple xml bindings on '$name'" }
                    val raw = stringArg(a, "name").orEmpty()
                    val n = if (raw.isEmpty()) name else raw
                    val (ans, alocal) = resolveQName(
                        n,
                        nsMap,
                        defaultNs = null,
                        path = n,
                        field = name,
                        ctx = "@XmlAttr"
                    )
                    source = Source.Attr(ans, alocal)
                }

                XML_CHILD_FQ -> {
                    vRequire(source == null) { "multiple xml bindings on '$name'" }
                    val raw = stringArg(a, "path").orEmpty()
                    if (sealedPolyDecl != null) {
                        source = buildPolyChild(sealedPolyDecl, raw, name, nsMap, registry)
                    } else {
                        val pathStr = if (raw.isEmpty()) name else raw
                        source = parseChildPath(pathStr, name, nsMap)
                    }
                }

                XML_TEXT_FQ -> {
                    vRequire(source == null) { "multiple xml bindings on '$name'" }
                    if (isList) vError("@XmlText on List unsupported for '$name'")
                    source = Source.Text(booleanArg(a, "preserveWhitespace") ?: false)
                }

                XML_MAP_FQ -> {
                    vRequire(source == null && mapAnnot == null) { "multiple xml bindings on '$name'" }
                    vRequire(isMap) { "@XmlMap requires Map<K, V> type for '$name'" }
                    mapAnnot = a
                }

                XML_FORMAT_FQ -> {
                    formatPattern = stringArg(a, "pattern") ?: vError("@XmlFormat missing pattern on '$name'")
                }

                XML_CONVERTER_FQ -> {
                    val ksType = a.arguments.firstOrNull { it.name?.asString() == "cls" }?.value as? KSType
                        ?: vError("@XmlConverter missing cls on '$name'")
                    val decl = ksType.declaration as? KSClassDeclaration
                        ?: vError("@XmlConverter cls must be a class on '$name'")
                    converterFq = decl.qualifiedName?.asString()
                        ?: vError("@XmlConverter cls qualified name missing on '$name'")
                    converterCls = decl.toClassName()
                }
            }
        }

        vRequire(formatPattern == null || converterCls == null) {
            "Field '$name' has both @XmlFormat and @XmlConverter; pick one"
        }

        if (mapAnnot != null) {
            vRequire(formatPattern == null && converterCls == null) {
                "@XmlFormat / @XmlConverter not supported on @XmlMap field '$name'"
            }
            return classifyMapParam(name, typeRef, mapAnnot, nsMap, registry)
        }

        vRequire(!isMap) { "Field '$name' is Map<K, V> but lacks @XmlMap" }

        val elemKsType: KSType = elemKsTypeEarly
            ?: vError("List<?> argument missing for '$name'")
        val elemFq = elemKsType.declaration.qualifiedName?.asString() ?: elemKsType.toString()
        val elemNullable = if (isList) elemKsType.isMarkedNullable else nullable

        if (source == null) vError("Field '$name' in ${cls.qualifiedName?.asString()} has no @XmlAttr/@XmlChild/@XmlText/@XmlMap")

        val coerce: Coerce = when {
            converterCls != null && converterFq != null -> Coerce.Custom(converterCls, converterFq)
            source is Source.PolyChild -> {
                vRequire(formatPattern == null && converterCls == null) {
                    "@XmlFormat / @XmlConverter not supported on polymorphic field '$name'"
                }
                Coerce.Nested(elemFq)
            }
            elemFq in SCALAR_TEMPORAL_FQS -> coerceForType(elemFq, formatPattern, name)
            isNestedDataClass(elemKsType) -> {
                if (source !is Source.Child) {
                    vError("Nested data-class field '$name' must use @XmlChild")
                }
                val elemDecl = elemKsType.declaration as KSClassDeclaration
                ensureNested(elemDecl, nsMap, registry, terminating = isList || elemNullable)
                Coerce.Nested(elemFq)
            }

            else -> vError("Unsupported type '$elemFq' for field '$name'. Use a scalar, supported temporal, BigDecimal, @XmlConverter, or a nested data class.")
        }

        val typeName = typeNameFor(elemKsType, isList, elemNullable, listNullable = false)
        val elemTypeName = typeNameFor(elemKsType, isList = false, elemNullable, listNullable = false)
        return FieldSpec(
            name = name,
            typeName = typeName,
            nullable = nullable,
            isList = isList,
            elemNullable = elemNullable,
            elemTypeName = elemTypeName,
            elemTypeFq = elemFq,
            source = source,
            coerce = coerce,
        )
    }

    private fun classifyMapParam(
        name: String,
        typeRef: KSType,
        mapAnnot: KSAnnotation,
        nsMap: Map<String, String>,
        registry: NestedTypeRegistry,
    ): FieldSpec {
        val entry = stringArg(mapAnnot, "entry") ?: vError("@XmlMap missing 'entry' for '$name'")
        val keyPath = stringArg(mapAnnot, "key") ?: vError("@XmlMap missing 'key' for '$name'")
        val valPath = stringArg(mapAnnot, "value") ?: vError("@XmlMap missing 'value' for '$name'")
        vRequire(entry.isNotBlank() && '/' !in entry && !entry.startsWith("@")) {
            "@XmlMap entry '$entry' for '$name' must be a single element name (optional 'prefix:local')"
        }
        val (entryNs, entryLocal) = resolveQName(entry, nsMap, defaultNs = nsMap[""], path = entry, field = name)

        val keyKsType = typeRef.arguments.getOrNull(0)?.type?.resolve()
            ?: vError("Map key type missing for '$name'")
        val valKsType = typeRef.arguments.getOrNull(1)?.type?.resolve()
            ?: vError("Map value type missing for '$name'")

        val keyField = buildSyntheticMapKvField("mk", keyKsType, keyPath, nsMap, registry, owner = name, kind = "key")
        val valField = buildSyntheticMapKvField("mv", valKsType, valPath, nsMap, registry, owner = name, kind = "value")

        val nullable = typeRef.isMarkedNullable
        val mapTypeName = MAP.parameterizedBy(keyField.typeName, valField.typeName).copy(nullable = nullable)

        return FieldSpec(
            name = name,
            typeName = mapTypeName,
            nullable = nullable,
            isList = false,
            elemNullable = false,
            elemTypeName = valField.typeName,
            elemTypeFq = "kotlin.collections.Map",
            source = Source.MapEntry(entryNs, entryLocal),
            coerce = Coerce.MapAggregate,
            mapKeyField = keyField,
            mapValueField = valField,
        )
    }

    private fun buildSyntheticMapKvField(
        syntheticName: String,
        type: KSType,
        pathStr: String,
        nsMap: Map<String, String>,
        registry: NestedTypeRegistry,
        owner: String,
        kind: String,
    ): FieldSpec {
        val typeFq = type.declaration.qualifiedName?.asString() ?: type.toString()
        vRequire(typeFq != "kotlin.collections.Map") { "@XmlMap '$kind' of '$owner': nested Map<,> not supported" }
        val isList = typeFq == "kotlin.collections.List"
        val nullable = if (isList) false else type.isMarkedNullable
        val elemKsType: KSType = if (isList) type.arguments.firstOrNull()?.type?.resolve()
            ?: vError("@XmlMap '$kind' of '$owner': List<?> arg missing")
        else type
        vRequire(!(isList && elemKsType.isMarkedNullable)) {
            "@XmlMap '$kind' of '$owner': nullable element inside List<…> not supported"
        }
        val elemFq = elemKsType.declaration.qualifiedName?.asString() ?: elemKsType.toString()
        vRequire(elemFq != "kotlin.collections.List") { "@XmlMap '$kind' of '$owner': List<List<?>> not supported" }
        vRequire(elemFq != "kotlin.collections.Map") { "@XmlMap '$kind' of '$owner': List<Map<?, ?>> / Map element not supported" }

        val source: Source = if (pathStr.startsWith("@")) {
            val rest = pathStr.substring(1)
            vRequire(rest.isNotEmpty()) { "@XmlMap '$kind' of '$owner': empty attribute name" }
            vRequire('/' !in rest) { "@XmlMap '$kind' of '$owner': '@' path must be a single attribute name" }
            val (ans, alocal) = resolveQName(
                rest,
                nsMap,
                defaultNs = null,
                path = pathStr,
                field = syntheticName,
                ctx = "@XmlMap '$kind'"
            )
            Source.Attr(ans, alocal)
        } else {
            parseChildPath(pathStr, syntheticName, nsMap)
        }

        val coerce: Coerce = when {
            elemFq in SCALAR_TEMPORAL_FQS -> coerceForType(elemFq, null, syntheticName)
            isNestedDataClass(elemKsType) -> {
                if (source !is Source.Child) {
                    vError("@XmlMap '$kind' of '$owner': nested data-class type requires an element path, not '@attr'")
                }
                val elemDecl = elemKsType.declaration as KSClassDeclaration
                ensureNested(elemDecl, nsMap, registry, terminating = isList || nullable)
                Coerce.Nested(elemFq)
            }

            else -> vError("@XmlMap '$kind' of '$owner': unsupported type '$elemFq'")
        }

        val typeName = typeNameFor(elemKsType, isList, elemNullable = nullable, listNullable = false)
        val elemTypeName = typeNameFor(elemKsType, isList = false, elemNullable = nullable, listNullable = false)
        return FieldSpec(
            name = syntheticName,
            typeName = typeName,
            nullable = nullable,
            isList = isList,
            elemNullable = nullable,
            elemTypeName = elemTypeName,
            elemTypeFq = elemFq,
            source = source,
            coerce = coerce,
        )
    }

    private fun isNestedDataClass(t: KSType): Boolean {
        val decl = t.declaration as? KSClassDeclaration ?: return false
        return Modifier.DATA in decl.modifiers
    }

    private fun ensureNested(
        cls: KSClassDeclaration,
        parentNs: Map<String, String>,
        registry: NestedTypeRegistry,
        terminating: Boolean = false,
    ): NestedTypeSpec {
        val fq = cls.qualifiedName?.asString() ?: vError("nested type missing qualified name")
        if (!terminating && registry.inProgress.contains(fq)) {
            val chain = registry.inProgress.toList()
            val start = chain.indexOf(fq).let { if (it < 0) 0 else it }
            val cycle = (chain.drop(start) + fq).joinToString(" -> ")
            vError("recursive nested data class $fq not supported (cycle: $cycle)")
        }
        val own = collectNs(cls)
        for ((p, u) in own) {
            val pu = parentNs[p] ?: continue
            if (pu != u) {
                vError("Nested data class $fq redeclares @XmlNs prefix '$p' as '$u' but enclosing record binds it to '$pu'")
            }
        }
        val ns = parentNs + own
        registry.byFq[fq]?.let { existing ->
            if (existing.nsMap != ns) {
                vError("Nested data class $fq used with conflicting @XmlNs scopes: ${existing.nsMap} vs $ns")
            }
            return existing
        }
        registry.inProgress.add(fq)
        try {
            val typeName = cls.toClassName()
            val helperName = "__parseNested_${typeName.simpleName}_${registry.byFq.size}"
            val ctor = cls.primaryConstructor ?: vError("Nested data class needs primary constructor: $fq")
            val stub = NestedTypeSpec(cls, typeName, helperName, ns, emptyList())
            registry.byFq[fq] = stub
            val fields = ctor.parameters.map { classifyParam(cls, it, ns, registry) }
            if (fields.count { it.source is Source.Text } > 1) {
                vError("$fq: multiple @XmlText fields not allowed")
            }
            validateChildPaths(cls, fields)
            val complete = stub.copy(fields = fields)
            registry.byFq[fq] = complete
            return complete
        } finally {
            registry.inProgress.remove(fq)
        }
    }

    private fun coerceForType(typeFq: String, pattern: String?, fieldName: String): Coerce = when (typeFq) {
        "kotlin.String" -> Coerce.AsString
        "kotlin.Int" -> Coerce.Scalar(ScalarKind.INT)
        "kotlin.Long" -> Coerce.Scalar(ScalarKind.LONG)
        "kotlin.Double" -> Coerce.Scalar(ScalarKind.DOUBLE)
        "kotlin.Boolean" -> Coerce.Scalar(ScalarKind.BOOLEAN)
        "java.time.LocalDate" -> Coerce.Temporal(TemporalKind.LOCAL_DATE, pattern ?: "")
        "java.time.LocalDateTime" -> Coerce.Temporal(TemporalKind.LOCAL_DATE_TIME, pattern ?: "")
        "java.time.Instant" -> Coerce.Temporal(TemporalKind.INSTANT, pattern ?: "")
        "java.math.BigDecimal" -> Coerce.Decimal(pattern ?: "")
        else -> vError("Unsupported type '$typeFq' for field '$fieldName'.")
    }

    private fun typeNameFor(t: KSType, isList: Boolean, elemNullable: Boolean, listNullable: Boolean): TypeName {
        val elemFq = t.declaration.qualifiedName?.asString() ?: t.toString()
        val base: TypeName = when (elemFq) {
            "kotlin.String" -> STRING
            "kotlin.Int" -> INT
            "kotlin.Long" -> LONG
            "kotlin.Double" -> DOUBLE
            "kotlin.Boolean" -> BOOLEAN
            else -> {
                val cn = (t.declaration as? KSClassDeclaration)?.toClassName()
                    ?: ClassName.bestGuess(elemFq)
                cn
            }
        }
        val elem = base.copy(nullable = elemNullable)
        return if (isList) LIST.parameterizedBy(elem).copy(nullable = listNullable) else elem
    }

    private fun parseChildPath(path: String, fieldName: String, nsMap: Map<String, String>): Source.Child {
        vRequire(path.isNotBlank()) { "@XmlChild path empty for '$fieldName'" }
        val descendant = path.startsWith("//")
        val rest = if (descendant) path.substring(2) else path
        vRequire(rest.isNotEmpty() && !rest.startsWith("/")) {
            "@XmlChild path '$path' for '$fieldName': invalid syntax"
        }
        val parts = rest.split('/').filter { it.isNotEmpty() }
        vRequire(parts.isNotEmpty()) { "@XmlChild path invalid for '$fieldName'" }
        if (descendant) {
            vRequire(!parts[0].startsWith("@")) {
                "@XmlChild path '$path' for '$fieldName': descendant axis head must be an element"
            }
        }
        val defaultNs: String? = nsMap[""]
        val out = mutableListOf<PathSeg>()
        for ((i, part) in parts.withIndex()) {
            val isLast = i == parts.lastIndex
            if (part.startsWith("@")) {
                vRequire(isLast) { "@XmlChild path '$path' for '$fieldName': '@' segment must be last" }
                vRequire(i > 0) { "@XmlChild path '$path' for '$fieldName': use @XmlAttr for record-level attributes" }
                val (ns, local) = resolveQName(part.substring(1), nsMap, defaultNs = null, path, fieldName)
                out += PathSeg.AttrLeaf(ns, local)
            } else {
                val (ns, local) = resolveQName(part, nsMap, defaultNs = defaultNs, path, fieldName)
                out += PathSeg.Element(ns, local)
            }
        }
        return Source.Child(out, descendant)
    }

    private fun buildPolyChild(
        sealedDecl: KSClassDeclaration,
        rawPath: String,
        fieldName: String,
        nsMap: Map<String, String>,
        registry: NestedTypeRegistry,
    ): Source.PolyChild {
        val polyAnnot = annotationOf(sealedDecl, XML_POLYMORPHIC_FQ)
            ?: vError("polymorphic field '$fieldName': sealed type ${sealedDecl.qualifiedName?.asString()} missing @XmlPolymorphic")
        val discriminator = stringArg(polyAnnot, "discriminator").orEmpty()

        val subtypes = sealedDecl.getSealedSubclasses().toList()
        vRequire(subtypes.isNotEmpty()) {
            "polymorphic field '$fieldName': sealed type ${sealedDecl.qualifiedName?.asString()} has no subclasses"
        }
        for (s in subtypes) {
            vRequire(Modifier.DATA in s.modifiers) {
                "polymorphic field '$fieldName': subtype ${s.qualifiedName?.asString()} must be a data class"
            }
            vRequire(annotationOf(s, XML_SUBTYPE_FQ) != null) {
                "polymorphic field '$fieldName': subtype ${s.qualifiedName?.asString()} missing @XmlSubtype"
            }
        }

        for (s in subtypes) ensureNested(s, nsMap, registry, terminating = true)

        if (discriminator.isEmpty()) {
            vRequire(rawPath.isEmpty()) {
                "polymorphic field '$fieldName': tag-mode @XmlChild path must be empty (got '$rawPath'); subtype tags drive dispatch"
            }
            val variants = subtypes.map { sub ->
                val subAnnot = annotationOf(sub, XML_SUBTYPE_FQ)!!
                val subName = stringArg(subAnnot, "name") ?: vError("@XmlSubtype.name missing on ${sub.qualifiedName?.asString()}")
                val (ns, local) = resolveQName(
                    subName, nsMap,
                    defaultNs = nsMap[""],
                    path = subName, field = fieldName,
                    ctx = "@XmlSubtype",
                )
                TagVariant(ns, local, sub.qualifiedName!!.asString())
            }
            val keys = variants.map { QKey(it.ns, it.local) }
            vRequire(keys.toSet().size == keys.size) {
                "polymorphic field '$fieldName': duplicate @XmlSubtype tags across variants"
            }
            return Source.PolyChild(PolyDispatch.Tag(variants))
        } else {
            vRequire(discriminator.startsWith("@")) {
                "polymorphic field '$fieldName': @XmlPolymorphic.discriminator must start with '@' (got '$discriminator')"
            }
            val attrRaw = discriminator.substring(1)
            vRequire(attrRaw.isNotEmpty() && '/' !in attrRaw) {
                "polymorphic field '$fieldName': bad discriminator '$discriminator'"
            }
            val (attrNs, attrLocal) = resolveQName(
                attrRaw, nsMap, defaultNs = null,
                path = discriminator, field = fieldName, ctx = "@XmlPolymorphic discriminator",
            )
            vRequire(rawPath.isNotBlank()) {
                "polymorphic field '$fieldName': attr-mode @XmlChild requires the wrapping element path"
            }
            vRequire(!rawPath.startsWith("//") && '/' !in rawPath && !rawPath.startsWith("@")) {
                "polymorphic field '$fieldName': attr-mode @XmlChild path must be a single direct-child element (got '$rawPath')"
            }
            val (wrapNs, wrapLocal) = resolveQName(
                rawPath, nsMap, defaultNs = nsMap[""],
                path = rawPath, field = fieldName,
            )
            val variants = subtypes.map { sub ->
                val subAnnot = annotationOf(sub, XML_SUBTYPE_FQ)!!
                val value = stringArg(subAnnot, "name") ?: vError("@XmlSubtype.name missing on ${sub.qualifiedName?.asString()}")
                AttrVariant(value, sub.qualifiedName!!.asString())
            }
            vRequire(variants.map { it.value }.toSet().size == variants.size) {
                "polymorphic field '$fieldName': duplicate @XmlSubtype values across variants"
            }
            return Source.PolyChild(PolyDispatch.Attr(wrapNs, wrapLocal, attrNs, attrLocal, variants))
        }
    }

    private fun resolveQName(
        s: String,
        nsMap: Map<String, String>,
        defaultNs: String?,
        path: String,
        field: String,
        ctx: String = "@XmlChild",
    ): Pair<String?, String> {
        val ci = s.indexOf(':')
        if (ci < 0) return defaultNs to s
        val prefix = s.substring(0, ci)
        val local = s.substring(ci + 1)
        vRequire(prefix.isNotEmpty() && local.isNotEmpty()) {
            "$ctx path '$path' for '$field': bad qname '$s'"
        }
        val ns = nsMap[prefix]
            ?: vError("$ctx path '$path' for '$field': unbound NS prefix '$prefix' (declare via @XmlNs)")
        return ns to local
    }

    private fun validateChildPaths(cls: KSClassDeclaration, fields: List<FieldSpec>) {
        val root = TrieNode()
        val directKeys = mutableSetOf<QKey>()
        for (f in fields) {
            val src = f.source as? Source.Child ?: continue
            if (src.descendant) continue
            insertIntoTrie(root, src.segments, f)
            (src.segments.firstOrNull() as? PathSeg.Element)?.let { directKeys += QKey(it.ns, it.name) }
        }
        validateTrie(root, cls)

        val descendantHeads = mutableSetOf<QKey>()
        for (f in fields) {
            val src = f.source as? Source.Child ?: continue
            if (!src.descendant) continue
            (src.segments.firstOrNull() as? PathSeg.Element)?.let { descendantHeads += QKey(it.ns, it.name) }
        }
        val collision = directKeys intersect descendantHeads
        if (collision.isNotEmpty()) {
            val k = collision.first()
            vError("${cls.qualifiedName?.asString()}: @XmlChild('${k.local}') and @XmlChild('//${k.local}') target the same head element '${k.local}'; pick one")
        }

        val seenMapEntries = mutableSetOf<QKey>()
        for (f in fields) {
            val src = f.source as? Source.MapEntry ?: continue
            val key = QKey(src.entryNs, src.entryLocal)
            if (key in directKeys) {
                vError("${cls.qualifiedName?.asString()}: @XmlMap entry '${src.entryLocal}' on field '${f.name}' clashes with another @XmlChild's first segment")
            }
            if (key in descendantHeads) {
                vError("${cls.qualifiedName?.asString()}: @XmlMap entry '${src.entryLocal}' on field '${f.name}' clashes with a descendant @XmlChild('//${key.local}') head")
            }
            if (!seenMapEntries.add(key)) {
                vError("${cls.qualifiedName?.asString()}: duplicate @XmlMap entry '${src.entryLocal}' on field '${f.name}'")
            }
        }

        val seenPolyKeys = mutableSetOf<QKey>()
        var sawTagMode = false
        for (f in fields) {
            val src = f.source as? Source.PolyChild ?: continue
            when (val d = src.dispatch) {
                is PolyDispatch.Tag -> {
                    if (sawTagMode) {
                        vError("${cls.qualifiedName?.asString()}: more than one tag-mode polymorphic @XmlChild field at the same scope (field '${f.name}')")
                    }
                    sawTagMode = true
                    for (v in d.variants) {
                        val k = QKey(v.ns, v.local)
                        if (k in directKeys || k in seenMapEntries || !seenPolyKeys.add(k)) {
                            vError("${cls.qualifiedName?.asString()}: polymorphic subtype tag '${v.local}' on field '${f.name}' clashes with another @XmlChild / @XmlMap / subtype")
                        }
                        if (k in descendantHeads) {
                            vError("${cls.qualifiedName?.asString()}: polymorphic subtype tag '${v.local}' on field '${f.name}' clashes with a descendant @XmlChild('//${k.local}') head")
                        }
                    }
                }
                is PolyDispatch.Attr -> {
                    val k = QKey(d.wrapNs, d.wrapLocal)
                    if (k in directKeys || k in seenMapEntries || !seenPolyKeys.add(k)) {
                        vError("${cls.qualifiedName?.asString()}: polymorphic wrap tag '${d.wrapLocal}' on field '${f.name}' clashes with another @XmlChild / @XmlMap / subtype")
                    }
                    if (k in descendantHeads) {
                        vError("${cls.qualifiedName?.asString()}: polymorphic wrap tag '${d.wrapLocal}' on field '${f.name}' clashes with a descendant @XmlChild('//${k.local}') head")
                    }
                }
            }
        }
    }

    private fun insertIntoTrie(root: TrieNode, segments: List<PathSeg>, f: FieldSpec) {
        var node = root
        val elements = segments.takeWhile { it is PathSeg.Element }
            .map { (it as PathSeg.Element).let { e -> QKey(e.ns, e.name) } }
        val tail = segments.drop(elements.size)
        for (e in elements) {
            node = node.children.getOrPut(e) { TrieNode() }
        }
        when {
            tail.isEmpty() -> {
                if (f.coerce is Coerce.Nested) node.nestedEntries += f
                else node.textEntries += f
            }

            tail.size == 1 && tail[0] is PathSeg.AttrLeaf -> {
                val al = tail[0] as PathSeg.AttrLeaf
                node.attrEntries += Triple(al.ns, al.name, f)
            }

            else -> vError("invalid path tail for field '${f.name}'")
        }
    }

    private fun validateTrie(node: TrieNode, cls: KSClassDeclaration) {
        val mixCount = listOf(
            node.textEntries.isNotEmpty(),
            node.nestedEntries.isNotEmpty(),
            node.children.isNotEmpty(),
        ).count { it }
        if (mixCount > 1) {
            val texts = node.textEntries.joinToString { it.name }
            val nested = node.nestedEntries.joinToString { it.name }
            vError("${cls.qualifiedName?.asString()}: cannot mix text/nested/descend at same element [text=$texts nested=$nested children=${node.children.size}]")
        }
        val nonListText = node.textEntries.count { !it.isList }
        if (nonListText > 1) {
            val names = node.textEntries.filter { !it.isList }.joinToString { it.name }
            vError("${cls.qualifiedName?.asString()}: multiple non-list text fields [$names] target same element")
        }
        val nonListNested = node.nestedEntries.count { !it.isList }
        if (nonListNested > 1) {
            val names = node.nestedEntries.filter { !it.isList }.joinToString { it.name }
            vError("${cls.qualifiedName?.asString()}: multiple non-list nested fields [$names] target same element")
        }
        if (node.nestedEntries.size > 1 &&
            node.nestedEntries.any { it.isList } && node.nestedEntries.any { !it.isList }
        ) {
            vError("${cls.qualifiedName?.asString()}: cannot mix list and non-list nested fields at same element")
        }
        for (c in node.children.values) validateTrie(c, cls)
    }

    private fun emitFile(
        cls: KSClassDeclaration,
        recordPath: String,
        nsMap: Map<String, String>,
        fields: List<FieldSpec>,
        registry: NestedTypeRegistry,
    ) {
        val pkg = cls.packageName.asString()
        val recordTypeName: ClassName = ClassName(pkg, cls.simpleName.asString())
        val parserName = "${cls.simpleName.asString()}Parser"

        val flowOfRecord = FLOW.parameterizedBy(recordTypeName)

        val nsProp = PropertySpec.builder("NS", MAP.parameterizedBy(STRING, STRING))
            .addModifiers(KModifier.PRIVATE)
            .initializer(buildNsInitializer(nsMap))
            .build()

        val pathProp = PropertySpec.builder("PATH", COMPILED_PATH)
            .addModifiers(KModifier.PRIVATE)
            .initializer("%M(%S, NS)", PATHS_COMPILE, recordPath)
            .build()

        val convVarFor = mutableMapOf<String, String>()
        val convProps = mutableListOf<PropertySpec>()
        fun registerConverter(c: Coerce.Custom) {
            val key = c.cls.canonicalName
            if (key in convVarFor) return
            val varName = "__conv_${convVarFor.size}"
            convVarFor[key] = varName
            convProps += PropertySpec.builder(varName, c.cls)
                .addModifiers(KModifier.PRIVATE)
                .initializer("%T()", c.cls)
                .build()
        }
        for (f in fields) (f.coerce as? Coerce.Custom)?.let(::registerConverter)
        for (n in registry.byFq.values) for (f in n.fields) (f.coerce as? Coerce.Custom)?.let(::registerConverter)

        val parseFun = FunSpec.builder("parse")
            // @JvmOverloads lets Java callers omit `ignoreNamespace` and call parse(input)
            .addAnnotation(JvmOverloads::class)
            .addParameter(ParameterSpec("input", INPUT_STREAM))
            .addParameter(
                ParameterSpec.builder("ignoreNamespace", BOOLEAN)
                    .defaultValue("false")
                    .build()
            )
            .returns(flowOfRecord)
            .addCode(buildParseBody(recordTypeName, fields, convVarFor, registry))
            .build()

        val nestedHelpers = registry.byFq.values.map { spec ->
            FunSpec.builder(spec.helperName)
                .addModifiers(KModifier.PRIVATE)
                .addParameter("c", XML_READ_CURSOR)
                .returns(spec.typeName)
                .addCode(buildSubrecordBody(spec, convVarFor, registry))
                .build()
        }

        val parserBuilder = TypeSpec.objectBuilder(parserName)
            .addProperty(nsProp)
            .addProperty(pathProp)
        for (p in convProps) parserBuilder.addProperty(p)
        for (h in nestedHelpers) parserBuilder.addFunction(h)
        parserBuilder.addFunction(parseFun)
        parserBuilder.addOriginatingKSFile(cls.containingFile!!)

        val file = FileSpec.builder(pkg, parserName)
            .addAnnotation(
                AnnotationSpec.builder(Generated::class)
                    .addMember("%S", "Generated by xml-dsl")
                    .addMember("date = %S", LocalDate.now().toString())
                    .build()
            )
            .addAnnotation(
                AnnotationSpec.builder(Suppress::class)
                    .addMember("\"FunctionName\", \"RedundantExplicitType\", \"RedundantVisibilityModifier\", \"LocalVariableName\", \"IfThenToSafeAccess\"")
                    .build()
            )
            .addType(parserBuilder.build())
            .build()

        file.writeTo(codeGen, aggregating = false)
    }

    private fun buildNsInitializer(nsMap: Map<String, String>): CodeBlock {
        if (nsMap.isEmpty()) return CodeBlock.of("emptyMap()")
        val cb = CodeBlock.builder().add("mapOf(\n")
        nsMap.forEach { (k, v) -> cb.add("    %S to %S,\n", k, v) }
        cb.add(")")
        return cb.build()
    }

    private fun buildParseBody(
        recordType: ClassName,
        fields: List<FieldSpec>,
        convVarFor: Map<String, String>,
        registry: NestedTypeRegistry,
    ): CodeBlock {
        val cb = CodeBlock.builder()
        cb.beginControlFlow("return·%M", FLOW_BUILDER)
        cb.beginControlFlow("%T(input,·PATH,·ignoreNamespace).use·{ c ->\n", XML_READ_CURSOR)
        cb.beginControlFlow("while (c.findNextRecord())")
        emitInstanceBody(cb, recordType, fields, convVarFor, registry, ctx = Ctx.RECORD)
        cb.endControlFlow()
        cb.endControlFlow()
        cb.endControlFlow()
        return cb.build()
    }

    private fun buildSubrecordBody(
        spec: NestedTypeSpec,
        convVarFor: Map<String, String>,
        registry: NestedTypeRegistry,
    ): CodeBlock {
        val cb = CodeBlock.builder()
        emitInstanceBody(cb, spec.typeName, spec.fields, convVarFor, registry, ctx = Ctx.SUBRECORD)
        return cb.build()
    }

    private fun emitInstanceBody(
        cb: CodeBlock.Builder,
        type: ClassName,
        fields: List<FieldSpec>,
        convVarFor: Map<String, String>,
        registry: NestedTypeRegistry,
        ctx: Ctx,
    ) {
        val attrFields = fields.filter { it.source is Source.Attr }
        val textField = fields.firstOrNull { it.source is Source.Text }
        val childFields = fields.filter { it.source is Source.Child }
        val mapFields = fields.filter { it.source is Source.MapEntry }
        val polyFields = fields.filter { it.source is Source.PolyChild }

        val attrFn = if (ctx == Ctx.RECORD) "recordAttr" else "childAttr"
        val forEachFn = if (ctx == Ctx.RECORD) "forEachRecordChild" else "forEachSubrecordChild"
        val textFn = if (ctx == Ctx.RECORD) "recordText" else "subrecordText"
        val locFn = if (ctx == Ctx.RECORD) "recordLocation" else "childLocation"

        cb.add("val __loc: %T = c.${locFn}()\n", LOCATION)

        for (f in attrFields) {
            val src = f.source as Source.Attr
            val nsLit: CodeBlock = if (src.ns == null) CodeBlock.of("null") else CodeBlock.of("%S", src.ns)
            cb.add("val __raw_${f.name}: %T = c.${attrFn}(%L, %S)\n", STRING_NULLABLE, nsLit, src.name)
        }

        for (f in childFields + polyFields + listOfNotNull(textField)) emitFieldStateInit(cb, f)
        for (mf in mapFields) emitMapStateInit(cb, mf)

        val descendantFields = childFields.filter { (it.source as Source.Child).descendant }
        val directFields = childFields.filter { !(it.source as Source.Child).descendant }

        // Invariant: textField != null  ⇒  needTraverse == true (textField is one of the
        // disjuncts below). coerceField below reads `__raw_${textField.name}` unconditionally,
        // so the declaration emitted inside this block is always reached when textField != null.
        val needTraverse = childFields.isNotEmpty() || textField != null || mapFields.isNotEmpty() || polyFields.isNotEmpty()
        if (needTraverse) {
            val root = TrieNode()
            for (f in directFields) {
                val src = f.source as Source.Child
                insertIntoTrie(root, src.segments, f)
            }
            cb.beginControlFlow("c.${forEachFn}·{ ln, ns ->\n")
            emitTopLevelChildSwitch(cb, root, descendantFields, mapFields, polyFields, registry, convVarFor)
            cb.endControlFlow()
            if (textField != null) {
                val preserve = (textField.source as Source.Text).preserveWhitespace
                cb.add("val __raw_${textField.name}: %T = c.${textFn}($preserve)\n", STRING)
            }
        }

        for (f in fields) cb.add(coerceField(f, convVarFor))

        val emitVerb = if (ctx == Ctx.RECORD) "emit" else "return"
        cb.add("$emitVerb(%T(\n", type)
        cb.indent()
        for (f in fields) cb.add("${f.name} = __final_${f.name},\n")
        cb.unindent()
        cb.add("))\n")
    }

    private fun emitFieldStateInit(cb: CodeBlock.Builder, f: FieldSpec) {
        // Source.Text is declared and assigned in one go at the recordText/subrecordText call
        // site (see emitParseBody). No state needed up front — the cursor always provides a
        // String, so the early `var __raw_X: String? = null` would just be a dead initializer.
        if (f.source is Source.Text) return
        when {
            f.coerce is Coerce.Nested && f.isList -> {
                cb.add(
                    "val __list_${f.name}: %T = mutableListOf()\n",
                    MUTABLE_LIST.parameterizedBy(f.elemTypeName)
                )
            }

            f.coerce is Coerce.Nested -> {
                cb.add("var __set_${f.name}: %T = false\n", BOOLEAN)
                cb.add(
                    "var __nested_${f.name}: %T = null\n",
                    f.elemTypeName.copy(nullable = true)
                )
            }

            f.isList -> {
                cb.add(
                    "val __list_${f.name}: %T = mutableListOf()\n",
                    MUTABLE_LIST.parameterizedBy(STRING)
                )
            }

            else -> {
                if (needsSetFlag(f)) cb.add("var __set_${f.name}: %T = false\n", BOOLEAN)
                cb.add("var __raw_${f.name}: %T = null\n", STRING_NULLABLE)
                if (needsChildLoc(f)) {
                    cb.add("var __loc_${f.name}: %T = null\n", LOCATION_NULLABLE)
                }
            }
        }
    }

    /**
     * `__set_X` is read by [coerceField] for `Source.Child` (missing/null check). `Source.Text`
     * is declared at the recordText/subrecordText call and never tracked through a flag.
     * `Source.Attr` never goes through this branch.
     */
    private fun needsSetFlag(f: FieldSpec): Boolean = when (f.source) {
        is Source.Text -> false
        else -> true
    }

    /**
     * `__loc_X` is read only by [coerceField] when the child field's `coerceRaw` consumes the
     * `lc` argument — i.e. for Scalar/Temporal/Decimal/Custom coercions on a single (non-list)
     * `Source.Child`. `AsString`, `Nested`, and list paths never reference `__loc_X`, so emitting
     * it would be dead code.
     */
    private fun needsChildLoc(f: FieldSpec): Boolean =
        f.source is Source.Child && !f.isList &&
            f.coerce !is Coerce.AsString && f.coerce !is Coerce.Nested

    private fun emitMapStateInit(cb: CodeBlock.Builder, f: FieldSpec) {
        val keyF = f.mapKeyField ?: error("map field '${f.name}' missing key field")
        val valF = f.mapValueField ?: error("map field '${f.name}' missing value field")
        val storedValueType: TypeName =
            if (valF.isList) MUTABLE_LIST.parameterizedBy(valF.elemTypeName) else valF.typeName
        val storeType = MUTABLE_MAP.parameterizedBy(keyF.typeName, storedValueType)
        cb.add("val __map_${f.name}: %T = %M()\n", storeType, LINKED_MAP_OF)
        if (f.nullable) cb.add("var __set_${f.name}: %T = false\n", BOOLEAN)
    }

    private fun emitMapEntryCase(
        cb: CodeBlock.Builder,
        f: FieldSpec,
        registry: NestedTypeRegistry,
        convVarFor: Map<String, String>,
    ) {
        val keyF = f.mapKeyField ?: error("map field '${f.name}' missing key field")
        val valF = f.mapValueField ?: error("map field '${f.name}' missing value field")
        val synthetic = listOf(keyF, valF)

        for (sf in synthetic) {
            val src = sf.source
            if (src is Source.Attr) {
                val nsLit: CodeBlock = if (src.ns == null) CodeBlock.of("null") else CodeBlock.of("%S", src.ns)
                if (sf.isList) {
                    cb.add(
                        "val __list_${sf.name}: %T = mutableListOf()\n",
                        MUTABLE_LIST.parameterizedBy(STRING)
                    )
                    cb.add("c.childAttr(%L, %S)?.let { __list_${sf.name}.add(it) }\n", nsLit, src.name)
                } else {
                    cb.add(
                        "val __raw_${sf.name}: %T = c.childAttr(%L, %S)\n",
                        STRING_NULLABLE, nsLit, src.name
                    )
                }
            } else {
                emitFieldStateInit(cb, sf)
            }
        }

        val childSyn = synthetic.filter { it.source is Source.Child }
        if (childSyn.isNotEmpty()) {
            val descendF = childSyn.filter { (it.source as Source.Child).descendant }
            val directF = childSyn.filter { !(it.source as Source.Child).descendant }
            val root = TrieNode()
            for (df in directF) insertIntoTrie(root, (df.source as Source.Child).segments, df)
            cb.beginControlFlow("c.forEachSubrecordChild·{ ln, ns ->\n")
            emitTopLevelChildSwitch(cb, root, descendF, emptyList(), emptyList(), registry, convVarFor)
            cb.endControlFlow()
        }

        cb.add(coerceField(keyF, convVarFor))
        cb.add(coerceField(valF, convVarFor))

        if (valF.isList) {
            cb.add(
                "__map_${f.name}.getOrPut(__final_${keyF.name}) { mutableListOf() }.addAll(__final_${valF.name})\n"
            )
        } else {
            cb.add("__map_${f.name}[__final_${keyF.name}] = __final_${valF.name}\n")
        }
        if (f.nullable) cb.add("__set_${f.name} = true\n")
    }

    private fun emitChildrenSwitch(cb: CodeBlock.Builder, node: TrieNode, registry: NestedTypeRegistry) {
        if (node.children.isEmpty()) {
            cb.add("c.skipChild()\n")
            return
        }
        cb.beginControlFlow("when(ln)")
        for ((key, child) in node.children) {
            val cond = qnameCond(key.ns, key.local)
            cb.beginControlFlow("%L ->", cond)
            emitChildBody(cb, child, registry)
            cb.endControlFlow()
        }
        cb.add("else -> c.skipChild()\n")
        cb.endControlFlow()
    }

    private fun emitTopLevelChildSwitch(
        cb: CodeBlock.Builder,
        directRoot: TrieNode,
        descendantFields: List<FieldSpec>,
        mapFields: List<FieldSpec>,
        polyFields: List<FieldSpec>,
        registry: NestedTypeRegistry,
        convVarFor: Map<String, String>,
    ) {
        if (directRoot.children.isEmpty() && descendantFields.isEmpty() && mapFields.isEmpty() && polyFields.isEmpty()) {
            cb.add("c.skipChild()\n")
            return
        }
        val byHead = LinkedHashMap<QKey, MutableList<FieldSpec>>()
        for (f in descendantFields) {
            val seg = (f.source as Source.Child).segments[0] as PathSeg.Element
            byHead.getOrPut(QKey(seg.ns, seg.name)) { mutableListOf() }.add(f)
        }
        cb.beginControlFlow("when(ln)")
        for ((key, child) in directRoot.children) {
            val cond = qnameCond(key.ns, key.local)
            cb.beginControlFlow("%L ->", cond)
            emitChildBody(cb, child, registry)
            cb.endControlFlow()
        }
        for (mf in mapFields) {
            val src = mf.source as Source.MapEntry
            val cond = qnameCond(src.entryNs, src.entryLocal)
            cb.beginControlFlow("%L ->", cond)
            emitMapEntryCase(cb, mf, registry, convVarFor)
            cb.endControlFlow()
        }
        for (pf in polyFields) {
            when (val d = (pf.source as Source.PolyChild).dispatch) {
                is PolyDispatch.Tag -> {
                    for (v in d.variants) {
                        val cond = qnameCond(v.ns, v.local)
                        cb.beginControlFlow("%L ->", cond)
                        emitPolyAssign(cb, pf, v.subtypeFq, registry)
                        cb.endControlFlow()
                    }
                }
                is PolyDispatch.Attr -> {
                    val cond = qnameCond(d.wrapNs, d.wrapLocal)
                    cb.beginControlFlow("%L ->", cond)
                    emitPolyAttrSwitch(cb, pf, d, registry)
                    cb.endControlFlow()
                }
            }
        }
        for ((head, fields) in byHead) {
            val cond = qnameCond(head.ns, head.local)
            cb.beginControlFlow("%L ->", cond)
            emitDescendantArm(cb, head, fields, registry, terminating = false)
            cb.endControlFlow()
        }
        if (byHead.isEmpty()) {
            cb.add("else -> c.skipChild()\n")
        } else {
            cb.beginControlFlow("else ->")
            cb.beginControlFlow("c.forEachDescendantInChild·{ dln, dns ->\n")
            cb.beginControlFlow("when(dln)")
            for ((head, fields) in byHead) {
                val cond = qnameCond(head.ns, head.local, nsVar = "dns")
                cb.beginControlFlow("%L ->", cond)
                emitDescendantArm(cb, head, fields, registry, terminating = true)
                cb.endControlFlow()
            }
            cb.add("else -> false\n")
            cb.endControlFlow()
            cb.endControlFlow()
            cb.endControlFlow()
        }
        cb.endControlFlow()
    }

    private fun emitDescendantArm(
        cb: CodeBlock.Builder,
        head: QKey,
        headFields: List<FieldSpec>,
        registry: NestedTypeRegistry,
        terminating: Boolean,
    ) {
        val emptyTail = headFields.filter { (it.source as Source.Child).segments.size == 1 }
        val nonEmptyTail = headFields.filter { (it.source as Source.Child).segments.size > 1 }
        vRequire(!(emptyTail.isNotEmpty() && nonEmptyTail.isNotEmpty())) {
            "cannot mix '//${head.local}' with '//${head.local}/...' on the same head element"
        }
        if (emptyTail.isNotEmpty()) {
            vRequire(emptyTail.size == 1) {
                "multiple descendant fields targeting '//${head.local}' (single-segment); at most one allowed"
            }
            emitLeafReadInline(cb, emptyTail[0], registry)
        } else {
            val tailTrie = TrieNode()
            for (f in nonEmptyTail) {
                val tail = (f.source as Source.Child).segments.drop(1)
                insertIntoTrie(tailTrie, tail, f)
            }
            emitChildBody(cb, tailTrie, registry)
        }
        if (terminating) cb.add("true\n")
    }

    private fun qnameCond(ns: String?, local: String, nsVar: String = "ns"): CodeBlock =
        if (ns == null) CodeBlock.of("%S if ($nsVar == null || c.ignoreNamespace)", local)
        else CodeBlock.of("%S if ($nsVar == %S || c.ignoreNamespace)", local, ns)

    private fun emitLeafReadInline(cb: CodeBlock.Builder, f: FieldSpec, registry: NestedTypeRegistry) {
        when (f.coerce) {
            is Coerce.Nested -> {
                val spec = registry.byFq[f.elemTypeFq]
                    ?: error("missing nested spec for ${f.elemTypeFq}")
                cb.add("val __n_${f.name}·=·${spec.helperName}(c)\n")
                if (f.isList) {
                    cb.add("__list_${f.name}.add(__n_${f.name})\n")
                } else {
                    cb.add("__nested_${f.name} = __n_${f.name}\n")
                    cb.add("__set_${f.name} = true\n")
                }
            }

            else -> {
                val needLoc = needsChildLoc(f)
                if (needLoc) cb.add("val __t_loc·=·c.childLocation()\n")
                cb.add("val __t = c.childText(false)\n")
                if (f.isList) {
                    cb.add("__list_${f.name}.add(__t)\n")
                } else {
                    cb.add("__raw_${f.name} = __t\n")
                    if (needLoc) cb.add("__loc_${f.name} = __t_loc\n")
                    cb.add("__set_${f.name} = true\n")
                }
            }
        }
    }

    private fun emitPolyAssign(
        cb: CodeBlock.Builder,
        f: FieldSpec,
        subtypeFq: String,
        registry: NestedTypeRegistry,
    ) {
        val spec = registry.byFq[subtypeFq]
            ?: error("missing nested spec for polymorphic subtype $subtypeFq")
        cb.add("val __n_${f.name}·=·${spec.helperName}(c)\n")
        if (f.isList) {
            cb.add("__list_${f.name}.add(__n_${f.name})\n")
        } else {
            cb.add("__nested_${f.name} = __n_${f.name}\n")
            cb.add("__set_${f.name} = true\n")
        }
    }

    private fun emitPolyAttrSwitch(
        cb: CodeBlock.Builder,
        f: FieldSpec,
        d: PolyDispatch.Attr,
        registry: NestedTypeRegistry,
    ) {
        val nsLit: CodeBlock = if (d.attrNs == null) CodeBlock.of("null") else CodeBlock.of("%S", d.attrNs)
        cb.add("val __disc_${f.name}: %T = c.childAttr(%L, %S)\n", STRING_NULLABLE, nsLit, d.attrLocal)
        cb.beginControlFlow("when (__disc_${f.name})")
        for (v in d.variants) {
            cb.beginControlFlow("%S ->", v.value)
            emitPolyAssign(cb, f, v.subtypeFq, registry)
            cb.endControlFlow()
        }
        cb.add("else -> c.skipChild()\n")
        cb.endControlFlow()
    }

    private fun emitChildBody(cb: CodeBlock.Builder, node: TrieNode, registry: NestedTypeRegistry) {
        for ((attrNs, attrName, f) in node.attrEntries) {
            val nsLit: CodeBlock = if (attrNs == null) CodeBlock.of("null") else CodeBlock.of("%S", attrNs)
            if (f.isList) {
                cb.add("c.childAttr(%L, %S)?.let { __list_${f.name}.add(it) }\n", nsLit, attrName)
            } else if (needsChildLoc(f)) {
                cb.add(
                    "c.childAttr(%L, %S)?.let { __raw_${f.name} = it; __loc_${f.name} = c.childLocation(); __set_${f.name} = true }\n",
                    nsLit, attrName
                )
            } else {
                cb.add(
                    "c.childAttr(%L, %S)?.let { __raw_${f.name} = it; __set_${f.name} = true }\n",
                    nsLit, attrName
                )
            }
        }
        val hasText = node.textEntries.isNotEmpty()
        val hasNested = node.nestedEntries.isNotEmpty()
        val hasDescend = node.children.isNotEmpty()
        when {
            hasNested -> {
                for (f in node.nestedEntries) {
                    val spec = registry.byFq[f.elemTypeFq]
                        ?: error("missing nested spec for ${f.elemTypeFq}")
                    cb.add("val __n_${f.name}·=·${spec.helperName}(c)\n")
                    if (f.isList) {
                        cb.add("__list_${f.name}.add(__n_${f.name})\n")
                    } else {
                        cb.add("__nested_${f.name} = __n_${f.name}\n")
                        cb.add("__set_${f.name} = true\n")
                    }
                }
            }

            hasText -> {
                val needLoc = node.textEntries.any { needsChildLoc(it) }
                if (needLoc) cb.add("val __t_loc·=·c.childLocation()\n")
                cb.add("val __t = c.childText(false)\n")
                for (f in node.textEntries) {
                    if (f.isList) cb.add("__list_${f.name}.add(__t)\n")
                    else {
                        cb.add("__raw_${f.name} = __t\n")
                        if (needsChildLoc(f)) cb.add("__loc_${f.name} = __t_loc\n")
                        cb.add("__set_${f.name} = true\n")
                    }
                }
            }

            hasDescend -> {
                cb.beginControlFlow("c.forEachChild·{ ln, ns ->\n")
                emitChildrenSwitch(cb, node, registry)
                cb.endControlFlow()
            }

            else -> cb.add("c.skipChild()\n")
        }
    }

    private fun coerceField(f: FieldSpec, convVarFor: Map<String, String>): CodeBlock {
        val cb = CodeBlock.builder()
        cb.add("val __final_${f.name}: %T = ", f.typeName)

        if (f.coerce is Coerce.MapAggregate) {
            if (f.nullable) cb.add("if (!__set_${f.name}) null else __map_${f.name}\n")
            else cb.add("__map_${f.name}\n")
            return cb.build()
        }

        if (f.coerce is Coerce.Nested) {
            if (f.isList) {
                cb.add("__list_${f.name}\n")
            } else {
                val nameLit = CodeBlock.of("%S", f.name)
                val missingThrow = CodeBlock.of("throw %T(%L, __loc)", MISSING_EX, nameLit)
                if (f.nullable) {
                    cb.add("if (!__set_${f.name}) null else __nested_${f.name}\n")
                } else {
                    cb.add("if (!__set_${f.name}) %L else __nested_${f.name}!!\n", missingThrow)
                }
            }
            return cb.build()
        }

        if (f.isList) {
            cb.add("__list_${f.name}.map { __r -> ")
            cb.add(coerceRaw(f, CodeBlock.of("__r"), convVarFor))
            cb.add(" }\n")
            return cb.build()
        }

        val rawVar = CodeBlock.of("__raw_${f.name}")
        val nameLit = CodeBlock.of("%S", f.name)
        val missingThrow = CodeBlock.of("throw %T(%L, __loc)", MISSING_EX, nameLit)
        val orMissing = CodeBlock.of("(%L ?: %L)", rawVar, missingThrow)
        val orEmpty = CodeBlock.of("(%L ?: \"\")", rawVar)

        when (f.source) {
            is Source.Attr -> {
                if (f.nullable) {
                    cb.add("if (%L == null) null else ", rawVar)
                    cb.add(coerceRaw(f, rawVar, convVarFor))
                } else {
                    cb.add(coerceRaw(f, orMissing, convVarFor))
                }
            }

            is Source.Text -> {
                // __raw_X is declared as non-null String at the recordText/subrecordText call,
                // and that call runs unconditionally for any record carrying an @XmlText field.
                // No __set_X gate needed: nullable @XmlText still binds whatever the cursor
                // produced (empty string for an empty body).
                cb.add(coerceRaw(f, rawVar, convVarFor))
            }

            is Source.Child -> {
                val effLoc =
                    if (needsChildLoc(f)) CodeBlock.of("(__loc_${f.name} ?: __loc)")
                    else CodeBlock.of("__loc")
                if (f.nullable) {
                    cb.add("if (!__set_${f.name}) null else ")
                    cb.add(coerceRaw(f, orEmpty, convVarFor, effLoc))
                } else {
                    cb.add("if (!__set_${f.name}) %L else ", missingThrow)
                    cb.add(coerceRaw(f, orEmpty, convVarFor, effLoc))
                }
            }

            is Source.MapEntry -> error("Source.MapEntry should be handled before coerceField switch")
            is Source.PolyChild -> error("Source.PolyChild should be handled via Coerce.Nested before coerceField switch")
        }
        cb.add("\n")
        return cb.build()
    }

    private fun coerceRaw(
        f: FieldSpec,
        raw: CodeBlock,
        convVarFor: Map<String, String>,
        lcExpr: CodeBlock = CodeBlock.of("__loc"),
    ): CodeBlock {
        val nl = CodeBlock.of("%S", f.name)
        val lc = lcExpr
        return when (val coerce = f.coerce) {
            Coerce.AsString -> raw
            Coerce.MapAggregate -> error("Coerce.MapAggregate should be handled before coerceRaw")
            is Coerce.Scalar -> CodeBlock.of("%M(%L, %L, %L)", scalarMember(coerce.kind), nl, raw, lc)
            is Coerce.Temporal -> {
                val m = when (coerce.kind) {
                    TemporalKind.LOCAL_DATE -> COERCE_LOCAL_DATE
                    TemporalKind.LOCAL_DATE_TIME -> COERCE_LOCAL_DATE_TIME
                    TemporalKind.INSTANT -> COERCE_INSTANT
                }
                CodeBlock.of("%M(%L, %L, %S, %L)", m, nl, raw, coerce.pattern, lc)
            }

            is Coerce.Decimal -> CodeBlock.of("%M(%L, %L, %S, %L)", COERCE_BIG_DECIMAL, nl, raw, coerce.pattern, lc)
            is Coerce.Custom -> {
                val varName = convVarFor[coerce.cls.canonicalName] ?: error("no converter var for ${coerce.cls}")
                CodeBlock.of("$varName.convert(%L, %L)", raw, lc)
            }

            is Coerce.Nested -> error("Coerce.Nested should be handled before coerceRaw")
        }
    }

    private fun scalarMember(kind: ScalarKind): MemberName = when (kind) {
        ScalarKind.INT -> COERCE_INT
        ScalarKind.LONG -> COERCE_LONG
        ScalarKind.DOUBLE -> COERCE_DOUBLE
        ScalarKind.BOOLEAN -> COERCE_BOOLEAN
    }

    private fun annotationOf(cls: KSClassDeclaration, fqcn: String): KSAnnotation? =
        cls.annotations.firstOrNull { fq(it) == fqcn }

    private fun fq(a: KSAnnotation): String? =
        a.annotationType.resolve().declaration.qualifiedName?.asString()

    private fun stringArg(a: KSAnnotation, name: String): String? =
        a.arguments.firstOrNull { it.name?.asString() == name }?.value as? String

    private fun booleanArg(a: KSAnnotation, name: String): Boolean? =
        a.arguments.firstOrNull { it.name?.asString() == name }?.value as? Boolean

    enum class ScalarKind { INT, LONG, DOUBLE, BOOLEAN }
    enum class TemporalKind { LOCAL_DATE, LOCAL_DATE_TIME, INSTANT }
    enum class Ctx { RECORD, SUBRECORD }

    sealed class PathSeg {
        data class Element(val ns: String?, val name: String) : PathSeg()
        data class AttrLeaf(val ns: String?, val name: String) : PathSeg()
    }

    sealed class Source {
        data class Attr(val ns: String?, val name: String) : Source()
        data class Text(val preserveWhitespace: Boolean) : Source()
        data class Child(val segments: List<PathSeg>, val descendant: Boolean) : Source()
        data class MapEntry(val entryNs: String?, val entryLocal: String) : Source()
        data class PolyChild(val dispatch: PolyDispatch) : Source()
    }

    sealed class PolyDispatch {
        data class Tag(val variants: List<TagVariant>) : PolyDispatch()
        data class Attr(
            val wrapNs: String?,
            val wrapLocal: String,
            val attrNs: String?,
            val attrLocal: String,
            val variants: List<AttrVariant>,
        ) : PolyDispatch()
    }

    data class TagVariant(val ns: String?, val local: String, val subtypeFq: String)
    data class AttrVariant(val value: String, val subtypeFq: String)

    sealed class Coerce {
        data object AsString : Coerce()
        data class Scalar(val kind: ScalarKind) : Coerce()
        data class Temporal(val kind: TemporalKind, val pattern: String) : Coerce()
        data class Decimal(val pattern: String) : Coerce()
        data class Custom(val cls: ClassName, val fq: String) : Coerce()
        data class Nested(val typeFq: String) : Coerce()
        data object MapAggregate : Coerce()
    }

    data class FieldSpec(
        val name: String,
        val typeName: TypeName,
        val nullable: Boolean,
        val isList: Boolean,
        val elemNullable: Boolean,
        val elemTypeName: TypeName,
        val elemTypeFq: String,
        val source: Source,
        val coerce: Coerce,
        val mapKeyField: FieldSpec? = null,
        val mapValueField: FieldSpec? = null,
    )

    data class QKey(val ns: String?, val local: String)

    class TrieNode {
        val children: MutableMap<QKey, TrieNode> = LinkedHashMap()
        val attrEntries: MutableList<Triple<String?, String, FieldSpec>> = mutableListOf()
        val textEntries: MutableList<FieldSpec> = mutableListOf()
        val nestedEntries: MutableList<FieldSpec> = mutableListOf()
    }

    data class NestedTypeSpec(
        val cls: KSClassDeclaration,
        val typeName: ClassName,
        val helperName: String,
        val nsMap: Map<String, String>,
        val fields: List<FieldSpec>,
    )

    class NestedTypeRegistry {
        val byFq: MutableMap<String, NestedTypeSpec> = LinkedHashMap()
        val inProgress: MutableSet<String> = LinkedHashSet()
    }

    private companion object {
        const val XML_RECORD_FQ = "xmlfluss.XmlRecord"
        const val XML_NS_FQ = "xmlfluss.XmlNs"
        const val XML_ATTR_FQ = "xmlfluss.XmlAttr"
        const val XML_CHILD_FQ = "xmlfluss.XmlChild"
        const val XML_TEXT_FQ = "xmlfluss.XmlText"
        const val XML_FORMAT_FQ = "xmlfluss.XmlFormat"
        const val XML_CONVERTER_FQ = "xmlfluss.XmlConverter"
        const val XML_MAP_FQ = "xmlfluss.XmlMap"
        const val XML_POLYMORPHIC_FQ = "xmlfluss.XmlPolymorphic"
        const val XML_SUBTYPE_FQ = "xmlfluss.XmlSubtype"

        val SCALAR_TEMPORAL_FQS: Set<String> = setOf(
            "kotlin.String", "kotlin.Int", "kotlin.Long", "kotlin.Double", "kotlin.Boolean",
            "java.time.LocalDate", "java.time.LocalDateTime", "java.time.Instant",
            "java.math.BigDecimal",
        )

        val FLOW = ClassName("kotlinx.coroutines.flow", "Flow")
        val FLOW_BUILDER = MemberName("kotlinx.coroutines.flow", "flow")
        val INPUT_STREAM = ClassName("java.io", "InputStream")
        val XML_READ_CURSOR = ClassName("xmlfluss.runtime", "XmlReadCursor")
        val COMPILED_PATH = ClassName("xmlfluss.path", "CompiledPath")
        val PATHS_COMPILE = MemberName(ClassName("xmlfluss.runtime", "Paths"), "compile")
        val MISSING_EX = ClassName("xmlfluss", "XmlParseException", "Missing")
        val MUTABLE_LIST = ClassName("kotlin.collections", "MutableList")
        val LINKED_MAP_OF = MemberName("kotlin.collections", "linkedMapOf")
        val LOCATION = ClassName("xmlfluss", "Location")
        val LOCATION_NULLABLE = LOCATION.copy(nullable = true)

        val COERCE_INT = MemberName(ClassName("xmlfluss.runtime", "Coercions"), "toInt")
        val COERCE_LONG = MemberName(ClassName("xmlfluss.runtime", "Coercions"), "toLong")
        val COERCE_DOUBLE = MemberName(ClassName("xmlfluss.runtime", "Coercions"), "toDouble")
        val COERCE_BOOLEAN = MemberName(ClassName("xmlfluss.runtime", "Coercions"), "toBoolean")
        val COERCE_LOCAL_DATE = MemberName(ClassName("xmlfluss.runtime", "Coercions"), "toLocalDate")
        val COERCE_LOCAL_DATE_TIME = MemberName(ClassName("xmlfluss.runtime", "Coercions"), "toLocalDateTime")
        val COERCE_INSTANT = MemberName(ClassName("xmlfluss.runtime", "Coercions"), "toInstant")
        val COERCE_BIG_DECIMAL = MemberName(ClassName("xmlfluss.runtime", "Coercions"), "toBigDecimal")
        val STRING_NULLABLE = STRING.copy(nullable = true)
    }
}
