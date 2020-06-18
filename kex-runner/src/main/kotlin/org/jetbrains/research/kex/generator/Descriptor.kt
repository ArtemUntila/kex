package org.jetbrains.research.kex.generator

import com.abdullin.kthelper.assert.unreachable
import com.abdullin.kthelper.logging.log
import org.jetbrains.research.kex.ExecutionContext
import org.jetbrains.research.kex.ktype.*
import org.jetbrains.research.kex.state.PredicateState
import org.jetbrains.research.kex.state.StateBuilder
import org.jetbrains.research.kex.state.emptyState
import org.jetbrains.research.kex.state.predicate.axiom
import org.jetbrains.research.kex.state.predicate.require
import org.jetbrains.research.kex.state.term.Term
import org.jetbrains.research.kex.state.term.term
import org.jetbrains.research.kfg.type.ArrayType
import org.jetbrains.research.kfg.ir.Class as KfgClass
import org.jetbrains.research.kfg.type.Type as KfgType

sealed class Descriptor {
    abstract val term: Term
    abstract val type: KexType

    abstract val hasState: Boolean

    abstract val query: PredicateState
}

sealed class ConstantDescriptor : Descriptor() {
    override val query
        get() = unreachable<PredicateState> {
            log.error("Can't transform constant descriptor $this to initializer state")
        }

    override val hasState: Boolean
        get() = false

    object Null : ConstantDescriptor() {
        override val type = KexNull()
        override val term = term { const(null) }
        override fun toString() = "null"
    }

    data class Bool(val value: Boolean) : ConstantDescriptor() {
        override val type = KexBool()
        override val term get() = term { const(value) }
    }

    data class Int(val value: kotlin.Int) : ConstantDescriptor() {
        override val type = KexInt()
        override val term get() = term { const(value) }
    }

    data class Long(val value: kotlin.Long) : ConstantDescriptor() {
        override val type = KexLong()
        override val term get() = term { const(value) }
    }

    data class Float(val value: kotlin.Float) : ConstantDescriptor() {
        override val type = KexFloat()
        override val term get() = term { const(value) }
    }

    data class Double(val value: kotlin.Double) : ConstantDescriptor() {
        override val type = KexDouble()
        override val term get() = term { const(value) }
    }

    data class Class(val value: KfgClass) : ConstantDescriptor() {
        override val type = KexClass(value.fullname)
        override val term get() = term { `class`(value) }
    }
}

data class FieldDescriptor(
        val name: String,
        val kfgType: KfgType,
        val klass: KfgClass,
        val owner: Descriptor,
        val value: Descriptor
) : Descriptor() {
    override val type = kfgType.kexType
    override val term = term { owner.term.field(type, name) }

    override val hasState: Boolean
        get() = true

    override val query: PredicateState
        get() {
            val builder = StateBuilder()
            if (value.hasState) {
                builder += value.query
            }
            builder += require { term.load() equality value.term }
            return builder.apply()
        }

    override fun toString() = "${klass.fullname}.$name = $value"
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FieldDescriptor

        if (name != other.name) return false
        if (type != other.type) return false
        if (klass != other.klass) return false
        if (value != other.value) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + klass.hashCode()
        result = 31 * result + value.hashCode()
        return result
    }
}

data class ObjectDescriptor(
        val klass: KfgClass,
        private val fieldsInner: MutableMap<String, FieldDescriptor> = mutableMapOf()
) : Descriptor() {
    override val term = term { generate(klass.kexType) }
    override val type = KexClass(klass.fullname)
    val name = term.name
    val fields get() = fieldsInner.toMap()

    override val hasState: Boolean
        get() = true

    operator fun set(field: String, value: FieldDescriptor) {
        fieldsInner[field] = value
    }

    operator fun get(field: String) = fieldsInner[field]

    override val query: PredicateState
        get() {
            val builder = StateBuilder()
            builder += axiom { term inequality null }
            fields.values.forEach {
                builder += it.query
            }
            return builder.apply()
        }

    override fun toString(): String = buildString {
        append("$klass {")
        if (fieldsInner.isNotEmpty()) {
            append("\n  ")
            appendln(fieldsInner.values.joinToString("\n").replace("\n", "\n  "))
        }
        appendln("}")
    }

    fun merge(other: ObjectDescriptor): ObjectDescriptor =
            ObjectDescriptor(klass, (other.fields + this.fields).toMutableMap())
}

