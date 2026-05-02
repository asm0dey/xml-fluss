package xmlfluss.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MAP
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.writeTo
import xmlfluss.codegen.plan.DispatchPlan
import java.time.LocalDate
import javax.annotation.processing.Generated
import xmlfluss.codegen.model.Coerce as CoreCoerce
import xmlfluss.codegen.model.NestedRegistry as CoreNestedRegistry
import xmlfluss.codegen.model.RecordSpec as CoreRecordSpec
import xmlfluss.codegen.model.TypeRef as CoreTypeRef

/**
 * Build the `${ClassName}Parser` `FileSpec` and write it via [codeGen]. Pure code-shape work
 * — every routing decision was resolved in `DispatchPlanBuilder`; the emitter only translates
 * the plan into KotlinPoet specs and emits inline source.
 */
internal fun emitFile(
    coreSpec: CoreRecordSpec,
    plan: DispatchPlan,
    registry: CoreNestedRegistry,
    codeGen: CodeGenerator,
) {
    val origin = coreSpec.originatingHandle() as? KSClassDeclaration
        ?: error(
            "KSP originatingHandle must be a KSClassDeclaration for ${coreSpec.packageName()}.${coreSpec.simpleName()}, got " +
                (coreSpec.originatingHandle()?.javaClass?.name ?: "null"),
        )
    val pkg = coreSpec.packageName()
    val recordTypeName = TypeRefs.toClassName(CoreTypeRef.of(pkg, coreSpec.simpleName()))
    val parserName = "${coreSpec.simpleName()}Parser"

    val flowOfRecord = FLOW.parameterizedBy(recordTypeName)

    val nsProp = PropertySpec.builder("NS", MAP.parameterizedBy(STRING, STRING))
        .addModifiers(KModifier.PRIVATE)
        .initializer(buildNsInitializer(coreSpec.nsMap()))
        .build()

    val pathProp = PropertySpec.builder("PATH", COMPILED_PATH)
        .addModifiers(KModifier.PRIVATE)
        .initializer("%M(%S, NS)", PATHS_COMPILE, coreSpec.recordPath())
        .build()

    val convVarFor = mutableMapOf<String, String>()
    val convProps = mutableListOf<PropertySpec>()
    fun registerConverter(c: CoreCoerce.Custom) {
        val cls = TypeRefs.toClassName(c.converterClass())
        val key = cls.canonicalName
        if (key in convVarFor) return
        val varName = "__conv_${convVarFor.size}"
        convVarFor[key] = varName
        convProps += PropertySpec.builder(varName, cls)
            .addModifiers(KModifier.PRIVATE)
            .initializer("%T()", cls)
            .build()
    }
    for (f in coreSpec.fields()) (f.coerce() as? CoreCoerce.Custom)?.let(::registerConverter)
    for (n in registry.byFq().values) for (f in n.fields()) (f.coerce() as? CoreCoerce.Custom)?.let(::registerConverter)

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
        .addCode(buildParseBody(recordTypeName, coreSpec.fields(), convVarFor, registry, plan))
        .build()

    val nestedHelpers = registry.byFq().entries.map { (fq, spec) ->
        val nestedPlan = plan.byFq()[fq]
            ?: error("DispatchPlan missing for nested record $fq")
        val nestedType = TypeRefs.toClassName(
            CoreTypeRef.of(spec.packageName(), spec.simpleName())
        )
        FunSpec.builder(registry.helperName(fq))
            .addModifiers(KModifier.PRIVATE)
            .addParameter("c", XML_READ_CURSOR)
            .returns(nestedType)
            .addCode(buildSubrecordBody(nestedType, spec, convVarFor, registry, nestedPlan))
            .build()
    }

    val parserBuilder = TypeSpec.objectBuilder(parserName)
        .addProperty(nsProp)
        .addProperty(pathProp)
    for (p in convProps) parserBuilder.addProperty(p)
    for (h in nestedHelpers) parserBuilder.addFunction(h)
    parserBuilder.addFunction(parseFun)
    parserBuilder.addOriginatingKSFile(origin.containingFile!!)

    val file = FileSpec.builder(pkg, parserName)
        .addAnnotation(
            AnnotationSpec.builder(Generated::class)
                .addMember("%S", "xml-fluss-ksp")
                .addMember("date = %S", LocalDate.now().toString())
                .build()
        )
        .addAnnotation(
            // Surviving suppressions:
            //  - RedundantExplicitType: PropertySpec always emits a type for object members
            //    (NS, PATH, __conv_<N>); we can't drop it without a different KotlinPoet API.
            //  - RedundantVisibilityModifier: KotlinPoet emits `public` on top-level objects
            //    and member functions by default; the only alternative is to change visibility.
            //  - LocalVariableName: locals are prefixed with `__` to guarantee no collision
            //    with user-declared record fields (e.g. a record field named `t` would shadow
            //    the synthetic `__t`). Renaming would require name-mangling logic.
            AnnotationSpec.builder(Suppress::class)
                .addMember("\"RedundantExplicitType\", \"RedundantVisibilityModifier\", \"LocalVariableName\"")
                .build()
        )
        .addType(parserBuilder.build())
        .build()

    file.writeTo(codeGen, aggregating = false)
}
