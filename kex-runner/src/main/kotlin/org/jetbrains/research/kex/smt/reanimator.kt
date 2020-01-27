package org.jetbrains.research.kex.smt

import org.jetbrains.research.kex.ExecutionContext
import org.jetbrains.research.kex.generator.ArrayDescriptor
import org.jetbrains.research.kex.generator.Descriptor
import org.jetbrains.research.kex.generator.ObjectDescriptor
import org.jetbrains.research.kex.generator.descriptor
import org.jetbrains.research.kex.ktype.*
import org.jetbrains.research.kex.state.term.*
import org.jetbrains.research.kex.state.transformer.memspace
import org.jetbrains.research.kex.util.*
import org.jetbrains.research.kfg.ir.Method
import java.lang.reflect.*
import java.lang.reflect.Array
import kotlin.math.ceil

private val Term.isPointer get() = this.type is KexPointer
private val Term.isPrimary get() = !this.isPointer

private var Field.isFinal: Boolean
    get() = (this.modifiers and Modifier.FINAL) == Modifier.FINAL
    set(value) {
        if (value == this.isFinal) return
        val modifiersField = this.javaClass.getDeclaredField("modifiers")
        modifiersField.isAccessible = true
        modifiersField.setInt(this, this.modifiers and if (value) Modifier.FINAL else Modifier.FINAL.inv())
    }

data class ReanimatedModel(val method: Method, val instance: Any?, val arguments: List<Any?>)

interface Reanimator<T> {
    val method: Method
    val model: SMTModel
    val context: ExecutionContext

    val loader: ClassLoader get() = context.loader

    val memoryMappings: MutableMap<Int, MutableMap<Int, T>>

    fun memory(memspace: Int, address: Int) =
            memoryMappings.getOrPut(memspace, ::hashMapOf)[address]

    fun memory(memspace: Int, address: Int, getter: () -> T) =
            memoryMappings.getOrPut(memspace, ::hashMapOf).getOrPut(address, getter)

    fun memory(memspace: Int, address: Int, value: T) =
            memoryMappings.getOrPut(memspace, ::hashMapOf).getOrPut(address) { value }

    fun reanimate(term: Term,
                  jType: Type = loader.loadClass(term.type.getKfgType(method.cm.type)),
                  value: Term? = model.assignments[term]): T =
            reanimateNullable(term, jType, value) ?: unreachable { log.error("Unable to reanimate non-nullable value") }

    fun reanimateNullable(term: Term,
                          jType: Type = loader.loadClass(term.type.getKfgType(method.cm.type)),
                          value: Term? = model.assignments[term]): T
}

