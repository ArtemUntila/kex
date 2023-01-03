package org.vorpal.research.kex.symbolic

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import org.vorpal.research.kex.ExecutionContext
import org.vorpal.research.kex.KexRunnerTest
import org.vorpal.research.kex.asm.analysis.symbolic.SymbolicTraverser
import org.vorpal.research.kex.asm.manager.ClassInstantiationDetector
import org.vorpal.research.kex.asm.transform.SymbolicTraceInstrumenter
import org.vorpal.research.kex.jacoco.CoverageReporter
import org.vorpal.research.kex.launcher.ClassLevel
import org.vorpal.research.kfg.ClassManager
import org.vorpal.research.kfg.Package
import org.vorpal.research.kfg.ir.Class
import org.vorpal.research.kfg.visitor.MethodVisitor
import org.vorpal.research.kfg.visitor.executePipeline
import org.vorpal.research.kthelper.logging.log
import kotlin.test.assertEquals

@ExperimentalSerializationApi
@InternalSerializationApi
@DelicateCoroutinesApi
abstract class SymbolicTest : KexRunnerTest() {

    override fun createTraceCollector(context: ExecutionContext) = object : MethodVisitor {
        override val cm: ClassManager
            get() = context.cm

        override fun cleanup() {}
    }

    fun assertCoverage(klass: Class, expectedCoverage: Double = 1.0, eps: Double = 0.0) {
        executePipeline(analysisContext.cm, Package.defaultPackage) {
            +ClassInstantiationDetector(analysisContext)
        }

        for (method in klass.allMethods.sortedBy { method -> method.prototype }) {
            SymbolicTraverser.run(analysisContext, setOf(method))
        }

        val coverage = CoverageReporter(listOf(jar)).execute(klass.cm, ClassLevel(klass))
        log.debug(coverage.print(true))
        assertEquals(expectedCoverage, coverage.instructionCoverage.ratio, eps)
    }
}