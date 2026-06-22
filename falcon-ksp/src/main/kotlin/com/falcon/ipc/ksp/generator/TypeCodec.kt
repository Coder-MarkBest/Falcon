package com.falcon.ipc.ksp.generator

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType

object TypeCodec {
    private const val CODEC = "com.falcon.ipc.protocol.BundleCodec"
    private const val LIST = "kotlin.collections.List"
    private const val MAP = "kotlin.collections.Map"

    private val primitives = mapOf(
        "kotlin.Int" to "Int",
        "kotlin.Long" to "Long",
        "kotlin.Float" to "Float",
        "kotlin.Double" to "Double",
        "kotlin.Boolean" to "Boolean",
        "kotlin.String" to "String",
        "kotlin.ByteArray" to "ByteArray"
    )
    private val nonNullablePrimitives =
        setOf("kotlin.Int", "kotlin.Long", "kotlin.Float", "kotlin.Double", "kotlin.Boolean")

    fun isUnit(t: KSType) = t.declaration.qualifiedName?.asString() == "kotlin.Unit"

    private fun qn(t: KSType) = t.declaration.qualifiedName?.asString()

    private fun isParcelable(decl: KSClassDeclaration): Boolean =
        decl.getAllSuperTypes().any { it.declaration.qualifiedName?.asString() == "android.os.Parcelable" }

    // ── Public entry points (key is a literal index like "0" or "r") ──────────

    fun put(t: KSType, bundleVar: String, key: String, valueExpr: String): String? =
        putRaw(t, bundleVar, "\"$key\"", valueExpr, 0)

    fun get(t: KSType, bundleVar: String, key: String): String? =
        getWithNull(t, bundleVar, "\"$key\"", 0)

    /**
     * Render a [KSType] as Kotlin source, preserving generic arguments and nullability.
     * e.g. List<DemoUser>, Map<String, Int>?, ByteArray.
     */
    fun render(t: KSType): String {
        val q = qn(t) ?: t.declaration.simpleName.asString()
        val base = when (q) {
            "kotlin.Int" -> "Int"
            "kotlin.Long" -> "Long"
            "kotlin.Float" -> "Float"
            "kotlin.Double" -> "Double"
            "kotlin.Boolean" -> "Boolean"
            "kotlin.String" -> "String"
            "kotlin.ByteArray" -> "ByteArray"
            "kotlin.Unit" -> "Unit"
            "kotlin.Any" -> "Any"
            else -> q
        }
        val args = t.arguments.mapNotNull { it.type?.resolve() }
        val rendered = if (args.isEmpty()) base else "$base<${args.joinToString(", ") { render(it) }}>"
        return rendered + if (t.isMarkedNullable) "?" else ""
    }

    // ── put ───────────────────────────────────────────────────────────────────

    private fun putRaw(t: KSType, bundleVar: String, keyExpr: String, valueExpr: String, depth: Int): String? {
        val q = qn(t) ?: return null
        primitives[q]?.let { suffix ->
            return "$CODEC.put$suffix($bundleVar, $keyExpr, $valueExpr)"
        }
        if (q == LIST) {
            val elem = t.arguments.firstOrNull()?.type?.resolve() ?: return null
            val b = "b$depth"; val k = "k$depth"; val e = "e$depth"
            val ep = putRaw(elem, b, k, e, depth + 1) ?: return null
            return "$CODEC.putList($bundleVar, $keyExpr, $valueExpr) { $b, $k, $e -> $ep }"
        }
        if (q == MAP) {
            val kt = t.arguments.getOrNull(0)?.type?.resolve() ?: return null
            val vt = t.arguments.getOrNull(1)?.type?.resolve() ?: return null
            val bk = "b$depth"; val kk = "k$depth"; val ek = "e$depth"
            val bv = "c$depth"; val kv = "l$depth"; val ev = "m$depth"
            val pk = putRaw(kt, bk, kk, ek, depth + 1) ?: return null
            val pv = putRaw(vt, bv, kv, ev, depth + 1) ?: return null
            return "$CODEC.putMap($bundleVar, $keyExpr, $valueExpr, { $bk, $kk, $ek -> $pk }, { $bv, $kv, $ev -> $pv })"
        }
        val decl = t.declaration
        if (decl is KSClassDeclaration) {
            if (decl.classKind == ClassKind.ENUM_CLASS)
                return "$CODEC.putEnum($bundleVar, $keyExpr, $valueExpr)"
            if (isParcelable(decl))
                return "$CODEC.putParcelable($bundleVar, $keyExpr, $valueExpr)"
        }
        return null
    }

    // ── get ───────────────────────────────────────────────────────────────────

    /** Non-null `!!` is needed unless the type is nullable or a Bundle-non-null primitive. */
    private fun needsBang(t: KSType): Boolean {
        if (t.isMarkedNullable) return false
        return qn(t) !in nonNullablePrimitives
    }

    private fun getWithNull(t: KSType, bundleVar: String, keyExpr: String, depth: Int): String? {
        val raw = getRaw(t, bundleVar, keyExpr, depth) ?: return null
        return raw + if (needsBang(t)) "!!" else ""
    }

    private fun getRaw(t: KSType, bundleVar: String, keyExpr: String, depth: Int): String? {
        val q = qn(t) ?: return null
        if (q in nonNullablePrimitives) {
            return "$CODEC.get${primitives[q]}($bundleVar, $keyExpr)"
        }
        primitives[q]?.let { suffix ->  // String, ByteArray (codec returns nullable)
            return "$CODEC.get$suffix($bundleVar, $keyExpr)"
        }
        if (q == LIST) {
            val elem = t.arguments.firstOrNull()?.type?.resolve() ?: return null
            val b = "b$depth"; val k = "k$depth"
            val eg = getWithNull(elem, b, k, depth + 1) ?: return null
            return "$CODEC.getList($bundleVar, $keyExpr) { $b, $k -> $eg }"
        }
        if (q == MAP) {
            val kt = t.arguments.getOrNull(0)?.type?.resolve() ?: return null
            val vt = t.arguments.getOrNull(1)?.type?.resolve() ?: return null
            val bk = "b$depth"; val kk = "k$depth"
            val bv = "c$depth"; val kv = "l$depth"
            val gk = getWithNull(kt, bk, kk, depth + 1) ?: return null
            val gv = getWithNull(vt, bv, kv, depth + 1) ?: return null
            return "$CODEC.getMap($bundleVar, $keyExpr, { $bk, $kk -> $gk }, { $bv, $kv -> $gv })"
        }
        val decl = t.declaration
        if (decl is KSClassDeclaration) {
            if (decl.classKind == ClassKind.ENUM_CLASS)
                return "$CODEC.getEnum($bundleVar, $keyExpr, $q::class.java)"
            if (isParcelable(decl))
                return "$CODEC.getParcelable($bundleVar, $keyExpr, $q::class.java)"
        }
        return null
    }
}
