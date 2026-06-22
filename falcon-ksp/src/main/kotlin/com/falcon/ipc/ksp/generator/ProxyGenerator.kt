package com.falcon.ipc.ksp.generator

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Modifier

object ProxyGenerator {

    private const val IPC_METHOD   = "com.falcon.ipc.annotations.IpcMethod"
    private const val IPC_EVENT    = "com.falcon.ipc.annotations.IpcEvent"
    private const val IPC_STREAM   = "com.falcon.ipc.annotations.IpcStream"
    private const val IPC_CALLBACK = "com.falcon.ipc.annotations.IpcCallback"
    private const val IPC_REPLY    = "com.falcon.ipc.service.IpcReply"

    private fun KSFunctionDeclaration.annotationQNs(): Set<String> =
        annotations.map { it.annotationType.resolve().declaration.qualifiedName?.asString() ?: "" }.toSet()

    fun generate(
        codeGenerator: CodeGenerator,
        logger: KSPLogger,
        serviceInterface: KSClassDeclaration,
        methods: List<KSFunctionDeclaration>
    ) {
        val packageName = serviceInterface.packageName.asString()
        val interfaceName = serviceInterface.simpleName.asString()
        val proxyName = "${interfaceName.removePrefix("I")}_Proxy"

        val code = buildString {
            appendLine("package $packageName")
            appendLine()
            appendLine("import android.os.Bundle")
            appendLine("import com.falcon.ipc.protocol.IpcEnvelope")
            appendLine("import com.falcon.ipc.transport.IpcTransport")
            appendLine("import com.falcon.ipc.transport.TransportResult")
            appendLine()
            appendLine("/**")
            appendLine(" * Auto-generated Proxy for $interfaceName.")
            appendLine(" * Forwards method calls through IPC transport using typed Bundle + stable methodId.")
            appendLine(" */")
            appendLine("class $proxyName(")
            appendLine("    private val transport: IpcTransport,")
            appendLine("    private val serviceKey: String")
            appendLine(") : $interfaceName {")
            appendLine()

            methods.forEach { method ->
                val methodName = method.simpleName.asString()
                val annQNs = method.annotationQNs()

                when {
                    IPC_EVENT in annQNs || IPC_STREAM in annQNs -> {
                        generateEventOrStream(this, logger, interfaceName, method, methodName)
                    }
                    IPC_CALLBACK in annQNs -> {
                        generateCallback(this, logger, interfaceName, method, methodName)
                    }
                    IPC_METHOD in annQNs -> {
                        generateIpcMethod(this, logger, interfaceName, method, methodName)
                    }
                    else -> {
                        // Unknown annotation — skip silently (e.g. lifecycle or marker methods)
                    }
                }
            }

            appendLine("}")
        }

        val file = codeGenerator.createNewFile(
            Dependencies(false), packageName, proxyName
        )
        file.write(code.toByteArray())
        file.close()
    }

    // ── @IpcEvent / @IpcStream ────────────────────────────────────────────────

    private fun generateEventOrStream(
        sb: StringBuilder,
        logger: KSPLogger,
        interfaceName: String,
        method: KSFunctionDeclaration,
        methodName: String
    ) {
        val returnTypeResolved = method.returnType!!.resolve()
        val id = MethodIds.of(method)

        // Build param declarations (Flow methods typically have no params, but be safe)
        val flowParamDecls = method.parameters.joinToString(", ") { param ->
            val pName = param.name?.asString() ?: "p${method.parameters.indexOf(param)}"
            val pType = renderTypeName(param.type.resolve())
            "$pName: $pType"
        }

        // Extract Flow<T> element type
        val elemType = returnTypeResolved.arguments.firstOrNull()?.type?.resolve()
        if (elemType == null) {
            logger.error("@IpcEvent/@IpcStream return type has no type argument in $interfaceName.$methodName")
            return
        }
        val elemTypeName = renderTypeName(elemType)

        val getExpr = TypeCodec.get(elemType, "__falconBundle", "r")
        if (getExpr == null) {
            logger.error(
                "@IpcEvent/@IpcStream element type ${elemType.declaration.qualifiedName?.asString() ?: "?"} " +
                    "unsupported in $interfaceName.$methodName; make it Parcelable"
            )
            return
        }

        sb.appendLine("    override fun $methodName($flowParamDecls): kotlinx.coroutines.flow.Flow<$elemTypeName> =")
        sb.appendLine("        com.falcon.ipc.core.EventProxy.typedRemoteFlow(")
        sb.appendLine("            serviceKey + \"#$id\", transport,")
        sb.appendLine("            capacity = com.falcon.ipc.Falcon.getInstance().eventBufferCapacity,")
        sb.appendLine("            overflow = com.falcon.ipc.Falcon.getInstance().eventOverflow")
        sb.appendLine("        ) { __falconBundle -> $getExpr }")
        sb.appendLine()
    }

