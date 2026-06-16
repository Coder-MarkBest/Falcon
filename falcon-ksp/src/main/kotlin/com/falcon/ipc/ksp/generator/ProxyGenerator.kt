package com.falcon.ipc.ksp.generator

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Modifier

object ProxyGenerator {

    fun generate(
        codeGenerator: CodeGenerator,
        serviceInterface: KSClassDeclaration,
        methods: List<KSFunctionDeclaration>
    ) {
        val packageName = serviceInterface.packageName.asString()
        val interfaceName = serviceInterface.simpleName.asString()
        val proxyName = "${interfaceName.removePrefix("I")}_Proxy"

        val code = buildString {
            appendLine("package $packageName")
            appendLine()
            appendLine("import com.falcon.ipc.protocol.IpcEnvelope")
            appendLine("import com.falcon.ipc.protocol.IpcSerializer")
            appendLine("import com.falcon.ipc.transport.IpcTransport")
            appendLine("import com.falcon.ipc.transport.TransportResult")
            appendLine()
            appendLine("/**")
            appendLine(" * Auto-generated Proxy for $interfaceName.")
            appendLine(" * Forwards method calls through IPC transport.")
            appendLine(" */")
            appendLine("class $proxyName(")
            appendLine("    private val transport: IpcTransport,")
            appendLine("    private val serviceKey: String")
            appendLine(") : $interfaceName {")
            appendLine()

            methods.forEach { method ->
                val methodName = method.simpleName.asString()
                val returnType = method.returnType?.resolve()?.declaration?.simpleName?.asString() ?: "Any?"
                val isFlow = returnType == "Flow"
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

                val paramNames = params.joinToString(", ") { param ->
                    param.name?.asString() ?: "arg${params.indexOf(param)}"
                }

                val suspendModifier = if (isSuspend) "suspend " else ""
                val returnTypeDef = method.returnType?.resolve()?.declaration?.qualifiedName?.asString()
                    ?: method.returnType?.resolve()?.declaration?.simpleName?.asString()
                    ?: "Any?"

                // Map qualified return type to simple form for generated code
                val simpleReturnType = when {
                    returnTypeDef == "kotlin.Int" -> "Int"
                    returnTypeDef == "kotlin.Long" -> "Long"
                    returnTypeDef == "kotlin.Float" -> "Float"
                    returnTypeDef == "kotlin.Double" -> "Double"
                    returnTypeDef == "kotlin.Boolean" -> "Boolean"
                    returnTypeDef == "kotlin.String" -> "String"
                    returnTypeDef == "kotlin.Unit" -> "Unit"
                    returnTypeDef == "kotlin.ByteArray" -> "ByteArray"
                    returnTypeDef == "kotlin.Any" -> "Any?"
                    else -> returnTypeDef.substringAfterLast(".")
                }

                val returnClassExpr = when (simpleReturnType) {
                    "Int" -> "Int::class.java"
                    "Long" -> "Long::class.java"
                    "Float" -> "Float::class.java"
                    "Double" -> "Double::class.java"
                    "Boolean" -> "Boolean::class.java"
                    "String" -> "String::class.java"
                    "ByteArray" -> "ByteArray::class.java"
                    "Unit" -> "Unit::class.java"
                    else -> "$simpleReturnType::class.java"
                }

                appendLine("    override ${suspendModifier}fun $methodName($paramDeclarations): $simpleReturnType {")
                appendLine("        val args = com.falcon.ipc.protocol.IpcSerializer.serializeArgs(arrayOf($paramNames))")
                appendLine("        val envelope = com.falcon.ipc.protocol.IpcEnvelope(")
                appendLine("            serviceKey = serviceKey,")
                appendLine("            method = \"$methodName\",")
                appendLine("            args = args")
                appendLine("        )")
                appendLine("        val result = transport.invoke(envelope)")
                appendLine("        return when (result) {")
                appendLine("            is com.falcon.ipc.transport.TransportResult.Success -> {")
                if (simpleReturnType == "Unit") {
                    appendLine("                Unit")
                } else {
                    appendLine("                com.falcon.ipc.protocol.IpcSerializer.deserializeResult(")
                    appendLine("                    result.data as? ByteArray ?: ByteArray(0), $returnClassExpr")
                    appendLine("                )!!")
                }
                appendLine("            }")
                appendLine("            is com.falcon.ipc.transport.TransportResult.Error -> {")
                appendLine("                throw RuntimeException(\"IPC error: \${result.message}\")")
                appendLine("            }")
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
