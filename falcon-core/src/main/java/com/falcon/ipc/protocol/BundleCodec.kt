package com.falcon.ipc.protocol

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
    @Suppress("DEPRECATION")
    fun <T : Parcelable> getParcelable(b: Bundle, k: String, cls: Class<T>): T? =
        b.getParcelable(k)
    fun putEnum(b: Bundle, k: String, v: Enum<*>?) = b.putString(k, v?.name)
    fun <T : Enum<T>> getEnum(b: Bundle, k: String, cls: Class<T>): T? =
        b.getString(k)?.let { java.lang.Enum.valueOf(cls, it) }
}