    // ── @IpcCallback ──────────────────────────────────────────────────────────

    private fun generateCallback(
        sb: StringBuilder,
        logger: KSPLogger,
        interfaceName: String,
        method: KSFunctionDeclaration,
        methodName: String
    ) {
        val id = MethodIds.of(method)
        val allParams = method.parameters.toList()

        val replyParam = allParams.firstOrNull {
            it.type.resolve().declaration.qualifiedName?.asString() == IPC_REPLY
        }
        if (replyParam == null) {
            logger.error("@IpcCallback method $interfaceName.$methodName must declare an IpcReply<T> parameter")
            return
        }
        val regularParams = allParams.filter {
            it.type.resolve().declaration.qualifiedName?.asString() != IPC_REPLY
        }

        // Build parameter declaration string (all params including reply)
        val paramDecls = allParams.joinToString(", ") { param ->
            val pName = param.name?.asString() ?: "p${allParams.indexOf(param)}"
            val resolvedType = param.type.resolve()
            val pQN = resolvedType.declaration.qualifiedName?.asString()
            val pType = if (pQN == IPC_REPLY) {
                val typeArg = resolvedType.arguments.firstOrNull()?.type?.resolve()
                val simpleArg = if (typeArg != null) TypeCodec.render(typeArg) else "Any"
                "com.falcon.ipc.service.IpcReply<$simpleArg>"
            } else {
                renderTypeName(resolvedType)
            }
            "$pName: $pType"
        }

        sb.appendLine("    override fun $methodName($paramDecls) {")
        sb.appendLine("        val __falconArgs = android.os.Bundle()")

        // Encode each regular (non-reply) param positionally with key = index among regular params
        regularParams.forEachIndexed { i, param ->
            val pName = param.name?.asString() ?: "arg$i"
            val pType = param.type.resolve()
            val putExpr = TypeCodec.put(pType, "__falconArgs", i.toString(), pName)
            if (putExpr == null) {
                logger.error(
                    "@IpcCallback param type ${pType.declaration.qualifiedName?.asString() ?: "?"} " +
                        "unsupported in $interfaceName.$methodName; make it Parcelable"
                )
                return
            }
            sb.appendLine("        $putExpr")
        }

        // Determine reply type T from IpcReply<T>
        val getReplyExpr: String
        if (replyParam != null) {
            val replyType = replyParam.type.resolve().arguments.firstOrNull()?.type?.resolve()
            if (replyType == null) {
                logger.error("@IpcCallback reply param has no type argument in $interfaceName.$methodName")
                return
            }
            val getRaw = TypeCodec.get(replyType, "out", "r")
            if (getRaw == null) {
                logger.error(
                    "@IpcCallback reply type ${replyType.declaration.qualifiedName?.asString() ?: "?"} " +
                        "unsupported in $interfaceName.$methodName; make it Parcelable"
                )
                return
            }
            getReplyExpr = getRaw
        } else {
            getReplyExpr = "Unit"
        }

        val replyParamName = replyParam?.name?.asString() ?: "reply"

        sb.appendLine("        val __falconStub = object : com.falcon.ipc.aidl.IIpcEventCallback.Stub() {")
        sb.appendLine("            override fun onEvent(event: com.falcon.ipc.protocol.IpcEnvelope) {")
        sb.appendLine("                if (event.isError) {")
        sb.appendLine("                    $replyParamName.onError(event.errorCode, event.errorMessage)")
        sb.appendLine("                } else {")
        sb.appendLine("                    val out = event.argsBundle ?: android.os.Bundle()")
        sb.appendLine("                    try {")
        sb.appendLine("                        $replyParamName.onResult($getReplyExpr)")
        sb.appendLine("                    } catch (e: Exception) {")
        sb.appendLine("                        $replyParamName.onError(com.falcon.ipc.protocol.ErrorCode.SERIALIZATION_ERROR, \"Failed to decode reply: \${e.message}\")")
        sb.appendLine("                    }")
        sb.appendLine("                }")
        sb.appendLine("            }")
        sb.appendLine("            override fun getEventKey(): String = \"\"")
        sb.appendLine("        }")
        sb.appendLine("        try {")
        sb.appendLine("            transport.invokeCallback(")
        sb.appendLine("                com.falcon.ipc.protocol.IpcEnvelope(serviceKey = serviceKey, method = \"$methodName\", methodId = $id, argsBundle = __falconArgs),")
        sb.appendLine("                __falconStub")
        sb.appendLine("            )")
        sb.appendLine("        } catch (e: Exception) {")
        sb.appendLine("            $replyParamName.onError(com.falcon.ipc.protocol.ErrorCode.TRANSPORT_ERROR, \"invokeCallback failed: \${e.message}\")")
        sb.appendLine("        }")
        sb.appendLine("    }")
        sb.appendLine()
    }

