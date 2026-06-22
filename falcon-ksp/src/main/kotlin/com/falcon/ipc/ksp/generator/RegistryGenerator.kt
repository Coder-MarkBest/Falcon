package com.falcon.ipc.ksp.generator

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.KSFile

object RegistryGenerator {

    data class RegistryEntry(
        val serviceKeyQualifiedName: String,  // interface qualified name = serviceKey
        val pkg: String,                       // package of generated dispatcher/proxy classes
        val dispatcherClassName: String,       // e.g. IBenchmarkFalconService_Dispatcher
        val proxyClassName: String,            // e.g. BenchmarkFalconService_Proxy
        val schemaHash: Int,                   // wire-contract hash (see SchemaHasher)
        val containingFile: KSFile
    )

    /**
     * Generates ONE aggregated object implementing FalconGeneratedRegistry.
     *
     * Only interfaces with ≥1 @IpcMethod are included (they produced a Dispatcher).
     * Proxy is included in the same map only when a Dispatcher was generated
     * (Flow-only interfaces are deferred to a future plan).
     *
     * Emitted once from finish() so there are no duplicate-file issues across KSP rounds.
     */
    fun generate(
        cg: CodeGenerator,
        entries: List<RegistryEntry>,
        moduleId: String
    ) {
        require(entries.isNotEmpty()) { "RegistryGenerator.generate() called with empty entries" }

        val objectName = "${moduleId}FalconGeneratedRegistry"
        val outputPkg = "com.falcon.ipc.generated"

        val sources = entries.map { it.containingFile }.toTypedArray()
        val deps = Dependencies(aggregating = true, *sources)

        val sb = StringBuilder()
        sb.appendLine("package $outputPkg")
        sb.appendLine()
        sb.appendLine("import com.falcon.ipc.runtime.FalconGeneratedRegistry")
        sb.appendLine("import com.falcon.ipc.runtime.IpcDispatcher")
        sb.appendLine("import com.falcon.ipc.transport.IpcTransport")
        sb.appendLine()
        sb.appendLine("object $objectName : FalconGeneratedRegistry {")
        sb.appendLine()

        // dispatcherFactories
        sb.appendLine("    override val dispatcherFactories: Map<String, (Any) -> IpcDispatcher> = mapOf(")
        entries.forEachIndexed { idx, e ->
            val trailing = if (idx < entries.size - 1) "," else ""
            sb.appendLine("        \"${e.serviceKeyQualifiedName}\" to { impl -> ${e.pkg}.${e.dispatcherClassName}(impl as ${e.serviceKeyQualifiedName}) }$trailing")
        }
        sb.appendLine("    )")
        sb.appendLine()

        // proxyFactories
        sb.appendLine("    override val proxyFactories: Map<String, (IpcTransport, String) -> Any> = mapOf(")
        entries.forEachIndexed { idx, e ->
            val trailing = if (idx < entries.size - 1) "," else ""
            sb.appendLine("        \"${e.serviceKeyQualifiedName}\" to { t, k -> ${e.pkg}.${e.proxyClassName}(t, k) }$trailing")
        }
        sb.appendLine("    )")
        sb.appendLine()

        // interfaceSchemas — wire-contract hash per service, checked once at discovery.
        sb.appendLine("    override val interfaceSchemas: Map<String, Int> = mapOf(")
        entries.forEachIndexed { idx, e ->
            val trailing = if (idx < entries.size - 1) "," else ""
            sb.appendLine("        \"${e.serviceKeyQualifiedName}\" to ${e.schemaHash}$trailing")
        }
        sb.appendLine("    )")
        sb.appendLine()

        sb.appendLine("}")

        cg.createNewFile(deps, outputPkg, objectName)
            .use { it.write(sb.toString().toByteArray()) }
    }
}
