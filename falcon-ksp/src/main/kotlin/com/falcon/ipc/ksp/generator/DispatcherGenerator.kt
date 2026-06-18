package com.falcon.ipc.ksp.generator

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Modifier

object DispatcherGenerator {

    private const val IPC_METHOD   = "com.falcon.ipc.annotations.IpcMethod"
    private const val IPC_EVENT    = "com.falcon.ipc.annotations.IpcEvent"
    private const val IPC_STREAM   = "com.falcon.ipc.annotations.IpcStream"
    private const val IPC_CALLBACK = "com.falcon.ipc.annotations.IpcCallback"
    private const val IPC_REPLY    = "com.falcon.ipc.service.IpcReply"

    private fun KSFunctionDeclaration.annotationQNs(): Set<String> =
        annotations.map { it.annotationType.resolve().declaration.qualifiedName?.asString() ?: "" }.toSet()

    fun generate(
        cg: CodeGenerator,
        logger: KSPLogger,
        iface: KSClassDeclaration,
        methods: List<KSFunctionDeclaration>
    ): Boolean {
        val pkg = iface.packageName.asString()
        val ifaceName = iface.simpleName.asString()
        val name = "${ifaceName}_Dispatcher"
        val seen = HashSet<Int>()
        val sb = StringBuilder()

        // Classify methods by annotation
        val ipcMethods   = methods.filter { IPC_METHOD   in it.annotationQNs() }
        val eventMethods = methods.filter { m -> m.annotationQNs().let { IPC_EVENT in it || IPC_STREAM in it } }
        val cbMethods    = methods.filter { IPC_CALLBACK in it.annotationQNs() }

        sb.appendLine("package $pkg")
        sb.appendLine()
        sb.appendLine("import android.os.Bundle")
        sb.appendLine("import com.falcon.ipc.runtime.IpcDispatcher")
        if (eventMethods.isNotEmpty()) {
            sb.appendLine("import kotlinx.coroutines.flow.Flow")
            sb.appendLine("import kotlinx.coroutines.flow.map")
        }
        sb.appendLine()
        sb.appendLine("class $name(private val impl: $ifaceName) : IpcDispatcher {")

        // ── dispatch (IpcMethod) ────────────────────────────────────────────
        sb.appendLine("    override fun dispatch(methodId: Int, args: Bundle): Bundle {")
        sb.appendLine("        val out = Bundle()")
        sb.appendLine("        when (methodId) {")

        for (m in ipcMethods) {
            val mName = m.simpleName.asString()
            val paramTypes = m.parameters.map { it.type.resolve() }
            val id = MethodIds.of(m)

            if (!seen.add(id)) {
                logger.error("methodId collision in $ifaceName for $mName")
                return false
            }

            val argExprs = paramTypes.mapIndexed { i, t ->
                TypeCodec.get(t, "args", i.toString())
                    ?: run {
                        logger.error(
                            "@IpcMethod param type ${t.declaration.qualifiedName?.asString() ?: "?"} unsupported in $ifaceName.$mName; " +
                                "make it Parcelable"
                        )
                        return false
                    }
            }

            val ret = m.returnType!!.resolve()
            val isSuspend = m.modifiers.contains(Modifier.SUSPEND)
            val call = "impl.$mName(${argExprs.joinToString(", ")})"
            val wrapped = if (isSuspend) "kotlinx.coroutines.runBlocking { $call }" else call

            sb.appendLine("            $id -> {")
            sb.appendLine("                val r = $wrapped")
            if (!TypeCodec.isUnit(ret)) {
                val putR = TypeCodec.put(ret, "out", "r", "r")
                    ?: run {
                        logger.error(
                            "@IpcMethod return type unsupported in $ifaceName.$mName; " +
                                "make it Parcelable"
                        )
                        return false
                    }
                sb.appendLine("                $putR")
            }
            sb.appendLine("            }")
        }

        sb.appendLine("            else -> throw IllegalArgumentException(\"Unknown methodId: \$methodId in $ifaceName\")")
        sb.appendLine("        }")
        sb.appendLine("        return out")
        sb.appendLine("    }")

        // ── eventFlow (IpcEvent / IpcStream) ───────────────────────────────
        if (eventMethods.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("    override fun eventFlow(methodId: Int): Flow<Bundle>? {")
            sb.appendLine("        return when (methodId) {")

            for (m in eventMethods) {
                val mName = m.simpleName.asString()
                val id = MethodIds.of(m)

                if (!seen.add(id)) {
                    logger.error("methodId collision in $ifaceName for $mName")
                    return false
                }

                // Flow<T> — extract T from the return type's first type argument
                val elemType = m.returnType!!.resolve().arguments.first().type!!.resolve()

                val putElem = TypeCodec.put(elemType, "it", "r", "v")
                    ?: run {
                        logger.error(
                            "@IpcEvent/@IpcStream element type ${elemType.declaration.qualifiedName?.asString() ?: "?"} unsupported in $ifaceName.$mName; " +
                                "make it Parcelable"
                        )
                        return false
                    }

                sb.appendLine("            $id -> impl.$mName().map { v -> android.os.Bundle().also { $putElem } }")
            }

            sb.appendLine("            else -> null")
            sb.appendLine("        }")
            sb.appendLine("    }")
        }

        // ── invokeCallback (IpcCallback) ───────────────────────────────────
        if (cbMethods.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("    override fun invokeCallback(methodId: Int, args: Bundle, reply: (Bundle) -> Unit) {")
            sb.appendLine("        when (methodId) {")

            for (m in cbMethods) {
                val mName = m.simpleName.asString()
                val id = MethodIds.of(m)

                if (!seen.add(id)) {
                    logger.error("methodId collision in $ifaceName for $mName")
                    return false
                }

                // Split params: regular args vs IpcReply
                val allParams = m.parameters.toList()
                val replyParam = allParams.firstOrNull {
                    it.type.resolve().declaration.qualifiedName?.asString() == IPC_REPLY
                }
                val regularParams = allParams.filter {
                    it.type.resolve().declaration.qualifiedName?.asString() != IPC_REPLY
                }

                sb.appendLine("            $id -> {")

                // Decode regular params positionally (keys "0", "1", ...)
                val argNames = regularParams.mapIndexed { i, p ->
                    val paramName = p.name?.asString() ?: "arg$i"
                    val paramType = p.type.resolve()
                    val getter = TypeCodec.get(paramType, "args", i.toString())
                        ?: run {
                            logger.error(
                                "@IpcCallback param type ${paramType.declaration.qualifiedName?.asString() ?: "?"} unsupported in $ifaceName.$mName; " +
                                    "make it Parcelable"
                            )
                            return false
                        }
                    sb.appendLine("                val $paramName = $getter")
                    paramName
                }

                // Determine reply type T from IpcReply<T>
                val replyTypeStr: String
                val putReply: String
                if (replyParam != null) {
                    val replyType = replyParam.type.resolve().arguments.first().type!!.resolve()
                    replyTypeStr = replyType.declaration.qualifiedName?.asString() ?: "Any"
                    putReply = TypeCodec.put(replyType, "it", "r", "data")
                        ?: run {
                            logger.error(
                                "@IpcCallback reply type $replyTypeStr unsupported in $ifaceName.$mName; " +
                                    "make it Parcelable"
                            )
                            return false
                        }
                } else {
                    replyTypeStr = "Unit"
                    putReply = ""
                }

                // Build call args: regular args first, then IpcReply anonymous object
                val callArgs = argNames.toMutableList()
                if (replyParam != null) {
                    callArgs += "object : com.falcon.ipc.service.IpcReply<$replyTypeStr> {\n" +
                        "                    override fun onResult(data: $replyTypeStr) { reply(android.os.Bundle().also { $putReply }) }\n" +
                        "                }"
                }

                sb.appendLine("                impl.$mName(${callArgs.joinToString(", ")})")
                sb.appendLine("            }")
            }

            sb.appendLine("            else -> super.invokeCallback(methodId, args, reply)")
            sb.appendLine("        }")
            sb.appendLine("    }")
        }

        sb.appendLine("}")

        cg.createNewFile(
            Dependencies(false, iface.containingFile!!),
            pkg,
            name
        ).use { it.write(sb.toString().toByteArray()) }

        return true
    }
}