    // ── @IpcMethod ────────────────────────────────────────────────────────────

    private fun generateIpcMethod(
        sb: StringBuilder,
        logger: KSPLogger,
        interfaceName: String,
        method: KSFunctionDeclaration,
        methodName: String
    ) {
        val returnTypeResolved = method.returnType?.resolve()
        val isSuspend = method.modifiers.contains(Modifier.SUSPEND)
        val params = method.parameters

        // Render param + return types via the shared renderer so generic arguments
        // (List<T>, Map<K, V>) and nullability are preserved.
        val paramDeclarations = params.joinToString(", ") { param ->
            val paramName = param.name?.asString() ?: "arg${params.indexOf(param)}"
            "$paramName: ${TypeCodec.render(param.type.resolve())}"
        }

        val suspendModifier = if (isSuspend) "suspend " else ""

        val returnTypeDef = returnTypeResolved?.declaration?.qualifiedName?.asString()
            ?: returnTypeResolved?.declaration?.simpleName?.asString()
            ?: "Any?"

        val simpleReturnType = if (returnTypeResolved != null) TypeCodec.render(returnTypeResolved) else "Any?"

        val methodId = MethodIds.of(method)

        // Generated locals are prefixed with `__falcon` so they cannot collide
        // with user-declared parameter names (e.g. a param literally named `b`).
        sb.appendLine("    override ${suspendModifier}fun $methodName($paramDeclarations): $simpleReturnType {")
        sb.appendLine("        val __falconArgs = Bundle()")

        params.forEachIndexed { i, param ->
            val paramName = param.name?.asString() ?: "arg$i"
            val paramType = param.type.resolve()
            val putExpr = TypeCodec.put(paramType, "__falconArgs", i.toString(), paramName)
            if (putExpr == null) {
                logger.error(
                    "@IpcMethod param type ${paramType.declaration.qualifiedName?.asString() ?: "?"} " +
                        "unsupported in $interfaceName.$methodName; make it Parcelable"
                )
                return
            }
            sb.appendLine("        $putExpr")
        }

        sb.appendLine("        val __falconEnv = com.falcon.ipc.protocol.IpcEnvelope(serviceKey = serviceKey, method = \"$methodName\", methodId = $methodId, argsBundle = __falconArgs)")
        sb.appendLine("        val __falconResult = transport.invoke(__falconEnv)")
        sb.appendLine("        return when (__falconResult) {")
        sb.appendLine("            is TransportResult.Success -> {")
        sb.appendLine("                val __falconOut = (__falconResult.data as? Bundle) ?: Bundle()")

        if (simpleReturnType == "Unit") {
            sb.appendLine("                Unit")
        } else {
            val getExpr = if (returnTypeResolved != null) TypeCodec.get(returnTypeResolved, "__falconOut", "r") else null
            if (getExpr == null) {
                logger.error(
                    "@IpcMethod return type $returnTypeDef unsupported in $interfaceName.$methodName; " +
                        "make it Parcelable"
                )
                return
            }
            sb.appendLine("                $getExpr")
        }

        sb.appendLine("            }")
        sb.appendLine("            is TransportResult.Error ->")
        sb.appendLine("                throw com.falcon.ipc.protocol.IpcException(__falconResult.code, \"IPC error [\${__falconResult.code}]: \${__falconResult.message}\")")
        sb.appendLine("        }")
        sb.appendLine("    }")
        sb.appendLine()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Render a KSType as Kotlin source (generic-aware, nullability-aware). */
    private fun renderTypeName(t: com.google.devtools.ksp.symbol.KSType): String =
        TypeCodec.render(t)

    /** Map a fully-qualified name to its simple/kotlin form (used for IpcReply<T> arg). */
    private fun renderQualifiedName(qn: String): String = when (qn) {
        "kotlin.Int" -> "Int"
        "kotlin.Long" -> "Long"
        "kotlin.Float" -> "Float"
        "kotlin.Double" -> "Double"
        "kotlin.Boolean" -> "Boolean"
        "kotlin.String" -> "String"
        "kotlin.ByteArray" -> "ByteArray"
        "kotlin.Unit" -> "Unit"
        else -> qn
    }
}