class ObjectReanimator(override val method: Method,
                       override val model: SMTModel,
                       override val context: ExecutionContext) : Reanimator<Any?> {
    private val randomizer get() = context.random

    override val memoryMappings = hashMapOf<Int, MutableMap<Int, Any?>>()

    override fun reanimateNullable(term: Term, jType: Type, value: Term?): Any? = when {
        term.isPrimary -> reanimatePrimary(term.type, jType, value)
        else -> reanimatePointer(term, jType, value)
    }

    private fun reanimatePrimary(type: KexType, jType: Type, value: Term?): Any? {
        if (value == null) return randomizer.next(jType)
        return when (type) {
            is KexBool -> (value as ConstBoolTerm).value
            is KexByte -> (value as ConstByteTerm).value
            is KexChar -> (value as ConstIntTerm).value.toChar()
            is KexShort -> (value as ConstShortTerm).value
            is KexInt -> (value as ConstIntTerm).value
            is KexLong -> (value as ConstLongTerm).value
            is KexFloat -> (value as ConstFloatTerm).value
            is KexDouble -> (value as ConstDoubleTerm).value
            else -> unreachable { log.error("Trying to recover non-primary term as primary value: $value with type $type") }
        }
    }

    private fun reanimatePointer(term: Term, jType: Type, addr: Term?): Any? = when (term.type) {
        is KexClass -> reanimateClass(term, jType, addr)
        is KexArray -> reanimateArray(term, jType, addr)
        is KexReference -> reanimateReference(term, jType, addr)
        else -> unreachable { log.error("Trying to recover non-pointer term $term with type ${term.type} as pointer value") }
    }

    private fun reanimateClass(term: Term, jType: Type, addr: Term?): Any? {
        val type = term.type as KexClass
        val address = (addr as? ConstIntTerm)?.value ?: return null
        if (address == 0) return null

        return memory(type.memspace, address) { randomizer.nextOrNull(jType) }
    }

    private fun reanimateArray(term: Term, jType: Type, addr: Term?): Any? {
        val arrayType = term.type as KexArray
        val address = (addr as? ConstIntTerm)?.value ?: return null
        if (address == 0) return null

        val memspace = arrayType.memspace
        val instance = newArrayInstance(memspace, arrayType, jType, addr)
        return memory(arrayType.memspace, address, instance)
    }

    private fun reanimateReference(term: Term, jType: Type, addr: Term?): Any? {
        val memspace = term.memspace
        val refValue = model.memories[memspace]?.finalMemory!![addr]
        return when (term) {
            is ArrayIndexTerm -> {
                val arrayRef = term.arrayRef
                val elementType = (arrayRef.type as KexArray).element

                val arrayAddr = (model.assignments[arrayRef] as ConstIntTerm).value
                val array = memory(arrayRef.memspace, arrayAddr) ?: return null

                val reanimatedValue = reanimateReferenceValue(term, jType, refValue)
                val address = (addr as? ConstIntTerm)?.value
                        ?: unreachable { log.error("Non-int address of array index") }
                val realIndex = (address - arrayAddr) / elementType.bitsize
                Array.set(array, realIndex, reanimatedValue)
                array
            }
            is FieldTerm -> {
                val (instance, klass) = when {
                    term.isStatic -> {
                        val classRef = (term.owner as ConstClassTerm)
                        val `class` = tryOrNull { loader.loadClass(classRef.`class`.canonicalDesc) } ?: return null
                        if (`class`.isSynthetic) return null
                        null to `class`
                    }
                    else -> {
                        val objectRef = term.owner
                        val objectAddr = (model.assignments[objectRef] as ConstIntTerm).value
                        val type = objectRef.type as KexClass

                        val kfgClass = method.cm.getByName(type.`class`)
                        val `class` = tryOrNull { loader.loadClass(kfgClass.canonicalDesc) } ?: return null
                        val instance = memory(objectRef.memspace, objectAddr) ?: return null
                        instance to `class`
                    }
                }
                val fieldAddress = model.assignments[term]
                val fieldValue = model.memories.getValue(memspace).finalMemory[fieldAddress]

                val fieldReflect = klass.getActualField((term.fieldName as ConstStringTerm).value)
                if (!klass.isAssignableFrom(instance?.javaClass ?: Any::class.java)) {
                    log.warn("Could not generate an instance of $klass, so skipping filed initialization")
                    return instance
                }
                val reanimatedValue = reanimateReferenceValue(term, fieldReflect.genericType, fieldValue)
                fieldReflect.isAccessible = true
                fieldReflect.isFinal = false
                if (fieldReflect.isEnumConstant || fieldReflect.isSynthetic) return instance
                if (fieldReflect.type.isPrimitive) {
                    val definedValue = reanimatedValue
                            ?: reanimatePrimary((term.type as KexReference).reference, fieldReflect.type, null)!!
                    when (definedValue.javaClass) {
                        Boolean::class.javaObjectType -> fieldReflect.setBoolean(instance, definedValue as Boolean)
                        Byte::class.javaObjectType -> fieldReflect.setByte(instance, definedValue as Byte)
                        Char::class.javaObjectType -> fieldReflect.setChar(instance, definedValue as Char)
                        Short::class.javaObjectType -> fieldReflect.setShort(instance, definedValue as Short)
                        Int::class.javaObjectType -> fieldReflect.setInt(instance, definedValue as Int)
                        Long::class.javaObjectType -> fieldReflect.setLong(instance, definedValue as Long)
                        Float::class.javaObjectType -> fieldReflect.setFloat(instance, definedValue as Float)
                        Double::class.javaObjectType -> fieldReflect.setDouble(instance, definedValue as Double)
                        else -> unreachable { log.error("Trying to get primitive type of non-primitive object $this") }
                    }
                } else {
                    fieldReflect.set(instance, reanimatedValue)
                }
                instance
            }
            else -> unreachable { log.error("Unknown reference term: $term with address $addr") }
        }
    }

    private fun reanimateReferenceValue(term: Term, jType: Type, value: Term?): Any? {
        val referencedType = (term.type as KexReference).reference
        if (value == null) return null
        val intVal = (value as ConstIntTerm).value

        return when (referencedType) {
            is KexPointer -> reanimateReferencePointer(term, jType, value)
            is KexBool -> intVal.toBoolean()
            is KexByte -> intVal.toByte()
            is KexChar -> intVal.toChar()
            is KexShort -> intVal.toShort()
            is KexInt -> intVal
            is KexLong -> intVal.toLong()
            is KexFloat -> intVal.toFloat()
            is KexDouble -> intVal.toDouble()
            else -> unreachable { log.error("Can't recover type $referencedType from memory value $value") }
        }
    }

    private fun reanimateReferencePointer(term: Term, jType: Type, addr: Term?): Any? {
        val referencedType = (term.type as KexReference).reference
        val address = (addr as? ConstIntTerm)?.value ?: return null
        if (address == 0) return null
        return when (referencedType) {
            is KexClass -> memory(term.memspace, address) { randomizer.nextOrNull(jType) }
            is KexArray -> {
                val memspace = term.memspace//referencedType.memspace
                val instance = newArrayInstance(memspace, referencedType, jType, addr)
                memory(memspace, address, instance)
            }
            else -> unreachable { log.error("Trying to recover reference pointer that is not pointer") }
        }
    }

    private fun newArrayInstance(memspace: Int, arrayType: KexArray, jType: Type, addr: Term?): Any? {
        val bounds = model.bounds[memspace] ?: return null
        val bound = (bounds.finalMemory[addr] as? ConstIntTerm)?.value ?: return null

        val elementSize = arrayType.element.bitsize
        // todo: this is needed because Boolector does not always align bounds by byte size
        val elements = ceil(bound.toDouble() / elementSize).toInt()

        val elementType = when (jType) {
            is Class<*> -> jType.componentType
            is GenericArrayType -> randomizer.nextOrNull(jType.genericComponentType)?.javaClass
            else -> unreachable { log.error("Unknown jType in array recovery: $jType") }
        }
        log.debug("Creating array of type $elementType with size $elements")
        return Array.newInstance(elementType, elements)
    }
}

