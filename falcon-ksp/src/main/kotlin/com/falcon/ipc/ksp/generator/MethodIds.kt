package com.falcon.ipc.ksp.generator

import com.falcon.ipc.annotations.MethodId
import com.google.devtools.ksp.symbol.KSFunctionDeclaration

object MethodIds {
    private const val IPC_REPLY = "com.falcon.ipc.service.IpcReply"

    fun of(m: KSFunctionDeclaration): Int {
        val paramQNs = m.parameters
            .map { it.type.resolve().declaration.qualifiedName?.asString() ?: "?" }
            .filter { it != IPC_REPLY }
        return MethodId.signatureHash(m.simpleName.asString(), paramQNs)
    }
}
