package org.jetbrains.research.kex.state.term

import org.jetbrains.research.kfg.type.Type

class FieldLoadTerm(type: Type, val classType: Type, operands: Array<Term>) : Term("", type, operands) {
    val isStatic = operands.size == 1

    fun getObjectRef() = if (isStatic) null else subterms[0]
    fun getFieldName() = if (isStatic) subterms[0] else subterms[1]

    override fun print(): String {
        val sb = StringBuilder()
        if (isStatic) sb.append(classType)
        else sb.append(getObjectRef())
        sb.append(".${getFieldName()}")
        return sb.toString()
    }
}