data class ArrayDescriptor(
        val length: Int,
        val kfgType: KfgType,
        private val elementsInner: MutableMap<Int, Descriptor> = mutableMapOf()
) : Descriptor() {
    val elementType = (kfgType as ArrayType).component
    override val type = KexArray(elementType.kexType)
    override val term = term { generate(type) }
    val name = term.name
    val elements get() = elementsInner.toMap()

    override val hasState: Boolean
        get() = true

    operator fun set(index: Int, value: Descriptor) {
        elementsInner[index] = value
    }

    override val query: PredicateState
        get() {
            val builder = StateBuilder()
            builder += axiom { term inequality null }
            elements.forEach { (index, element) ->
                if (element.hasState) {
                    builder += element.query
                }
                builder += require { term[index].load() equality element.term }
            }
            return builder.apply()
        }

    override fun toString(): String = buildString {
        append("$type {")
        if (elementsInner.isNotEmpty()) {
            append("\n  ")
            appendln(elementsInner.toList().joinToString("\n") { "[${it.first}] = ${it.second}" }.replace("\n", "\n  "))
        }
        appendln("}")
    }
}

class DescriptorBuilder(val context: ExecutionContext) {
    val `null` = ConstantDescriptor.Null
    fun const(@Suppress("UNUSED_PARAMETER") nothing: Nothing?) = `null`
    fun const(value: Boolean) = ConstantDescriptor.Bool(value)
    fun const(number: Number) = when (number) {
        is Long -> ConstantDescriptor.Long(number)
        is Float -> ConstantDescriptor.Float(number)
        is Double -> ConstantDescriptor.Double(number)
        else -> ConstantDescriptor.Int(number.toInt())
    }

    fun `object`(type: KfgClass): ObjectDescriptor = ObjectDescriptor(type)
    fun array(length: Int, type: KfgType): ArrayDescriptor = ArrayDescriptor(length, type)

    fun ObjectDescriptor.field(name: String, type: KfgType, klass: KfgClass, value: Descriptor) =
            FieldDescriptor(name, type, klass, this, value)

    fun default(type: KexType, nullable: Boolean): Descriptor = descriptor(context) {
        when (type) {
            is KexBool -> const(false)
            is KexByte -> const(0)
            is KexChar -> const(0)
            is KexShort -> const(0)
            is KexInt -> const(0)
            is KexLong -> const(0L)
            is KexFloat -> const(0.0F)
            is KexDouble -> const(0.0)
            is KexClass -> if (nullable) `null` else `object`(type.kfgClass(context.types))
            is KexArray -> if (nullable) `null` else array(0, type.getKfgType(context.types))
            is KexReference -> default(type.reference, nullable)
            else -> unreachable { log.error("Could not generate default descriptor value for unknown type $type") }
        }
    }

    fun default(type: KexType): Descriptor = descriptor(context) {
        when (type) {
            is KexBool -> const(false)
            is KexByte -> const(0)
            is KexChar -> const(0)
            is KexShort -> const(0)
            is KexInt -> const(0)
            is KexLong -> const(0L)
            is KexFloat -> const(0.0F)
            is KexDouble -> const(0.0)
            is KexClass -> `null`
            is KexArray -> `null`
            is KexReference -> default(type.reference)
            else -> unreachable { log.error("Could not generate default descriptor value for unknown type $type") }
        }
    }
}

fun descriptor(context: ExecutionContext, body: DescriptorBuilder.() -> Descriptor): Descriptor =
        DescriptorBuilder(context).body()

val Descriptor.typeInfo: PredicateState get() = when (this) {
    is ObjectDescriptor -> {
        val descTerm = this.term
        val descType = this.type
        val instanceOfTerm = term { generate(KexBool()) }
        val builder = StateBuilder()
        builder += axiom { instanceOfTerm equality (descTerm `is` descType) }
        builder += axiom { instanceOfTerm equality true }
        for ((_, field) in this.fields) {
            builder += field.typeInfo
        }
        builder.apply()
    }
    is FieldDescriptor -> {
        val descTerm = this.term
        when (val descType = this.value.type) {
            !is KexClass -> emptyState()
            else -> {
                val builder = StateBuilder()
                val instanceOfTerm = term { generate(KexBool()) }
                builder += axiom { instanceOfTerm equality (descTerm `is` descType) }
                builder += axiom { instanceOfTerm equality true }
                builder.apply()
            }
        }
    }
    else -> emptyState()
}