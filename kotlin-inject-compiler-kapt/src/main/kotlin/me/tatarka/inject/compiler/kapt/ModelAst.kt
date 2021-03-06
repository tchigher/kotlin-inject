package me.tatarka.inject.compiler.kapt

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import kotlinx.metadata.*
import kotlinx.metadata.jvm.annotations
import kotlinx.metadata.jvm.syntheticMethodForAnnotations
import me.tatarka.inject.compiler.*
import javax.annotation.processing.Messager
import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.element.*
import javax.lang.model.type.*
import javax.lang.model.util.ElementFilter
import javax.lang.model.util.Elements
import javax.lang.model.util.SimpleTypeVisitor7
import javax.lang.model.util.Types
import javax.tools.Diagnostic
import kotlin.reflect.KClass

interface ModelAstProvider :
        AstProvider {

    val env: ProcessingEnvironment

    val types: Types get() = env.typeUtils
    val elements: Elements get() = env.elementUtils
    val messager: Messager get() = env.messager

    override val messenger: Messenger
        get() = ModelAstMessenger(messager)

    fun TypeElement.toAstClass(): AstClass {
        return ModelAstClass(this@ModelAstProvider, this, metadata?.toKmClass())
    }

    override fun findFunctions(packageName: String, functionName: String): List<AstFunction> {
        val packageElement = elements.getPackageElement(packageName)
        val results = mutableListOf<AstFunction>()
        for (element in ElementFilter.typesIn(packageElement.enclosedElements)) {
            for (function in ElementFilter.methodsIn(element.enclosedElements)) {
                if (function.simpleName.contentEquals(functionName)
                        && function.modifiers.contains(Modifier.STATIC) && function.modifiers.contains(Modifier.STATIC) && function.modifiers.contains(
                                Modifier.FINAL
                        )
                ) {
                    val metadata = element.metadata?.toKmPackage() ?: continue
                    val kmFunction = metadata.functions.find { it.name == functionName } ?: continue
                    results.add(ModelAstFunction(this, element, function, kmFunction))
                }
            }
        }
        return results
    }

    override fun declaredTypeOf(klass: KClass<*>, vararg astTypes: AstType): AstType {
        val type = elements.getTypeElement(klass.java.canonicalName)
        return ModelAstType(
                this,
                declaredType(type, astTypes.asList()),
                klass.toKmType()
        )
    }

    private fun declaredType(type: TypeElement, astTypes: List<AstType>) = types.getDeclaredType(type, *astTypes.map {
        (it as ModelAstType).type
    }.toTypedArray())

    override fun TypeSpec.Builder.addOriginatingElement(astClass: AstClass): TypeSpec.Builder = apply {
        require(astClass is ModelAstClass)
        addOriginatingElement(astClass.element)
    }
}

class ModelAstMessenger(private val messager: Messager) : Messenger {
    override fun warn(message: String, element: AstElement) {
        print(Diagnostic.Kind.WARNING, message, element)
    }

    override fun error(message: String, element: AstElement) {
        print(Diagnostic.Kind.ERROR, message, element)
    }

    private fun print(kind: Diagnostic.Kind, message: String, element: AstElement) {
        messager.printMessage(kind, message, (element as ModelAstElement).element)
    }
}

private interface ModelAstElement : ModelAstProvider, AstAnnotated {
    val element: Element

    override fun hasAnnotation(className: String): Boolean {
        return element.hasAnnotation(className)
    }

    override fun typeAnnotatedWith(className: String): AstClass? {
        return element.typeAnnotatedWith(className)?.toAstClass()
    }
}

private interface ModelAstMethod : ModelAstElement {
    override val element: ExecutableElement
}

private class ModelBasicElement(provider: ModelAstProvider, override val element: Element) : AstBasicElement(),
        ModelAstElement, ModelAstProvider by provider {
    override val simpleName: String get() = element.simpleName.toString()
}

