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

            methods.forEach { method ->
                val methodName = method.simpleName.asString()
                val returnTypeResolved = method.returnType?.resolve()
                val returnTypeSimpleName = returnTypeResolved?.declaration?.simpleName?.asString() ?: "Any?"
                val isFlow = returnTypeSimpleName == "Flow"
                val isSuspend = method.modifiers.contains(Modifier.SUSPEND)

                if (isFlow) {
                    appendLine("    // Event/Stream method: $methodName — returns Flow, handled by runtime")
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