class DescriptorReanimator(override val method: Method,
                           override val model: SMTModel,
                           override val context: ExecutionContext) : Reanimator<Descriptor> {
    private val types get() = context.types
    override val memoryMappings = hashMapOf<Int, MutableMap<Int, Descriptor>>()

    override fun reanimate(term: Term, @Suppress("UNUSED_PARAMETER") jType: Type, value: Term?): Descriptor = when {
        term.isPrimary -> reanimatePrimary(term, value)
        else -> reanimatePointer(term, value, false)
    }

    override fun reanimateNullable(term: Term, @Suppress("UNUSED_PARAMETER") jType: Type, value: Term?): Descriptor = when {
        term.isPrimary -> reanimatePrimary(term, value)
        else -> reanimatePointer(term, value, true)
    }

    private fun reanimatePrimary(term: Term, value: Term?) = descriptor(context) {
        if (value == null) default(term.type, term.name, false)
        else when (term.type) {
            is KexBool -> const((value as ConstBoolTerm).value)
            is KexByte -> const((value as ConstByteTerm).value)
            is KexChar -> const((value as ConstIntTerm).value)
            is KexShort -> const((value as ConstShortTerm).value)
            is KexInt -> const((value as ConstIntTerm).value)
            is KexLong -> const((value as ConstLongTerm).value)
            is KexFloat -> const((value as ConstFloatTerm).value)
            is KexDouble -> const((value as ConstDoubleTerm).value)
            else -> unreachable { log.error("Trying to recover non-primary term as primary value: $value with type ${term.type}") }
        }
    }

    private fun reanimatePointer(term: Term, addr: Term?, nullable: Boolean) = when (term.type) {
        is KexClass -> reanimateClass(term, addr, nullable)
        is KexArray -> reanimateArray(term, addr, nullable)
        is KexReference -> reanimateReference(term, addr, nullable)
        else -> unreachable { log.error("Trying to recover non-pointer term $term with type ${term.type} as pointer value") }
    }

    private fun reanimateClass(term: Term, addr: Term?, nullable: Boolean) = descriptor(context) {
        val type = term.type as KexClass

        when (val address = (addr as? ConstIntTerm)?.value) {
            null, 0 -> default(term.type, term.name, nullable)
            else -> memory(type.memspace, address) { `object`(term.name, type.kfgClass(types)) }
        }
    }

    private fun reanimateArray(term: Term, addr: Term?, nullable: Boolean) = descriptor(context) {
        val arrayType = term.type as KexArray

        when (val address = (addr as? ConstIntTerm)?.value) {
            null, 0 -> default(term.type, term.name, nullable)
            else -> memory(arrayType.memspace, address) {
                newArrayInstance(term.name, arrayType.memspace, arrayType, addr, nullable)
            }
        }
    }

    private fun reanimateReference(term: Term, addr: Term?, nullable: Boolean) = descriptor(context) {
        val memspace = term.memspace
        val refValue = model.memories[memspace]?.finalMemory!![addr]
        when (term) {
            is ArrayIndexTerm -> {
                val arrayRef = term.arrayRef
                val elementType = (arrayRef.type as KexArray).element

                val arrayAddr = (model.assignments[arrayRef] as ConstIntTerm).value
                val array = memory(arrayRef.memspace, arrayAddr) as? ArrayDescriptor
                        ?: return@descriptor default(term.type, term.name, nullable)

                val reanimatedValue = reanimateReferenceValue(term, refValue, nullable)
                val address = (addr as? ConstIntTerm)?.value
                        ?: unreachable { log.error("Non-int address of array index") }
                val realIndex = (address - arrayAddr) / elementType.bitsize
                array[realIndex] = reanimatedValue
                array
            }
            is FieldTerm -> {
                val fieldName = (term.fieldName as ConstStringTerm).value
                val (instance, klass, field) = when {
                    term.isStatic -> {
                        val classRef = (term.owner as ConstClassTerm)
                        val `class` = tryOrNull { loader.loadClass(classRef.`class`.canonicalDesc) }
                                ?: return@descriptor default(term.type, term.name, nullable)
                        if (`class`.isSynthetic) return@descriptor default(term.type, term.name, nullable)

                        Triple(`null`, `class`, classRef.`class`.getField(fieldName, term.type.getKfgType(types)))
                    }
                    else -> {
                        val objectRef = term.owner
                        val objectAddr = (model.assignments[objectRef] as ConstIntTerm).value
                        val type = objectRef.type as KexClass

                        val kfgClass = method.cm.getByName(type.`class`)
                        val `class` = tryOrNull { loader.loadClass(kfgClass.canonicalDesc) }
                                ?: return@descriptor default(term.type, term.name, nullable)

                        val instance = memory(objectRef.memspace, objectAddr)
                                ?: return@descriptor default(term.type, term.name, nullable)

                        Triple(instance, `class`, kfgClass.getField(fieldName, term.type.getKfgType(types)))
                    }
                }
                val fieldAddress = model.assignments[term]
                val fieldValue = model.memories.getValue(memspace).finalMemory[fieldAddress]

                val fieldReflect = klass.getActualField((term.fieldName as ConstStringTerm).value)
                val reanimatedValue = reanimateReferenceValue(term, fieldValue, nullable)
                if (fieldReflect.isEnumConstant || fieldReflect.isSynthetic)
                    return@descriptor default(term.type, term.name, nullable)

                if (instance is ObjectDescriptor) {
                    instance[fieldReflect.name] = field(field.name, field.type, field.`class`, reanimatedValue, instance)
                }

                instance
            }
            else -> unreachable { log.error("Unknown reference term: $term with address $addr") }
        }
    }

    private fun reanimateReferenceValue(term: Term, value: Term?, nullable: Boolean) = descriptor(context) {
        val referencedType = (term.type as KexReference).reference
        if (value == null) return@descriptor default(term.type, term.name, nullable)

        when (value) {
            is ConstDoubleTerm -> const(value.value)
            is ConstFloatTerm -> const(value.value)
            else -> {
                val intVal = (value as ConstIntTerm).value
                when (referencedType) {
                    is KexPointer -> reanimateReferencePointer(term, value, nullable)
                    is KexBool -> const(intVal.toBoolean())
                    is KexLong -> const(intVal.toLong())
                    is KexFloat -> const(intVal.toFloat())
                    is KexDouble -> const(intVal.toDouble())
                    else -> const(intVal)
                }
            }
        }
    }

    private fun reanimateReferencePointer(term: Term, addr: Term?, nullable: Boolean) = descriptor(context) {
        val referencedType = (term.type as KexReference).reference
        val address = (addr as? ConstIntTerm)?.value ?: return@descriptor default(term.type, term.name, nullable)
        if (address == 0) return@descriptor default(term.type, term.name, nullable)
        when (referencedType) {
            is KexClass -> memory(term.memspace, address) { `object`(term.name, referencedType.kfgClass(types)) }
            is KexArray -> memory(term.memspace, address) {
                newArrayInstance(term.name, term.memspace, referencedType, addr, nullable)
            }
            else -> unreachable { log.error("Trying to recover reference pointer that is not pointer") }
        }
    }

    private fun newArrayInstance(name: String, memspace: Int, arrayType: KexArray, addr: Term?, nullable: Boolean) = descriptor(context) {
        val bounds = model.bounds[memspace] ?: return@descriptor default(arrayType, name, nullable)
        val bound = (bounds.finalMemory[addr] as? ConstIntTerm)?.value
                ?: return@descriptor default(arrayType, name, nullable)

        val elementSize = arrayType.element.bitsize
        val elements = bound / elementSize

        log.debug("Creating array of type $arrayType with size $elements")
        array(name, elements, arrayType.getKfgType(types))
    }
}