private class ModelAstClass(
        provider: ModelAstProvider,
        override val element: TypeElement,
        val kmClass: KmClass?
) : AstClass(),
        ModelAstElement, ModelAstProvider by provider {

    override val packageName: String get() = elements.getPackageOf(element).qualifiedName.toString()

    override val name: String get() = element.simpleName.toString()

    override val modifiers: Set<AstModifier> by lazy {
        collectModifiers(
                kmClass?.flags
        )
    }

    override val companion: AstClass? by lazy {
        val companionName = kmClass?.companionObject ?: return@lazy null
        val companionType = ElementFilter.typesIn(element.enclosedElements).firstOrNull { type ->
            type.simpleName.contentEquals(companionName)
        }
        companionType?.toAstClass()
    }

    override val superTypes: List<AstClass> by lazy {
        mutableListOf<AstClass>().apply {
            val superclassType = element.superclass
            if (superclassType !is NoType) {
                val superclass = provider.types.asElement(superclassType) as TypeElement
                add(superclass.toAstClass())
            }
            addAll(element.interfaces.mapNotNull { ifaceType ->
                val iface = provider.types.asElement(ifaceType) as TypeElement
                iface.toAstClass()
            })
        }
    }

    override val primaryConstructor: AstConstructor? by lazy {
        ElementFilter.constructorsIn(element.enclosedElements).mapNotNull { constructor ->
            //TODO: not sure how to match constructors
            ModelAstConstructor(
                    this,
                    this,
                    constructor,
                    kmClass?.constructors?.first()
            )
        }.firstOrNull()
    }

    override val methods: List<AstMethod> by lazy {
        ElementFilter.methodsIn(element.enclosedElements).mapNotNull<ExecutableElement, AstMethod> { method ->
            if (kmClass != null) {
                for (property in kmClass.properties) {
                    if (method.matches(property)) {
                        return@mapNotNull ModelAstProperty(
                                this,
                                element,
                                method,
                                property
                        )
                    }
                }
                for (function in kmClass.functions) {
                    if (method.matches(function)) {
                        return@mapNotNull ModelAstFunction(
                                this,
                                element,
                                method,
                                function
                        )
                    }
                }
            }
            null
        }
    }

    override val type: AstType by lazy {
        ModelAstType(provider, element.asType(), kmClass?.type)
    }

    override fun asClassName(): ClassName = element.asClassName()

    override fun equals(other: Any?): Boolean = other is ModelAstElement && element == other.element

    override fun hashCode(): Int = element.hashCode()
}

private class ModelAstConstructor(
        provider: ModelAstProvider,
        parent: AstClass,
        override val element: ExecutableElement,
        private val kmConstructor: KmConstructor?
) : AstConstructor(parent),
        ModelAstElement, ModelAstProvider by provider {

    override val parameters: List<AstParam> by lazy {
        element.parameters.mapNotNull { element ->
            if (kmConstructor != null) {
                for (parameter in kmConstructor.valueParameters) {
                    if (element.simpleName.contentEquals(parameter.name)) {
                        return@mapNotNull ModelAstParam(
                                this,
                                element,
                                parameter
                        )
                    }
                }
            }
            null
        }
    }
}

private class ModelAstFunction(
        provider: ModelAstProvider,
        val parent: TypeElement,
        override val element: ExecutableElement,
        private val kmFunction: KmFunction
) : AstFunction(),
        ModelAstMethod, ModelAstProvider by provider {

    override val name: String get() = kmFunction.name

    override val modifiers: Set<AstModifier> by lazy {
        collectModifiers(
                kmFunction.flags
        )
    }

    override val returnType: AstType
        get() = ModelAstType(
                this,
                element.returnType,
                kmFunction.returnType
        )

    override fun returnTypeFor(enclosingClass: AstClass): AstType {
        require(enclosingClass is ModelAstClass)
        val declaredType = enclosingClass.element.asType() as DeclaredType
        val methodType = types.asMemberOf(declaredType, element) as ExecutableType
        return ModelAstType(
                this,
                methodType.returnType,
                kmFunction.returnType
        )
    }

    override val receiverParameterType: AstType?
        get() = kmFunction.receiverParameterType?.let {
            ModelAstType(this, element.parameters[0].asType(), it)
        }

    override val parameters: List<AstParam> by lazy {
        element.parameters.mapNotNull { element ->
            for (parameter in kmFunction.valueParameters) {
                if (element.simpleName.contentEquals(parameter.name)) {
                    return@mapNotNull ModelAstParam(
                            this,
                            element,
                            parameter
                    )
                }
            }
            null
        }
    }

    override fun overrides(other: AstMethod): Boolean {
        require(other is ModelAstMethod)
        return elements.overrides(element, other.element, parent)
    }

    override fun asMemberName(): MemberName {
        return MemberName(elements.getPackageOf(element).qualifiedName.toString(), name)
    }
}

