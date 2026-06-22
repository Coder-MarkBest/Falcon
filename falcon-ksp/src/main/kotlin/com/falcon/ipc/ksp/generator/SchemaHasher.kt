package com.falcon.ipc.ksp.generator

import com.falcon.ipc.annotations.MethodId
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType

/**
 * Computes a per-interface **wire-contract hash** that captures what `methodId` does not:
 * return types and the field layout of referenced Parcelable types. Exchanged once at
 * discovery (`__check_service__`) so client and server built from different definitions are
 * rejected instead of silently mis-decoding.
 *
 * **Honest limits:** field layout is read from declared-property order, which equals the
 * wire order for `@Parcelize`. A hand-written `Parcelable` whose `writeToParcel` order
 * differs from its property order is not perfectly captured — but adding/removing/retyping
 * a property still changes the hash. This is a best-effort safety net, not a proof.
 */
object SchemaHasher {
    private const val IPC_REPLY = "com.falcon.ipc.service.IpcReply"
    private const val LIST = "kotlin.collections.List"
    private const val MAP = "kotlin.collections.Map"
    private const val FLOW = "kotlinx.coroutines.flow.Flow"

    fun hash(methods: List<KSFunctionDeclaration>): Int {
        val parts = methods.map { m ->
            val params = m.parameters
                .filter { it.type.resolve().declaration.qualifiedName?.asString() != IPC_REPLY }
                .joinToString(",") { sig(it.type.resolve(), HashSet()) }
            // For @IpcCallback the reply type IS the effective "return"; otherwise the return type.
            val out = m.parameters
                .firstOrNull { it.type.resolve().declaration.qualifiedName?.asString() == IPC_REPLY }
                ?.let { sig(it.type.resolve(), HashSet()) }
                ?: (m.returnType?.resolve()?.let { sig(it, HashSet()) } ?: "Unit")
            "${m.simpleName.asString()}($params)->$out"
        }.sorted()
        return MethodId.signatureHash("schema", parts)
    }

    private fun isParcelable(decl: KSClassDeclaration): Boolean =
        decl.getAllSuperTypes().any { it.declaration.qualifiedName?.asString() == "android.os.Parcelable" }

    private fun argSig(t: KSType, idx: Int, visiting: MutableSet<String>): String =
        t.arguments.getOrNull(idx)?.type?.resolve()?.let { sig(it, visiting) } ?: "?"

    private fun sig(t: KSType, visiting: MutableSet<String>): String {
        val qn = t.declaration.qualifiedName?.asString() ?: "?"
        val nn = if (t.isMarkedNullable) "?" else ""
        when (qn) {
            LIST -> return "List<${argSig(t, 0, visiting)}>$nn"
            MAP -> return "Map<${argSig(t, 0, visiting)},${argSig(t, 1, visiting)}>$nn"
            FLOW -> return "Flow<${argSig(t, 0, visiting)}>$nn"
            IPC_REPLY -> return "Reply<${argSig(t, 0, visiting)}>"
        }
        val decl = t.declaration
        // Expand Parcelable field layout (declaration order = wire order for @Parcelize).
        // `visiting` guards against self-referential types causing infinite recursion.
        if (decl is KSClassDeclaration && decl.classKind == ClassKind.CLASS &&
            qn !in visiting && isParcelable(decl)
        ) {
            visiting.add(qn)
            val props = decl.getAllProperties()
                .joinToString(",") { "${it.simpleName.asString()}:${sig(it.type.resolve(), visiting)}" }
            return "$qn{$props}$nn"
        }
        return qn + nn
    }
}
