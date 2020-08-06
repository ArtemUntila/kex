package org.jetbrains.research.kex.generator

import com.abdullin.kthelper.logging.log
import org.jetbrains.research.kex.generator.descriptor.Descriptor
import org.jetbrains.research.kfg.ir.Class
import org.jetbrains.research.kfg.ir.Field
import org.jetbrains.research.kfg.ir.Method
import org.jetbrains.research.kfg.type.Type

interface ApiCall {
    val parameters: List<CallStack>

    fun wrap(name: String): CallStack = CallStack(name, mutableListOf(this))
    fun print(owner: CallStack, builder: StringBuilder, visited: MutableSet<CallStack>)
}

class CallStack(val name: String, val stack: MutableList<ApiCall>) : Iterable<ApiCall> by stack {
    constructor(name: String) : this(name, mutableListOf())

    fun add(call: ApiCall): CallStack {
        this.stack += call
        return this
    }

    operator fun plusAssign(call: ApiCall) {
        this.stack += call
    }

    operator fun plusAssign(call: CallStack) {
        this.stack += call.stack
    }

    override fun toString() = print()

    fun print(): String {
        val builder = StringBuilder()
        this.print(builder, mutableSetOf())
        return builder.toString()
    }

    fun print(builder: StringBuilder, visited: MutableSet<CallStack>) {
        if (this in visited) return
        visited += this
        for (call in stack) {
            call.print(this, builder, visited)
        }
    }

    fun reversed(): CallStack = CallStack(name, stack.reversed().toMutableList())
    fun reverse(): CallStack {
        this.stack.reverse()
        return this
    }
}

data class PrimaryValue<T>(val value: T) : ApiCall {
    override val parameters: List<CallStack>
        get() = listOf()

    override fun print(owner: CallStack, builder: StringBuilder, visited: MutableSet<CallStack>) {
        if (owner.name != value.toString()) {
            builder.appendln("${owner.name} = $value")
        }
    }
}

data class DefaultConstructorCall(val klass: Class) : ApiCall {
    override val parameters: List<CallStack>
        get() = listOf()

    override fun toString() = "${klass.fullname}()"
    override fun print(owner: CallStack, builder: StringBuilder, visited: MutableSet<CallStack>) {
        builder.appendln("${owner.name} = $this")
    }
}

data class ConstructorCall(val klass: Class, val constructor: Method, val args: List<CallStack>) : ApiCall {
    init {
        assert(constructor.isConstructor) { log.error("Trying to create constructor call for non-constructor method") }
    }

    override val parameters: List<CallStack> get() = args

    override fun toString() = "$constructor(${args.joinToString(", ")})"

    override fun print(owner: CallStack, builder: StringBuilder, visited: MutableSet<CallStack>) {
        for (arg in args) {
            arg.print(builder, visited)
        }
        builder.appendln("${owner.name} = ${constructor.`class`.fullname}(${args.joinToString(", ") { it.name }})")
    }
}

data class ExternalConstructorCall(val constructor: Method, val args: List<CallStack>) : ApiCall {
    init {
        assert(constructor.isStatic) { log.error("External constructor should be a static method") }
    }

    override val parameters: List<CallStack> get() = args

    override fun toString() = "$constructor(${args.joinToString(", ")})"

    override fun print(owner: CallStack, builder: StringBuilder, visited: MutableSet<CallStack>) {
        for (arg in args) {
            arg.print(builder, visited)
        }
        builder.appendln("${owner.name} = ${constructor.`class`.fullname}(${args.joinToString(", ") { it.name }})")
    }
}

data class MethodCall(val method: Method, val args: List<CallStack>) : ApiCall {
    init {
        assert(!method.isConstructor) { log.error("Trying to create method call for constructor method") }
    }

    override val parameters: List<CallStack> get() = args

    override fun toString() = "$method(${args.joinToString(", ")})"

    override fun print(owner: CallStack, builder: StringBuilder, visited: MutableSet<CallStack>) {
        for (arg in args) {
            arg.print(builder, visited)
        }
        builder.appendln("${owner.name}.${method.name}(${args.joinToString(", ") { it.name }})")
    }
}

data class UnknownCall(val type: Type, val target: Descriptor) : ApiCall {
    override val parameters: List<CallStack> get() = listOf()

    override fun toString() = "Unknown($target)"

    override fun print(owner: CallStack, builder: StringBuilder, visited: MutableSet<CallStack>) {
        builder.appendln("${owner.name} = $this")
    }
}

data class StaticFieldSetter(val klass: Class, val field: Field, val value: CallStack) : ApiCall {
    override val parameters: List<CallStack> get() = listOf(value)

    override fun toString() = "${klass.fullname}.${field.name} = ${value.name}"

    override fun print(owner: CallStack, builder: StringBuilder, visited: MutableSet<CallStack>) {
        value.print(builder, visited)
        builder.appendln(toString())
    }
}

data class FieldSetter(val field: Field, val value: CallStack) : ApiCall {
    override val parameters: List<CallStack> get() = listOf(value)

    override fun print(owner: CallStack, builder: StringBuilder, visited: MutableSet<CallStack>) {
        value.print(builder, visited)
        builder.appendln("${owner.name}.${field.name} = ${value.name}")
    }
}

data class NewArray(val klass: Type, val length: CallStack) : ApiCall {
    override val parameters: List<CallStack> get() = listOf(length)

    override fun toString() = "new $klass[$length]"
    override fun print(owner: CallStack, builder: StringBuilder, visited: MutableSet<CallStack>) {
        length.print(builder, visited)
        builder.appendln("${owner.name} = $this")
    }
}

data class ArrayWrite(val index: CallStack, val value: CallStack) : ApiCall {
    override val parameters: List<CallStack> get() = listOf(value)

    override fun toString() = "array[$index] = $value"

    override fun print(owner: CallStack, builder: StringBuilder, visited: MutableSet<CallStack>) {
        index.print(builder, visited)
        value.print(builder, visited)
        builder.appendln("${owner.name}[$index] = $value")
    }
}