package com.falcon.ipc.ksp.generator

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType

object TypeCodec {
    private val primitives = mapOf(
        "kotlin.Int" to "Int",
        "kotlin.Long" to "Long",
        "kotlin.Float" to "Float",
        "kotlin.Double" to "Double",
        "kotlin.Boolean" to "Boolean",
        "kotlin.String" to "String",
        "kotlin.ByteArray" to "ByteArray"
    )

    fun isUnit(t: KSType) = t.declaration.qualifiedName?.asString() == "kotlin.Unit"

    fun put(t: KSType, bundleVar: String, key: String, valueExpr: String): String? {
        val q = t.declaration.qualifiedName?.asString() ?: return null
        primitives[q]?.let { suffix ->
            return "com.falcon.ipc.protocol.BundleCodec.put$suffix($bundleVar, \"$key\", $valueExpr)"
        }
        val decl = t.declaration
        if (decl is KSClassDeclaration) {
            if (decl.classKind == ClassKind.ENUM_CLASS) {
                return "com.falcon.ipc.protocol.BundleCodec.putEnum($bundleVar, \"$key\", $valueExpr)"
            }
            if (decl.superTypes.any {
                    it.resolve().declaration.qualifiedName?.asString() == "android.os.Parcelable"
                }
            ) {
                return "com.falcon.ipc.protocol.BundleCodec.putParcelable($bundleVar, \"$key\", $valueExpr)"
            }
        }
        return null
    }

    fun get(t: KSType, bundleVar: String, key: String): String? {
        val q = t.declaration.qualifiedName?.asString() ?: return null
        // Non-null assertion needed when: (a) the method param/return is non-null AND
        // (b) BundleCodec.getXxx returns a nullable type.
        // Primitives (Int/Long/Float/Double/Boolean) are always non-null from Bundle — no !! needed.
        // String, ByteArray, Parcelable, Enum getters return nullable — add !! for non-null types.
        val nonNull = !t.isMarkedNullable
        val nn = if (nonNull) "!!" else ""
        val nonNullablePrimitives = setOf("kotlin.Int", "kotlin.Long", "kotlin.Float", "kotlin.Double", "kotlin.Boolean")
        if (q in nonNullablePrimitives) {
            val suffix = primitives[q]!!
            return "com.falcon.ipc.protocol.BundleCodec.get$suffix($bundleVar, \"$key\")"
        }
        primitives[q]?.let { suffix ->
            // String and ByteArray: codec returns nullable, add !! for non-null types
            return "com.falcon.ipc.protocol.BundleCodec.get$suffix($bundleVar, \"$key\")$nn"
        }
        val decl = t.declaration
        if (decl is KSClassDeclaration) {
            if (decl.classKind == ClassKind.ENUM_CLASS) {
                return "com.falcon.ipc.protocol.BundleCodec.getEnum($bundleVar, \"$key\", $q::class.java)$nn"
            }
            if (decl.superTypes.any {
                    it.resolve().declaration.qualifiedName?.asString() == "android.os.Parcelable"
                }
            ) {
                return "com.falcon.ipc.protocol.BundleCodec.getParcelable($bundleVar, \"$key\", $q::class.java)$nn"
            }
        }
        return null
    }
}
