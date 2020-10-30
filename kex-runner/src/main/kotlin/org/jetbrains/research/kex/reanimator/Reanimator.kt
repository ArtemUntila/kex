package org.jetbrains.research.kex.reanimator

import com.abdullin.kthelper.`try`
import com.abdullin.kthelper.logging.log
import com.abdullin.kthelper.time.timed
import com.abdullin.kthelper.tryOrNull
import org.jetbrains.research.kex.ExecutionContext
import org.jetbrains.research.kex.asm.state.PredicateStateAnalysis
import org.jetbrains.research.kex.random.GenerationException
import org.jetbrains.research.kex.reanimator.callstack.*
import org.jetbrains.research.kex.reanimator.codegen.javagen.CallStack2JavaPrinter
import org.jetbrains.research.kex.reanimator.codegen.kotlingen.CallStack2KotlinPrinter
import org.jetbrains.research.kex.reanimator.descriptor.Descriptor
import org.jetbrains.research.kex.reanimator.descriptor.DescriptorStatistics
import org.jetbrains.research.kex.smt.SMTModel
import org.jetbrains.research.kex.state.PredicateState
import org.jetbrains.research.kex.state.transformer.generateFinalDescriptors
import org.jetbrains.research.kex.state.transformer.generateInputByModel
import org.jetbrains.research.kfg.ClassManager
import org.jetbrains.research.kfg.ir.Class
import org.jetbrains.research.kfg.ir.Method

class NoConcreteInstanceException(val klass: Class) : Exception()

class Reanimator(val ctx: ExecutionContext, val psa: PredicateStateAnalysis) {
    val cm: ClassManager get() = ctx.cm
    private val csGenerator = CallStackGenerator(ctx, psa)
    private val csExecutor = CallStackExecutor(ctx)

    private fun printTest(method: Method, callStacks: Parameters<CallStack>) {
        val stack = when {
            method.isStatic -> StaticMethodCall(method, callStacks.arguments).wrap("static")
            method.isConstructor -> callStacks.instance!!
            else -> {
                val instance = callStacks.instance!!.clone()
                instance.stack += MethodCall(method, callStacks.arguments)
                instance
            }
        }
        val kotlinCS = tryOrNull { CallStack2KotlinPrinter(ctx).print(stack) } ?: ""
        log.debug("Kotlin call stacks:\n$kotlinCS")
        val javaCS = tryOrNull { CallStack2JavaPrinter(ctx).print(stack) } ?: ""
        log.debug("Java call stacks:\n$javaCS")
    }

    fun generateAPI(method: Method, state: PredicateState, model: SMTModel) = try {
        val descriptors = generateFinalDescriptors(method, ctx, model, state).concreteParameters(cm)
        log.debug("Generated descriptors:\n$descriptors")
        val callStacks = descriptors.callStacks
        printTest(method, callStacks)
        log.debug("Generated call stacks:\n$callStacks")
        val (instance, arguments, _) = callStacks.executed
        instance to arguments.toTypedArray()
    } catch (e: GenerationException) {
        throw e
    } catch (e: Exception) {
        throw GenerationException(e)
    } catch (e: Error) {
        throw GenerationException(e)
    }

    fun generateFromModel(method: Method, state: PredicateState, model: SMTModel) = try {
        generateInputByModel(ctx, method, state, model)
    } catch (e: GenerationException) {
        throw e
    } catch (e: Exception) {
        throw GenerationException(e)
    }

    private val Descriptor.callStack: CallStack
        get() = `try` {
            lateinit var cs: CallStack
            val time = timed { cs = csGenerator.generateDescriptor(this) }
            DescriptorStatistics.addDescriptor(this, cs, time)
            cs
        }.getOrThrow {
            DescriptorStatistics.addFailure(this)
        }

    private val Parameters<Descriptor>.callStacks: Parameters<CallStack>
        get() {
            val thisCallStack = instance?.callStack
            val argCallStacks = arguments.map { it.callStack }
            val staticFields = staticFields.mapValues { it.value.callStack }
            return Parameters(thisCallStack, argCallStacks, staticFields)
        }

    private val Parameters<CallStack>.executed: Parameters<Any?>
        get() {
            val instance = instance?.let { csExecutor.execute(it) }
            val args = arguments.map { csExecutor.execute(it) }
            val statics = staticFields.mapValues { csExecutor.execute(it.value) }
            return Parameters(instance, args, statics)
        }
}