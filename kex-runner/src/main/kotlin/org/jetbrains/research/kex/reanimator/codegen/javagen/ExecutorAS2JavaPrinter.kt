package org.jetbrains.research.kex.reanimator.codegen.javagen

import org.jetbrains.research.kex.ExecutionContext
import org.jetbrains.research.kex.asm.util.Visibility
import org.jetbrains.research.kex.config.kexConfig
import org.jetbrains.research.kex.descriptor.*
import org.jetbrains.research.kex.ktype.*
import org.jetbrains.research.kex.parameters.Parameters
import org.jetbrains.research.kex.reanimator.actionsequence.*
import org.jetbrains.research.kex.util.kapitalize
import org.jetbrains.research.kfg.type.ArrayType
import org.jetbrains.research.kfg.type.ClassType
import org.jetbrains.research.kfg.type.PrimaryType
import org.jetbrains.research.kfg.type.Type
import org.jetbrains.research.kthelper.assert.unreachable
import org.jetbrains.research.kthelper.logging.log
import org.jetbrains.research.kthelper.runIf

private val generateSetup by lazy { kexConfig.getBooleanValue("apiGeneration", "generateSetup", false) }

class ExecutorAS2JavaPrinter(
    ctx: ExecutionContext,
    packageName: String,
    klassName: String,
    val setupName: String
) : ActionSequence2JavaPrinter(ctx, packageName, klassName) {
    private val testParams = mutableListOf<JavaBuilder.JavaClass.JavaField>()
    private lateinit var newInstance: JavaBuilder.JavaFunction
    private lateinit var newArray: JavaBuilder.JavaFunction
    private val newPrimitiveArrayMap = mutableMapOf<String, JavaBuilder.JavaFunction>()
    private lateinit var getField: JavaBuilder.JavaFunction
    private lateinit var setField: JavaBuilder.JavaFunction
    private val setPrimitiveFieldMap = mutableMapOf<String, JavaBuilder.JavaFunction>()
    private lateinit var setElement: JavaBuilder.JavaFunction
    private val setPrimitiveElementMap = mutableMapOf<String, JavaBuilder.JavaFunction>()
    private lateinit var callConstructor: JavaBuilder.JavaFunction
    private lateinit var callMethod: JavaBuilder.JavaFunction
    private var klassCounter = 0

    init {
        initReflection()
    }

    private val KexType.primitiveName: String?
        get() = when (this) {
            is KexBool -> "boolean"
            is KexByte -> "byte"
            is KexChar -> "char"
            is KexShort -> "short"
            is KexInt -> "int"
            is KexLong -> "long"
            is KexFloat -> "float"
            is KexDouble -> "double"
            else -> null
        }

    private fun initReflection() {
        with(builder) {
            import("java.lang.Class")
            import("java.lang.reflect.Method")
            import("java.lang.reflect.Constructor")
            import("java.lang.reflect.Field")
            import("java.lang.reflect.Array")
            import("java.lang.reflect.Modifier")
            import("sun.misc.Unsafe")

            with(klass) {
                field("UNSAFE", type("Unsafe")) {
                    visibility = Visibility.PRIVATE
                    modifiers += "static"
                    modifiers += "final"
                }

                static {
                    aTry {
                        +"final Field uns = Unsafe.class.getDeclaredField(\"theUnsafe\")"
                        +"uns.setAccessible(true)"
                        +"UNSAFE = (Unsafe) uns.get(null)"
                    }.catch {
                        exceptions += type("Throwable")
                        +"throw new RuntimeException()"
                    }
                }

                method("newInstance") {
                    arguments += arg("klass", type("Class<?>"))
                    returnType = type("Object")
                    visibility = Visibility.PRIVATE
                    modifiers += "static"
                    exceptions += "Throwable"

                    +"Object instance = klass.cast(UNSAFE.allocateInstance(klass))"
                    +"return instance"
                }
                val primitiveTypes = listOf("boolean", "byte", "char", "short", "int", "long", "double", "float")

                newInstance = method("newInstance") {
                    arguments += arg("klass", type("String"))
                    returnType = type("Object")
                    visibility = Visibility.PRIVATE
                    modifiers += "static"
                    exceptions += "Throwable"

                    +"Class<?> reflect = Class.forName(klass)"
                    +"return newInstance(reflect)"
                }

                newArray = method("newArray") {
                    arguments += arg("elementType", type("String"))
                    arguments += arg("length", type("int"))
                    returnType = type("Object")
                    visibility = Visibility.PRIVATE
                    modifiers += "static"
                    exceptions += "Throwable"

                    +"Class<?> reflect = Class.forName(elementType)"
                    +"return Array.newInstance(reflect, length)"
                }

                for (type in primitiveTypes) {
                    newPrimitiveArrayMap[type] = method("new${type.kapitalize()}Array") {
                        arguments += arg("length", type("int"))
                        returnType = type("Object")
                        visibility = Visibility.PRIVATE
                        modifiers += "static"
                        exceptions += "Throwable"

                        +"return Array.newInstance(${type}.class, length)"
                    }
                }

                getField = method("getField") {
                    arguments += arg("klass", type("Class<?>"))
                    arguments += arg("name", type("String"))
                    returnType = type("Field")
                    visibility = Visibility.PRIVATE
                    modifiers += "static"
                    exceptions += "Throwable"

                    +"Field result = null"
                    +"Class<?> current = klass"
                    aDo {
                        aTry {
                            +"result = current.getDeclaredField(name)"
                        }.catch {
                            exceptions += type("Throwable")
                        }
                        +"current = current.getSuperclass()"
                    }.aWhile("current != null")
                    anIf("result == null") {
                        +"throw new NoSuchFieldException()"
                    }
                    +"return result"
                }

                setField = method("setField") {
                    arguments += arg("instance", type("Object"))
                    arguments += arg("klass", type("Class<?>"))
                    arguments += arg("name", type("String"))
                    arguments += arg("value", type("Object"))
                    returnType = void
                    visibility = Visibility.PRIVATE
                    modifiers += "static"
                    exceptions += "Throwable"

                    +"Field field = ${getField.name}(klass, name)"
                    +"field.setAccessible(true)"
                    +"field.set(instance, value)"
                }

                for (type in primitiveTypes) {
                    setPrimitiveFieldMap[type] = method("set${type.kapitalize()}Field") {
                        arguments += arg("instance", type("Object"))
                        arguments += arg("klass", type("Class<?>"))
                        arguments += arg("name", type("String"))
                        arguments += arg("value", type(type))
                        returnType = void
                        visibility = Visibility.PRIVATE
                        modifiers += "static"
                        exceptions += "Throwable"

                        +"Field field = ${getField.name}(klass, name)"
                        +"Field mods = Field.class.getDeclaredField(\"modifiers\")"
                        +"mods.setAccessible(true)"
                        +"int modifiers = mods.getInt(field)"
                        +"mods.setInt(field, modifiers & ~Modifier.FINAL)"
                        +"field.setAccessible(true)"
                        +"field.set${type.kapitalize()}(instance, value)"
                    }
                }

                setElement = method("setElement") {
                    arguments += arg("array", type("Object"))
                    arguments += arg("index", type("int"))
                    arguments += arg("element", type("Object"))
                    returnType = void
                    visibility = Visibility.PRIVATE
                    modifiers += "static"
                    exceptions += "Throwable"

                    +"Array.set(array, index, element)"
                }
                for (type in primitiveTypes) {
                    setPrimitiveElementMap[type] = method("set${type.kapitalize()}Element") {
                        arguments += arg("array", type("Object"))
                        arguments += arg("index", type("int"))
                        arguments += arg("element", type(type))
                        returnType = void
                        visibility = Visibility.PRIVATE
                        modifiers += "static"
                        exceptions += "Throwable"

                        +"Array.set${type.kapitalize()}(array, index, element)"
                    }
                }

                callConstructor = method("callConstructor") {
                    arguments += arg("klass", type("Class<?>"))
                    arguments += arg("argTypes", type("Class<?>[]"))
                    arguments += arg("args", type("Object[]"))
                    returnType = type("Object")
                    visibility = Visibility.PRIVATE
                    modifiers += "static"
                    exceptions += "Throwable"

                    +"Constructor<?> method = klass.getDeclaredConstructor(argTypes)"
                    +"method.setAccessible(true)"
                    +"return method.newInstance(args)"
                }

                callMethod = method("callMethod") {
                    arguments += arg("klass", type("Class<?>"))
                    arguments += arg("name", type("String"))
                    arguments += arg("argTypes", type("Class<?>[]"))
                    arguments += arg("instance", type("Object"))
                    arguments += arg("args", type("Object[]"))
                    returnType = type("Object")
                    visibility = Visibility.PRIVATE
                    modifiers += "static"
                    exceptions += "Throwable"

                    +"Method method = klass.getDeclaredMethod(name, argTypes)"
                    +"method.setAccessible(true)"
                    +"return method.invoke(instance, args)"
                }
            }
        }
    }

    override fun cleanup() {
        super.cleanup()
        testParams.clear()
    }

    override fun printActionSequence(
        testName: String,
        method: org.jetbrains.research.kfg.ir.Method,
        actionSequences: Parameters<ActionSequence>
    ) {
        cleanup()

        for (cs in actionSequences.asList)
            resolveTypes(cs)

        with(builder) {
            import("org.junit.Before")

            with(klass) {
                if (generateSetup) {
                    runIf(!method.isConstructor) {
                        actionSequences.instance?.let {
                            testParams += field(it.name, type("Object"))
                        }
                    }
                    actionSequences.arguments.forEach { arg ->
                        val type = when (arg) {
                            is UnknownSequence -> arg.type
                            is ActionList -> arg.firstNotNullOfOrNull {
                                when (it) {
                                    is DefaultConstructorCall -> it.klass.type
                                    is ConstructorCall -> it.constructor.klass.type
                                    is NewArray -> it.asArray
                                    is ExternalConstructorCall -> it.constructor.returnType
                                    is InnerClassConstructorCall -> it.constructor.klass.type
                                    is EnumValueCreation -> it.klass.type
                                    is StaticFieldGetter -> it.field.type
                                    else -> null
                                }
                            }
                                ?: unreachable { log.error("Unexpected call in arg") }
                            else -> unreachable { log.error("Unexpected call in arg") }
                        }
                        val fieldType = type.kexType.primitiveName?.let { type(it) } ?: type("Object")
                        if (testParams.all { it.name != arg.name })
                            testParams += field(arg.name, fieldType)
                    }
                }

                current = if (generateSetup) method(setupName) {
                    returnType = void
                    annotations += "Before"
                    exceptions += "Throwable"
                } else method(testName) {
                    returnType = void
                    annotations += "Test"
                    exceptions += "Throwable"
                }
            }
        }

        runIf(!method.isConstructor) {
            actionSequences.instance?.printAsJava()
        }
        for (cs in actionSequences.asList)
            cs.printAsJava()

        printedStacks.clear()
        if (generateSetup) {
            with(builder) {
                with(klass) {
                    current = method(testName) {
                        returnType = void
                        annotations += "Test"
                        exceptions += "Throwable"
                    }
                }
            }
        }

        printTestCall(method, actionSequences)
    }

    override fun printVarDeclaration(name: String, type: ASType) = when {
        testParams.any { it.name == name } -> name
        else -> super.printVarDeclaration(name, type)
    }

    override fun printUnknownSequence(sequence: UnknownSequence): List<String> {
        val descriptor = sequence.target
        printedStacks -= "${descriptor.term}"
        val result = mutableListOf<String>()
        val names = printDeclarations(descriptor, result)
        printInsides(descriptor, result, names)
        return result
    }

    val Type.klassType: String
        get() = when (this) {
            is PrimaryType -> "${kexType.primitiveName}.class"
            is ClassType -> "Class.forName(\"${klass.canonicalDesc}\")"
            is ArrayType -> "Array.newInstance(${component.klassType}, 0).getClass()"
            else -> unreachable { }
        }

    private fun printTestCall(
        method: org.jetbrains.research.kfg.ir.Method,
        actionSequences: Parameters<ActionSequence>
    ) =
        with(current) {
            +"Class<?> klass = Class.forName(\"${method.klass.canonicalDesc}\")"
            +"Class<?>[] argTypes = new Class<?>[${method.argTypes.size}]"
            for ((index, type) in method.argTypes.withIndex()) {
                +"argTypes[$index] = ${type.klassType}"
            }
            +"Object[] args = new Object[${method.argTypes.size}]"
            for ((index, arg) in actionSequences.arguments.withIndex()) {
                +"args[$index] = ${arg.name}"
            }
            +when {
                method.isConstructor -> "${callConstructor.name}(klass, argTypes, args)"
                else -> "${callMethod.name}(klass, \"${method.name}\", argTypes, ${actionSequences.instance?.name}, args)"
            }
        }

    protected val ConstantDescriptor.asConstant: String
        get() = when (this) {
            is ConstantDescriptor.Null -> "null"
            is ConstantDescriptor.Bool -> "$value"
            is ConstantDescriptor.Byte -> "(byte) $value"
            is ConstantDescriptor.Char -> when (value) {
                in 'a'..'z' -> "'$value'"
                in 'A'..'Z' -> "'$value'"
                else -> "(char) ${value.code}"
            }
            is ConstantDescriptor.Short -> "(short) $value"
            is ConstantDescriptor.Int -> "$value"
            is ConstantDescriptor.Long -> "${value}L"
            is ConstantDescriptor.Float -> when {
                value.isNaN() -> "Float.NaN".also {
                    builder.import("java.lang.Float")
                }
                value.isInfinite() && value < 0.0 -> "Float.NEGATIVE_INFINITY".also {
                    builder.import("java.lang.Float")
                }
                value.isInfinite() -> "Float.POSITIVE_INFINITY".also {
                    builder.import("java.lang.Float")
                }
                else -> "${value}F"
            }
            is ConstantDescriptor.Double -> when {
                value.isNaN() -> "Double.NaN".also {
                    builder.import("java.lang.Double")
                }
                value.isInfinite() && value < 0.0 -> "Double.NEGATIVE_INFINITY".also {
                    builder.import("java.lang.Double")
                }
                value.isInfinite() -> "Double.POSITIVE_INFINITY".also {
                    builder.import("java.lang.Double")
                }
                else -> "$value"
            }
            else -> unreachable { log.error("Unknown constant descriptor $this") }
        }

    private fun printDeclarations(
        descriptor: Descriptor,
        result: MutableList<String>,
        visited: MutableSet<Descriptor> = mutableSetOf(),
        names: MutableMap<Descriptor, String> = mutableMapOf()
    ): Map<Descriptor, String> {
        val name = "${descriptor.term}"
        if (descriptor in visited) return names
        if (name in printedStacks) return names
        visited += descriptor

        val resolveType = when (descriptor.type) {
            is KexPointer -> ctx.types.objectType.asType
            else -> descriptor.type.getKfgType(ctx.types).asType
        }
        val decl = printVarDeclaration(name, resolveType)

        when (descriptor) {
            ConstantDescriptor.Null -> result += "$decl = null".also { names[descriptor] = name }
            is ConstantDescriptor.Bool -> result += "$decl = ${descriptor.asConstant}".also { names[descriptor] = name }
            is ConstantDescriptor.Byte -> result += "$decl = ${descriptor.asConstant}".also { names[descriptor] = name }
            is ConstantDescriptor.Char -> result += "$decl = ${descriptor.asConstant}".also { names[descriptor] = name }
            is ConstantDescriptor.Double -> result += "$decl = ${descriptor.asConstant}".also { names[descriptor] = name }
            is ConstantDescriptor.Float -> result += "$decl = ${descriptor.asConstant}".also { names[descriptor] = name }
            is ConstantDescriptor.Int -> result += "$decl = ${descriptor.asConstant}".also { names[descriptor] = name }
            is ConstantDescriptor.Long -> result += "$decl = ${descriptor.asConstant}".also { names[descriptor] = name }
            is ConstantDescriptor.Short -> result += "$decl = ${descriptor.asConstant}".also { names[descriptor] = name }
            is ArrayDescriptor -> {
                val elementType = (descriptor.type as KexArray).element
                result += "$decl = ${
                    elementType.primitiveName?.let {
                        "${newPrimitiveArrayMap[it]!!.name}(${descriptor.length})"
                    } ?: "${newArray.name}(\"${(elementType.getKfgType(ctx.types) as ClassType).klass.canonicalDesc}\", ${descriptor.length})"
                }".also { names[descriptor] = name }
                for ((_, element) in descriptor.elements) {
                    printDeclarations(element, result, visited, names)
                }
            }
            is ClassDescriptor -> {
                val klass = (descriptor.type.getKfgType(ctx.types) as ClassType).klass
                val klassVarName = "klassInstance${klassCounter++}"
                result += "Class<?> $klassVarName = Class.forName(\"${klass.canonicalDesc}\")".also { names[descriptor] = klassVarName }
                for ((_, element) in descriptor.fields) {
                    printDeclarations(element, result, visited, names)
                }
            }
            is ObjectDescriptor -> {
                val klass = (descriptor.type.getKfgType(ctx.types) as ClassType).klass
                when {
                    klass.isEnum -> result += "$decl = ${klass.javaString}.${getEnumName(descriptor)}"
                    else -> {
                        result += "$decl = ${newInstance.name}(\"${klass.canonicalDesc}\")"
                        for ((_, element) in descriptor.fields) {
                            printDeclarations(element, result, visited, names)
                        }
                    }
                }.also { names[descriptor] = name }
            }
        }
        return names
    }

    private fun printInsides(descriptor: Descriptor, result: MutableList<String>, names: Map<Descriptor, String>): String = with(current) {
        val name = names[descriptor] ?: "${descriptor.term}"
        if (name in printedStacks) return@with name
        printedStacks += name

        when (descriptor) {
            is ArrayDescriptor -> {
                for ((index, element) in descriptor.elements) {
                    val elementName = printInsides(element, result, names)
                    val setElementMethod =
                        element.type.primitiveName?.let { setPrimitiveElementMap[it]!! } ?: setElement
                    result += "${setElementMethod.name}($name, $index, $elementName)"
                }
            }
            is ClassDescriptor -> {
                for ((field, element) in descriptor.fields) {
                    val fieldName = printInsides(element, result, names)
                    val setFieldMethod = field.second.primitiveName?.let { setPrimitiveFieldMap[it]!! } ?: setField
                    result += "${setFieldMethod.name}(null, $name, \"${field.first}\", $fieldName)"
                }
            }
            is ObjectDescriptor -> {
                val klass = (descriptor.type.getKfgType(ctx.types) as ClassType).klass
                when {
                    klass.isEnum -> {}
                    else -> {
                        for ((field, element) in descriptor.fields) {
                            val fieldName = printInsides(element, result, names)
                            val setFieldMethod =
                                field.second.primitiveName?.let { setPrimitiveFieldMap[it]!! } ?: setField
                            result += "${setFieldMethod.name}($name, ${name}.getClass(), \"${field.first}\", $fieldName)"
                        }
                    }
                }
            }
            else -> {}
        }
        return name
    }

    private fun getEnumName(descriptor: ObjectDescriptor): String {
        val klass = (descriptor.type.getKfgType(ctx.types) as ClassType).klass

        val nameDescriptor = descriptor["name", KexString()] as? ObjectDescriptor
        return nameDescriptor?.let { obj ->
            val valueDescriptor = obj["value", KexChar().asArray()] as? ArrayDescriptor
            valueDescriptor?.let { array ->
                (0 until array.length).map {
                    (array.elements.getOrDefault(it, descriptor { const(' ') }) as ConstantDescriptor.Char).value
                }.joinToString("")
            }
        } ?: klass.fields.filter { it.isFinal && it.isStatic && it.type == klass.type }.randomOrNull()?.name
        ?: "NOT_FOUND"
    }
}
