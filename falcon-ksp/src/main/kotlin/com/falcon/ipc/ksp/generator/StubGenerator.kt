package com.falcon.ipc.ksp.generator

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Modifier

object StubGenerator {

    fun generate(
        codeGenerator: CodeGenerator,
        serviceInterface: KSClassDeclaration,
        methods: List<KSFunctionDeclaration>
    ) {
        val packageName = serviceInterface.packageName.asString()
        val interfaceName = serviceInterface.simpleName.asString()
        val stubName = "${interfaceName.removePrefix("I")}_Stub"

        val code = buildString {
            appendLine("package $packageName")
            appendLine()
            appendLine("import com.falcon.ipc.protocol.IpcEnvelope")
            appendLine("import com.falcon.ipc.protocol.ErrorCode")
            appendLine()
            appendLine("/**")
            appendLine(" * Auto-generated Stub for $interfaceName.")
            appendLine(" * Routes incoming IPC calls to the local implementation.")
            appendLine(" */")
            appendLine("class $stubName(private val impl: $interfaceName) {")
            appendLine()
            appendLine("    fun dispatch(envelope: IpcEnvelope): IpcEnvelope {")
            appendLine("        return when (envelope.method) {")

            val IPC_METHOD_QN = "com.falcon.ipc.annotations.IpcMethod"
            methods.forEach { method ->
                // StubGenerator only handles @IpcMethod request/response; skip event/callback methods
                val hasIpcMethod = method.annotations.any {
                    it.annotationType.resolve().declaration.qualifiedName?.asString() == IPC_METHOD_QN
                }
                if (!hasIpcMethod) return@forEach

                val methodName = method.simpleName.asString()
                val params = method.parameters
                val isSuspend = method.modifiers.contains(Modifier.SUSPEND)

                // Build type array for deserialization
                val typeArray = params.joinToString(", ") { param ->
                    val typeName = param.type.resolve().declaration.qualifiedName?.asString()
                        ?: param.type.resolve().declaration.simpleName.asString()
                    mapTypeToClass(typeName)
                }

                // Build invocation args
                val invocationArgs = params.mapIndexed { index, param ->
                    val typeName = param.type.resolve().declaration.qualifiedName?.asString()
                        ?: param.type.resolve().declaration.simpleName.asString()
                    "args[$index] as ${mapTypeToKotlin(typeName)}"
                }.joinToString(", ")

                appendLine("            \"$methodName\" -> {")
                appendLine("                try {")
                if (params.isEmpty()) {
                    appendLine("                    val result = impl.$methodName()")
                } else {
                    appendLine("                    val args = com.falcon.ipc.protocol.IpcSerializer.deserializeArgs(")
                    appendLine("                        envelope.args ?: ByteArray(0),")
                    appendLine("                        arrayOf($typeArray)")
                    appendLine("                    )")
                    appendLine("                    val result = impl.$methodName($invocationArgs)")
                }
                appendLine("                    com.falcon.ipc.protocol.IpcEnvelope.response(")
                appendLine("                        envelope.requestId,")
                appendLine("                        com.falcon.ipc.protocol.IpcSerializer.serializeResult(result)")
                appendLine("                    )")
                appendLine("                } catch (e: Exception) {")
                appendLine("                    com.falcon.ipc.protocol.IpcEnvelope.error(")
                appendLine("                        com.falcon.ipc.protocol.ErrorCode.UNKNOWN,")
                appendLine("                        e.message ?: \"Unknown\",")
                appendLine("                        envelope.requestId")
                appendLine("                    )")
                appendLine("                }")
                appendLine("            }")
            }

            appendLine("            else -> com.falcon.ipc.protocol.IpcEnvelope.error(")
            appendLine("                com.falcon.ipc.protocol.ErrorCode.METHOD_NOT_FOUND,")
            appendLine("                \"Unknown method: \${envelope.method}\",")
            appendLine("                envelope.requestId")
            appendLine("            )")
            appendLine("        }")
            appendLine("    }")
            appendLine("}")
        }

        val file = codeGenerator.createNewFile(
            Dependencies(false), packageName, stubName
        )
        file.write(code.toByteArray())
        file.close()
    }

    private fun mapTypeToClass(typeName: String): String {
        return when (typeName) {
            "kotlin.Int" -> "Int::class.java"
            "kotlin.Long" -> "Long::class.java"
            "kotlin.Float" -> "Float::class.java"
            "kotlin.Double" -> "Double::class.java"
            "kotlin.Boolean" -> "Boolean::class.java"
            "kotlin.String" -> "String::class.java"
            "kotlin.ByteArray" -> "ByteArray::class.java"
            "kotlin.Unit" -> "Unit::class.java"
            "kotlin.Any" -> "Any::class.java"
            else -> "${typeName.substringAfterLast(".")}::class.java"
        }
    }

    private fun mapTypeToKotlin(typeName: String): String {
        return when (typeName) {
            "kotlin.Int" -> "Int"
            "kotlin.Long" -> "Long"
            "kotlin.Float" -> "Float"
            "kotlin.Double" -> "Double"
            "kotlin.Boolean" -> "Boolean"
            "kotlin.String" -> "String"
            "kotlin.ByteArray" -> "ByteArray"
            "kotlin.Unit" -> "Unit"
            "kotlin.Any" -> "Any?"
            else -> typeName.substringAfterLast(".")
        }
    }
}
