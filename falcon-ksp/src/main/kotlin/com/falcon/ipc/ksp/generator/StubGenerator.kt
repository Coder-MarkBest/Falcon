package com.falcon.ipc.ksp.generator

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration

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

            methods.forEach { method ->
                val methodName = method.simpleName.asString()
                appendLine("            \"$methodName\" -> {")
                appendLine("                try {")
                appendLine("                    // TODO: Deserialize args and invoke impl.$methodName()")
                appendLine("                    IpcEnvelope.response(envelope.requestId, null)")
                appendLine("                } catch (e: Exception) {")
                appendLine("                    IpcEnvelope.error(ErrorCode.UNKNOWN, e.message ?: \"Unknown\", envelope.requestId)")
                appendLine("                }")
                appendLine("            }")
            }

            appendLine("            else -> IpcEnvelope.error(ErrorCode.METHOD_NOT_FOUND, \"Unknown method: \${envelope.method}\", envelope.requestId)")
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
}
