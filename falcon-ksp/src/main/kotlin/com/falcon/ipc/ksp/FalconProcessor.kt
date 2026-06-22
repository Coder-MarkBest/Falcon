package com.falcon.ipc.ksp

import com.falcon.ipc.ksp.generator.DispatcherGenerator
import com.falcon.ipc.ksp.generator.ProxyGenerator
import com.falcon.ipc.ksp.generator.RegistryGenerator
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate

class FalconProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String> = emptyMap()
) : SymbolProcessor {

    companion object {
        const val IPC_SERVICE = "com.falcon.ipc.service.IpcService"
        const val IPC_METHOD = "com.falcon.ipc.annotations.IpcMethod"
        val IPC_ANNOTATIONS = setOf(
            IPC_METHOD,
            "com.falcon.ipc.annotations.IpcCallback",
            "com.falcon.ipc.annotations.IpcEvent",
            "com.falcon.ipc.annotations.IpcStream"
        )
    }

    // Track interfaces already processed to avoid duplicate generation across KSP rounds
    private val processedInterfaces = HashSet<String>()

    // Accumulated registry entries: interfaces with ≥1 annotated method (Dispatcher generated)
    private val registryEntries = mutableListOf<RegistryGenerator.RegistryEntry>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val serviceInterfaces = resolver.getAllFiles()
            .flatMap { it.declarations }
            .filterIsInstance<KSClassDeclaration>()
            .filter { clazz ->
                clazz.superTypes.any { superType ->
                    superType.resolve().declaration.qualifiedName?.asString() == IPC_SERVICE
                }
            }
            .filter { it.validate() }

        serviceInterfaces.forEach { serviceInterface ->
            val qualifiedName = serviceInterface.qualifiedName?.asString() ?: return@forEach
            if (!processedInterfaces.add(qualifiedName)) {
                return@forEach // already generated in a prior KSP round
            }
            val interfaceName = serviceInterface.simpleName.asString()
            logger.info("Processing IPC service: $interfaceName")

            val annotatedMethods = serviceInterface.getAllFunctions()
                .filter { func ->
                    func.annotations.any { ann ->
                        ann.annotationType.resolve().declaration.qualifiedName?.asString() in IPC_ANNOTATIONS
                    }
                }
                .toList()

            if (annotatedMethods.isEmpty()) {
                logger.warn("No annotated methods found in $interfaceName")
                return@forEach
            }

            ProxyGenerator.generate(codeGenerator, logger, serviceInterface, annotatedMethods)

            // Generate typed IpcDispatcher for all annotated methods (generator classifies internally)
            if (annotatedMethods.isNotEmpty()) {
                DispatcherGenerator.generate(codeGenerator, logger, serviceInterface, annotatedMethods)

                // Accumulate registry entry — same naming conventions as each generator uses:
                //   dispatcher: "${ifaceName}_Dispatcher"
                //   proxy:      "${interfaceName.removePrefix("I")}_Proxy"
                val containingFile = serviceInterface.containingFile
                if (containingFile != null) {
                    registryEntries += RegistryGenerator.RegistryEntry(
                        serviceKeyQualifiedName = qualifiedName,
                        pkg = serviceInterface.packageName.asString(),
                        dispatcherClassName = "${interfaceName}_Dispatcher",
                        proxyClassName = "${interfaceName.removePrefix("I")}_Proxy",
                        schemaHash = com.falcon.ipc.ksp.generator.SchemaHasher.hash(annotatedMethods),
                        containingFile = containingFile
                    )
                }
            }

            logger.info("Generated Stub, Proxy, and Dispatcher for $interfaceName (${annotatedMethods.size} methods)")
        }

        return emptyList()
    }

    override fun finish() {
        if (registryEntries.isEmpty()) {
            logger.info("FalconProcessor.finish(): no @IpcMethod interfaces found, skipping registry generation")
            return
        }

        val moduleId = resolveModuleId()
        logger.info("FalconProcessor.finish(): generating ${moduleId}FalconGeneratedRegistry for ${registryEntries.size} interface(s)")
        RegistryGenerator.generate(codeGenerator, registryEntries, moduleId)
    }

    /**
     * Resolves the module ID for the generated registry object name.
     *
     * Priority:
     * 1. KSP option "falcon.moduleId" (set via ksp { arg("falcon.moduleId", "MyModule") } in build.gradle)
     * 2. Fallback: last segment of the first entry's package, capitalized and sanitized to a valid Kotlin identifier.
     *    e.g. "com.falcon.benchmark" → "Benchmark"
     */
    private fun resolveModuleId(): String {
        val fromOption = options["falcon.moduleId"]
        if (!fromOption.isNullOrBlank()) {
            return fromOption.sanitizeIdentifier().capitalize()
        }

        // Fallback: last segment of first entry's package
        val firstPkg = registryEntries.first().pkg
        val lastSegment = firstPkg.substringAfterLast(".")
        return lastSegment.sanitizeIdentifier().capitalize()
    }

    private fun String.sanitizeIdentifier(): String =
        this.replace(Regex("[^A-Za-z0-9_]"), "_")
            .let { if (it.isEmpty() || it[0].isDigit()) "_$it" else it }

    @Suppress("DEPRECATION")
    private fun String.capitalize(): String =
        replaceFirstChar { it.uppercaseChar() }
}
