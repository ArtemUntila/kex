package org.jetbrains.research.kex.asm.state

import org.jetbrains.research.kex.KexTest
import org.jetbrains.research.kex.asm.transform.LoopDeroller
import org.jetbrains.research.kfg.CM
import org.jetbrains.research.kfg.analysis.LoopAnalysis
import org.jetbrains.research.kfg.analysis.LoopSimplifier
import org.jetbrains.research.kfg.ir.Method
import org.jetbrains.research.kfg.ir.value.instruction.UnreachableInst
import org.junit.Assert.assertNotNull
import kotlin.test.Test

class PredicateStateAnalysisTest : KexTest() {

    private fun performPSA(method: Method): PredicateStateAnalysis {
        val la = LoopAnalysis(method)
        la.visit()
        if (la.loops.isNotEmpty()) {
            val simplifier = LoopSimplifier(method)
            simplifier.visit()
            val deroller = LoopDeroller(method)
            deroller.visit()
        }

        val psa = PredicateStateAnalysis(method)
        psa.visit()
        return psa
    }

    @Test
    fun testSimplePSA() {
        for (`class` in CM.getConcreteClasses()) {
            for ((_, method) in `class`.methods) {
                if (method.isAbstract) continue

                val psa = performPSA(method)

                val catchBlocks = method.catchBlocks
                method.filter { it !in catchBlocks }
                        .flatten()
                        .filter { it !is UnreachableInst }
                        .forEach {
                    assertNotNull(psa.getInstructionState(it))
                }
            }
        }
    }
}