private class ModelAstProperty(
        provider: ModelAstProvider,
        val parent: TypeElement,
        override val element: ExecutableElement,
        private val kmProperty: KmProperty
) : AstProperty(),
        ModelAstMethod, ModelAstProvider by provider {

    override val name: String get() = kmProperty.name

    override val modifiers: Set<AstModifier> by lazy {
        val result = mutableSetOf<AstModifier>()
        val flags = kmProperty.flags
        if (Flag.Common.IS_PRIVATE(flags)) {
            result.add(AstModifier.PRIVATE)
        }
        if (Flag.Common.IS_ABSTRACT(flags)) {
            result.add(AstModifier.ABSTRACT)
        }
        result
    }

    override val returnType: AstType
        get() = ModelAstType(
                this,
                element.returnType,
                kmProperty.returnType
        )

    override fun returnTypeFor(enclosingClass: AstClass): AstType {
        require(enclosingClass is ModelAstClass)
        val declaredType = enclosingClass.element.asType() as DeclaredType
        val methodType = types.asMemberOf(declaredType, element) as ExecutableType
        return ModelAstType(
                this,
                methodType.returnType,
                kmProperty.returnType
        )
    }

    override val receiverParameterType: AstType?
        get() = kmProperty.receiverParameterType?.let {
            ModelAstType(this, element.parameters[0].asType(), it)
        }

    private val annotatedElement: Element? by lazy {
        val javaName = kmProperty.syntheticMethodForAnnotations?.name ?: return@lazy null
        for (method in ElementFilter.methodsIn(element.enclosingElement.enclosedElements)) {
            if (method.simpleName.contentEquals(javaName)) {
                return@lazy method
            }
        }
        null
    }

    override fun overrides(other: AstMethod): Boolean {
        require(other is ModelAstMethod)
        return elements.overrides(element, other.element, parent)
    }

    override fun hasAnnotation(className: String): Boolean {
        return annotatedElement?.hasAnnotation(className) == true
    }

    override fun typeAnnotatedWith(className: String): AstClass? {
        return annotatedElement?.typeAnnotatedWith(className)?.toAstClass()
    }

    override fun asMemberName(): MemberName {
        return MemberName(elements.getPackageOf(element).qualifiedName.toString(), name)
    }
}

private class ModelAstType(
        provider: ModelAstProvider,
        val type: TypeMirror,
        private val kmType: KmType?
) : AstType(),
        ModelAstElement, ModelAstProvider by provider {

    override val element: Element get() = types.asElement(type)

    override val name: String by lazy {
        asTypeName().toString()
    }

    override val annotations: List<AstAnnotation> by lazy {
        if (kmType != null) {
            kmType.annotations.map { annotation ->
                val mirror = provider.elements.getTypeElement(annotation.className.replace('/', '.'))
                ModelAstAnnotation(
                        this,
                        mirror.asType() as DeclaredType,
                        annotation
                )
            }
        } else {
            emptyList()
        }
    }

    override val typeAliasName: String?
        get() {
            return (kmType?.abbreviatedType?.classifier as? KmClassifier.TypeAlias)?.name?.replace('/', '.')
        }

    override val arguments: List<AstType> by lazy {
        val kmArgs: List<KmType?> = kmType?.arguments?.map { it.type } ?: emptyList()
        val args: List<TypeMirror> = (type as DeclaredType).typeArguments
        if (args.size == kmArgs.size) {
            args.zip(kmArgs) { a1, a2 -> ModelAstType(this, a1, a2) }
        } else {
            args.map { ModelAstType(this, it, null) }
        }
    }

    override fun isUnit(): Boolean = type is NoType

    override fun asElement(): AstBasicElement =
            ModelBasicElement(this, element)

    override fun toAstClass(): AstClass = (element as TypeElement).toAstClass()

    private fun rawTypeName(): ClassName {
        val kotlinClassName =
                (kmType?.classifier as? KmClassifier.Class)?.name?.replace('/', '.')?.let { ClassName.bestGuess(it) }
                        ?: ((type as DeclaredType).asElement() as TypeElement).asClassName()
        return when (kotlinClassName.canonicalName) {
            "java.util.Map" -> ClassName("kotlin.collections", "Map")
            "java.util.Set" -> ClassName("kotlin.collections", "Set")
            "java.lang.String" -> ClassName("kotlin", "String")
            else -> kotlinClassName
        }
    }

    override fun asTypeName(): TypeName {
        return type.accept(object : SimpleTypeVisitor7<TypeName, Void?>() {
            override fun visitPrimitive(t: PrimitiveType, p: Void?): TypeName {
                return when (t.kind) {
                    TypeKind.BOOLEAN -> BOOLEAN
                    TypeKind.BYTE -> BYTE
                    TypeKind.SHORT -> SHORT
                    TypeKind.INT -> INT
                    TypeKind.LONG -> LONG
                    TypeKind.CHAR -> CHAR
                    TypeKind.FLOAT -> FLOAT
                    TypeKind.DOUBLE -> DOUBLE
                    else -> throw AssertionError()
                }
            }

            override fun visitDeclared(t: DeclaredType, p: Void?): TypeName {
                val rawType: ClassName = rawTypeName().copy(nullable = isNullable) as ClassName
                val enclosingType = t.enclosingType
                val enclosing = if (enclosingType.kind != TypeKind.NONE &&
                        Modifier.STATIC !in t.asElement().modifiers
                )
                    enclosingType.accept(this, null) else
                    null
                if (t.typeArguments.isEmpty() && enclosing !is ParameterizedTypeName) {
                    return rawType
                }

                val typeArgumentNames = mutableListOf<TypeName>()
                for (typeArgument in arguments) {
                    typeArgumentNames += typeArgument.asTypeName()
                }
                return if (enclosing is ParameterizedTypeName)
                    enclosing.nestedClass(rawType.simpleName, typeArgumentNames) else
                    rawType.parameterizedBy(typeArgumentNames)
            }

            override fun visitError(t: ErrorType, p: Void?): TypeName {
                return visitDeclared(t, p)
            }

            override fun visitArray(t: ArrayType, p: Void?): ParameterizedTypeName {
                return ARRAY.parameterizedBy(t.componentType.asTypeName())
            }

            override fun visitTypeVariable(
                    t: TypeVariable,
                    p: Void?
            ): TypeName {
                return t.asTypeVariableName()
            }

            override fun visitWildcard(t: WildcardType, p: Void?): TypeName {
                return t.asWildcardTypeName()
            }

            override fun visitNoType(t: NoType, p: Void?): TypeName {
                if (t.kind == TypeKind.VOID) return UNIT
                return super.visitUnknown(t, p)
            }

            override fun defaultAction(e: TypeMirror?, p: Void?): TypeName {
                throw IllegalArgumentException("Unexpected type mirror: " + e!!)
            }
        }, null)
    }

    override fun isAssignableFrom(other: AstType): Boolean {
        require(other is ModelAstType)
        return types.isAssignable(other.type, type)
    }

    override fun equals(other: Any?): Boolean {
        if (other !is AstType) return false
        return asTypeName() == other.asTypeName()
    }

    override fun hashCode(): Int {
        return asTypeName().hashCode()
    }

    val isNullable: Boolean
        get() {
            if (kmType == null) return false
            return Flag.Type.IS_NULLABLE(kmType.flags)
        }
}

