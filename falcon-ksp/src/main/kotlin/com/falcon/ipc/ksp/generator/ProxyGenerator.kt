package com.falcon.ipc.ksp.generator

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Modifier

object ProxyGenerator {

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

            val IPC_CALLBACK_QN = "com.falcon.ipc.annotations.IpcCallback"
            val IPC_REPLY_QN    = "com.falcon.ipc.service.IpcReply"

            methods.forEach { method ->
                val methodName = method.simpleName.asString()
                val returnTypeResolved = method.returnType?.resolve()
                val returnTypeSimpleName = returnTypeResolved?.declaration?.simpleName?.asString() ?: "Any?"
                val isFlow = returnTypeSimpleName == "Flow"
                val isCallback = method.annotations.any {
                    it.annotationType.resolve().declaration.qualifiedName?.asString() == IPC_CALLBACK_QN
                }
                val isSuspend = method.modifiers.contains(Modifier.SUSPEND)

                if (isFlow) {
                    // Build parameter list for Flow methods (typically none, but be safe)
                    val flowParamDecls = method.parameters.joinToString(", ") { param ->
                        val pName = param.name?.asString() ?: "p${method.parameters.indexOf(param)}"
                        val pQN = param.type.resolve().declaration.qualifiedName?.asString()
                            ?: param.type.resolve().declaration.simpleName.asString()
                        val pType = when (pQN) {
                            "kotlin.Int" -> "Int"; "kotlin.Long" -> "Long"
                            "kotlin.Float" -> "Float"; "kotlin.Double" -> "Double"
                            "kotlin.Boolean" -> "Boolean"; "kotlin.String" -> "String"
                            "kotlin.ByteArray" -> "ByteArray"; "kotlin.Unit" -> "Unit"
                            else -> pQN.substringAfterLast(".")
                        }
                        "$pName: $pType"
                    }
                    // Reconstruct exact Flow<T> return type
                    val elemQN = returnTypeResolved?.arguments?.firstOrNull()?.type?.resolve()
                        ?.declaration?.qualifiedName?.asString() ?: "Any"
                    val elemSimple = when (elemQN) {
                        "kotlin.Int" -> "Int"; "kotlin.Long" -> "Long"
                        "kotlin.Float" -> "Float"; "kotlin.Double" -> "Double"
                        "kotlin.Boolean" -> "Boolean"; "kotlin.String" -> "String"
                        "kotlin.ByteArray" -> "ByteArray"
                        else -> elemQN.substringAfterLast(".")
                    }
                    // Must implement the interface method — stub that errors at runtime
                    appendLine("    // Event/Stream method: $methodName — real Flow is provided by runtime event subscription")
                    appendLine("    @Suppress(\"UNCHECKED_CAST\")")
                    appendLine("    override fun $methodName($flowParamDecls): kotlinx.coroutines.flow.Flow<$elemSimple> =")
                    appendLine("        error(\"$methodName: subscribe via FalconManager.remoteFlow(), not direct call\")")
                    appendLine()
                    return@forEach
                }

                if (isCallback) {
                    // Stub implementation — real dispatch is handled by runtime via invokeCallback
                    val paramDecls = method.parameters.joinToString(", ") { param ->
                        val pName = param.name?.asString() ?: "p${method.parameters.indexOf(param)}"
                        val resolvedType = param.type.resolve()
                        val pQN = resolvedType.declaration.qualifiedName?.asString()
                            ?: resolvedType.declaration.simpleName.asString()
                        // Handle IpcReply<T> specially — reconstruct generic type string
                        val pType = if (pQN == IPC_REPLY_QN) {
                            val typeArg = resolvedType.arguments.firstOrNull()?.type?.resolve()
                                ?.declaration?.qualifiedName?.asString() ?: "Any"
                            val simpleArg = when (typeArg) {
                                "kotlin.Int" -> "Int"; "kotlin.Long" -> "Long"
                                "kotlin.Float" -> "Float"; "kotlin.Double" -> "Double"
                                "kotlin.Boolean" -> "Boolean"; "kotlin.String" -> "String"
                                "kotlin.ByteArray" -> "ByteArray"
                                else -> typeArg.substringAfterLast(".")
                            }
                            "com.falcon.ipc.service.IpcReply<$simpleArg>"
                        } else {
                            when (pQN) {
                                "kotlin.Int" -> "Int"; "kotlin.Long" -> "Long"
                                "kotlin.Float" -> "Float"; "kotlin.Double" -> "Double"
                                "kotlin.Boolean" -> "Boolean"; "kotlin.String" -> "String"
                                "kotlin.ByteArray" -> "ByteArray"; "kotlin.Unit" -> "Unit"
                                else -> pQN.substringAfterLast(".")
                            }
                        }
                        "$pName: $pType"
                    }
                    appendLine("    override fun $methodName($paramDecls) {")
                    appendLine("        error(\"$methodName: callback methods must be invoked via IpcDispatcher.invokeCallback\")")
                    appendLine("    }")
                    appendLine()
                    return@forEach
                }

                // Build parameter list
                val params = method.parameters
                val paramDeclarations = params.joinToString(", ") { param ->
                    val paramName = param.name?.asString() ?: "arg${params.indexOf(param)}"
                    val paramType = param.type.resolve().declaration.qualifiedName?.asString()
                        ?: param.type.resolve().declaration.simpleName.asString()
                    "$paramName: $paramType"
                }

                val suspendModifier = if (isSuspend) "suspend " else ""

                val returnTypeDef = returnTypeResolved?.declaration?.qualifiedName?.asString()
                    ?: returnTypeResolved?.declaration?.simpleName?.asString()
                    ?: "Any?"

                // Map qualified return type to simple form for generated code
                val simpleReturnType = when (returnTypeDef) {
                    "kotlin.Int" -> "Int"
                    "kotlin.Long" -> "Long"
                    "kotlin.Float" -> "Float"
                    "kotlin.Double" -> "Double"
                    "kotlin.Boolean" -> "Boolean"
                    "kotlin.String" -> "String"
                    "kotlin.Unit" -> "Unit"
                    "kotlin.ByteArray" -> "ByteArray"
                    "kotlin.Any" -> "Any?"
                    else -> returnTypeDef.substringAfterLast(".")
                }

                val methodId = MethodIds.of(method)

                appendLine("    override ${suspendModifier}fun $methodName($paramDeclarations): $simpleReturnType {")
                appendLine("        val b = Bundle()")

                // Emit TypeCodec.put for each parameter
                params.forEachIndexed { i, param ->
                    val paramName = param.name?.asString() ?: "arg$i"
                    val paramType = param.type.resolve()
                    val putExpr = TypeCodec.put(paramType, "b", i.toString(), paramName)
                    if (putExpr == null) {
                        logger.error(
                            "@IpcMethod param type ${paramType.declaration.qualifiedName?.asString() ?: "?"} " +
                                "unsupported in $interfaceName.$methodName; make it Parcelable"
                        )
                        return@forEach
                    }
                    appendLine("        $putExpr")
                }

                appendLine("        val env = com.falcon.ipc.protocol.IpcEnvelope(serviceKey = serviceKey, method = \"$methodName\", methodId = $methodId, argsBundle = b)")
                appendLine("        val result = transport.invoke(env)")
                appendLine("        return when (result) {")
                appendLine("            is TransportResult.Success -> {")
                appendLine("                val out = (result.data as? Bundle) ?: Bundle()")

                if (simpleReturnType == "Unit") {
                    appendLine("                Unit")
                } else {
                    val getExpr = if (returnTypeResolved != null) TypeCodec.get(returnTypeResolved, "out", "r") else null
                    if (getExpr == null) {
                        logger.error(
                            "@IpcMethod return type $returnTypeDef unsupported in $interfaceName.$methodName; " +
                                "make it Parcelable"
                        )
                        return@forEach
                    }
                    appendLine("                $getExpr")
                }

                appendLine("            }")
                appendLine("            is TransportResult.Error ->")
                appendLine("                throw RuntimeException(\"IPC error [\${result.code}]: \${result.message}\")")
                appendLine("        }")
                appendLine("    }")
                appendLine()
            }

            appendLine("}")
        }

        val file = codeGenerator.createNewFile(
            Dependencies(false), packageName, proxyName
        )
        file.write(code.toByteArray())
        file.close()
    }
}
