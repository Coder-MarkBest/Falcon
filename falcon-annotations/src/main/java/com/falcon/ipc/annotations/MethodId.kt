package com.falcon.ipc.annotations

/** Stable method identity for IPC dispatch. Shared by the KSP processor and the runtime. */
object MethodId {
    fun signatureHash(name: String, paramTypeQualifiedNames: List<String>): Int {
        val sig = name + "(" + paramTypeQualifiedNames.joinToString(",") + ")"
        var hash = -0x7ee3623b // 0x811c9dc5 FNV offset basis as Int
        for (c in sig) {
            hash = hash xor c.code
            hash *= 0x01000193 // FNV prime
        }
        return hash
    }
}
