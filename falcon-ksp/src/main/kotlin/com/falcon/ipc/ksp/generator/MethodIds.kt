package com.falcon.ipc.ksp.generator

import com.falcon.ipc.annotations.MethodId
import com.google.devtools.ksp.symbol.KSFunctionDeclaration

object MethodIds {
    fun of(m: KSFunctionDeclaration): Int {
        val paramQNs = m.parameters.map { it.type.resolve().declaration.qualifiedName?.asString() ?: "?" }
        return MethodId.signatureHash(m.simpleName.asString(), paramQNs)
    }
}
