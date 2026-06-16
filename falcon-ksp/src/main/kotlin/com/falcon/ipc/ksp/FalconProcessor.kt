package com.falcon.ipc.ksp

import com.falcon.ipc.ksp.generator.ProxyGenerator
import com.falcon.ipc.ksp.generator.StubGenerator
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate

class FalconProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    companion object {
        const val IPC_SERVICE = "com.falcon.ipc.service.IpcService"
        val IPC_ANNOTATIONS = setOf(
            "com.falcon.ipc.annotations.IpcMethod",
            "com.falcon.ipc.annotations.IpcCallback",
            "com.falcon.ipc.annotations.IpcEvent",
            "com.falcon.ipc.annotations.IpcStream"
        )
    }

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

            StubGenerator.generate(codeGenerator, serviceInterface, annotatedMethods)
            ProxyGenerator.generate(codeGenerator, serviceInterface, annotatedMethods)

            logger.info("Generated Stub and Proxy for $interfaceName (${annotatedMethods.size} methods)")
        }

        return emptyList()
    }
}
