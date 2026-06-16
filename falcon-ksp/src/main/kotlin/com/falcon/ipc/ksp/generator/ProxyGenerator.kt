package com.falcon.ipc.ksp.generator

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration

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
            appendLine("import com.falcon.ipc.transport.IpcTransport")
            appendLine()
            appendLine("/**")
            appendLine(" * Auto-generated Proxy for $interfaceName.")
            appendLine(" * Forwards method calls through IPC transport.")
            appendLine(" */")
            appendLine("class $proxyName(")
            appendLine("    private val transport: IpcTransport,")
            appendLine("    private val serviceKey: String")
            appendLine(") {")
            appendLine()

            methods.forEach { method ->
                val methodName = method.simpleName.asString()
                val returnType = method.returnType?.resolve()?.declaration?.simpleName?.asString() ?: "Any?"
                val isFlow = returnType == "Flow"

                if (isFlow) {
                    appendLine("    // Event/Stream method: $methodName — returns Flow, handled by runtime")
                } else {
                    appendLine("    suspend fun $methodName(): $returnType {")
                    appendLine("        val envelope = IpcEnvelope(")
                    appendLine("            serviceKey = serviceKey,")
                    appendLine("            method = \"$methodName\"")
                    appendLine("        )")
                    appendLine("        val result = transport.invoke(envelope)")
                    appendLine("        // TODO: Deserialize result based on return type")
                    appendLine("        TODO(\"Proxy deserialization for $methodName\")")
                    appendLine("    }")
                }
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