private class ModelAstAnnotation(
        provider: ModelAstProvider,
        val annotationType: DeclaredType,
        private val kmAnnotation: KmAnnotation
) : AstAnnotation(),
        ModelAstElement, ModelAstProvider by provider {
    override val element: Element get() = types.asElement(annotationType)

    override fun equals(other: Any?): Boolean {
        if (other !is ModelAstAnnotation) return false
        return kmAnnotation == other.kmAnnotation
    }

    override fun hashCode(): Int {
        return kmAnnotation.hashCode()
    }

    override fun toString(): String {
        return "@$annotationType(${
            kmAnnotation.arguments.toList()
                    .joinToString(separator = ", ") { (name, value) -> "$name=${value.value}" }
        })"
    }
}

private class ModelAstParam(
        provider: ModelAstProvider,
        override val element: VariableElement,
        val kmValueParameter: KmValueParameter
) : AstParam(),
        ModelAstElement, ModelAstProvider by provider {

    override val name: String get() = kmValueParameter.name

    override val type: AstType by lazy {
        ModelAstType(this, element.asType(), kmValueParameter.type!!)
    }

    override fun asParameterSpec(): ParameterSpec = ParameterSpec.get(element)
}

private val KmClass.type: KmType
    get() = KmType(0).apply {
        classifier = KmClassifier.Class(name)
    }

private val KmAnnotation.type: KmType
    get() = KmType(0).apply {
        classifier = KmClassifier.Class(className)
    }

private fun KClass<*>.toKmType(): KmType = KmType(0).apply {
    classifier = KmClassifier.Class(java.canonicalName)
}

private fun collectModifiers(flags: Flags?): Set<AstModifier> {
    val result = mutableSetOf<AstModifier>()
    if (flags == null) return result
    if (Flag.Common.IS_PRIVATE(flags)) {
        result.add(AstModifier.PRIVATE)
    }
    if (Flag.Common.IS_ABSTRACT(flags)) {
        result.add(AstModifier.ABSTRACT)
    }
    return result
}

private fun String.typeAliasTypeName(): ClassName {
    val lastDot = lastIndexOf('.')
    return if (lastDot < 0) {
        ClassName("", this)
    } else {
        ClassName(substring(0, lastDot), substring(lastDot + 1))
    }
}

val AstClass.element: TypeElement
    get() {
        require(this is ModelAstClass)
        return element
    }
