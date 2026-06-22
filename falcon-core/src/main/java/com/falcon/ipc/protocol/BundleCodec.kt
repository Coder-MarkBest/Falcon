package com.falcon.ipc.protocol

import android.os.Build
import android.os.Bundle
import android.os.Parcelable

/**
 * Typed Bundle put/get used by KSP-generated proxies/dispatchers.
 * Keys are parameter indices as strings ("0".."n"); the return value uses key "r".
 */
object BundleCodec {
    fun putInt(b: Bundle, k: String, v: Int) = b.putInt(k, v)
    fun getInt(b: Bundle, k: String): Int = b.getInt(k)
    fun putLong(b: Bundle, k: String, v: Long) = b.putLong(k, v)
    fun getLong(b: Bundle, k: String): Long = b.getLong(k)
    fun putFloat(b: Bundle, k: String, v: Float) = b.putFloat(k, v)
    fun getFloat(b: Bundle, k: String): Float = b.getFloat(k)
    fun putDouble(b: Bundle, k: String, v: Double) = b.putDouble(k, v)
    fun getDouble(b: Bundle, k: String): Double = b.getDouble(k)
    fun putBoolean(b: Bundle, k: String, v: Boolean) = b.putBoolean(k, v)
    fun getBoolean(b: Bundle, k: String): Boolean = b.getBoolean(k)
    fun putString(b: Bundle, k: String, v: String?) = b.putString(k, v)
    fun getString(b: Bundle, k: String): String? = b.getString(k)
    fun putByteArray(b: Bundle, k: String, v: ByteArray?) = b.putByteArray(k, v)
    fun getByteArray(b: Bundle, k: String): ByteArray? = b.getByteArray(k)
    fun <T : Parcelable> putParcelable(b: Bundle, k: String, v: T?) = b.putParcelable(k, v)
    fun <T : Parcelable> getParcelable(b: Bundle, k: String, cls: Class<T>): T? {
        // The receiving Bundle defaults to the framework classloader, which cannot resolve
        // app-defined Parcelables across a Binder boundary — set the value type's loader first.
        b.classLoader = cls.classLoader
        return if (Build.VERSION.SDK_INT >= 33) {
            b.getParcelable(k, cls)
        } else {
            @Suppress("DEPRECATION")
            b.getParcelable(k)
        }
    }
    fun putEnum(b: Bundle, k: String, v: Enum<*>?) = b.putString(k, v?.name)
    fun <T : Enum<T>> getEnum(b: Bundle, k: String, cls: Class<T>): T? =
        b.getString(k)?.let { java.lang.Enum.valueOf(cls, it) }

    // --- Collections ---
    // Encoded as: size at "$k#n" (-1 = null), elements in a nested Bundle at "$k#v"
    // under string-index keys. The element codec is supplied by generated code, so any
    // supported element type (including nested List/Map) works via recursion.
    //
    // Invariant: the caller-supplied [k] must not contain '#' — these helpers derive
    // sibling keys "$k#n"/"$k#v"/"$k#k" from it. KSP always passes numeric indices
    // ("0".."n") or "r", which satisfy this.

    fun <T> putList(b: Bundle, k: String, v: List<T>?, putElem: (Bundle, String, T) -> Unit) {
        if (v == null) { b.putInt("$k#n", -1); return }
        b.putInt("$k#n", v.size)
        val inner = Bundle()
        v.forEachIndexed { i, e -> putElem(inner, i.toString(), e) }
        b.putBundle("$k#v", inner)
    }

    fun <T> getList(b: Bundle, k: String, getElem: (Bundle, String) -> T): List<T>? {
        val n = b.getInt("$k#n", -1)
        if (n < 0) return null
        val inner = b.getBundle("$k#v") ?: return emptyList()
        inner.classLoader = BundleCodec::class.java.classLoader
        return (0 until n).map { getElem(inner, it.toString()) }
    }

    fun <K, V> putMap(
        b: Bundle, k: String, v: Map<K, V>?,
        putKey: (Bundle, String, K) -> Unit, putVal: (Bundle, String, V) -> Unit
    ) {
        if (v == null) { b.putInt("$k#n", -1); return }
        b.putInt("$k#n", v.size)
        val kb = Bundle(); val vb = Bundle()
        var i = 0
        for ((key, value) in v) { putKey(kb, i.toString(), key); putVal(vb, i.toString(), value); i++ }
        b.putBundle("$k#k", kb); b.putBundle("$k#v", vb)
    }

    fun <K, V> getMap(
        b: Bundle, k: String,
        getKey: (Bundle, String) -> K, getVal: (Bundle, String) -> V
    ): Map<K, V>? {
        val n = b.getInt("$k#n", -1)
        if (n < 0) return null
        val kb = b.getBundle("$k#k") ?: return emptyMap()
        val vb = b.getBundle("$k#v") ?: return emptyMap()
        kb.classLoader = BundleCodec::class.java.classLoader
        vb.classLoader = BundleCodec::class.java.classLoader
        val out = LinkedHashMap<K, V>(n)
        for (i in 0 until n) out[getKey(kb, i.toString())] = getVal(vb, i.toString())
        return out
    }
}
