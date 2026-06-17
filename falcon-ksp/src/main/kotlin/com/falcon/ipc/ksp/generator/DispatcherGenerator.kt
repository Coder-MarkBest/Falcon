package com.falcon.ipc.ksp.generator

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Modifier

object DispatcherGenerator {

    fun generate(
        cg: CodeGenerator,
        logger: KSPLogger,
        iface: KSClassDeclaration,
        methods: List<KSFunctionDeclaration>
    ): Boolean {
        val pkg = iface.packageName.asString()
        val ifaceName = iface.simpleName.asString()
        val name = "${ifaceName}_Dispatcher"
        val seen = HashSet<Int>()
        val sb = StringBuilder()

        sb.appendLine("package $pkg")
        sb.appendLine()
        sb.appendLine("import android.os.Bundle")
        sb.appendLine("import com.falcon.ipc.runtime.IpcDispatcher")
        sb.appendLine()
        sb.appendLine("class $name(private val impl: $ifaceName) : IpcDispatcher {")
        sb.appendLine("    override fun dispatch(methodId: Int, args: Bundle): Bundle {")
        sb.appendLine("        val out = Bundle()")
        sb.appendLine("        when (methodId) {")

        for (m in methods) {
            val mName = m.simpleName.asString()
            val paramTypes = m.parameters.map { it.type.resolve() }
            val id = MethodIds.of(m)

            if (!seen.add(id)) {
                logger.error("methodId collision in $ifaceName for $mName")
                return false
            }

            val argExprs = paramTypes.mapIndexed { i, t ->
                TypeCodec.get(t, "args", i.toString())
                    ?: run {
                        logger.error(
                            "@IpcMethod param type ${t.declaration.qualifiedName?.asString() ?: "?"} unsupported in $ifaceName.$mName; " +
                                "make it Parcelable"
                        )
                        return false
                    }
            }

            val ret = m.returnType!!.resolve()
            val isSuspend = m.modifiers.contains(Modifier.SUSPEND)
            val call = "impl.$mName(${argExprs.joinToString(", ")})"
            val wrapped = if (isSuspend) "kotlinx.coroutines.runBlocking { $call }" else call

            sb.appendLine("            $id -> {")
            sb.appendLine("                val r = $wrapped")
            if (!TypeCodec.isUnit(ret)) {
                val putR = TypeCodec.put(ret, "out", "r", "r")
                    ?: run {
                        logger.error(
                            "@IpcMethod return type unsupported in $ifaceName.$mName; " +
                                "make it Parcelable"
                        )
                        return false
                    }
                sb.appendLine("                $putR")
            }
            sb.appendLine("            }")
        }

        sb.appendLine("            else -> throw IllegalArgumentException(\"Unknown methodId: \$methodId in $ifaceName\")")
        sb.appendLine("        }")
        sb.appendLine("        return out")
        sb.appendLine("    }")
        sb.appendLine("}")

        cg.createNewFile(
            Dependencies(false, iface.containingFile!!),
            pkg,
            name
        ).use { it.write(sb.toString().toByteArray()) }

        return true
    }
}
