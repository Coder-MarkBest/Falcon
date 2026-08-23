package com.falcon.ipc.dynamic

import android.os.Bundle

/** Language-neutral payload backed only by Android platform wire types. */
class DynamicPayload private constructor(internal val bundle: Bundle) {
    constructor() : this(Bundle())

    fun putInt(key: String, value: Int) = apply { checkKey(key); bundle.putInt(key, value) }
    fun putLong(key: String, value: Long) = apply { checkKey(key); bundle.putLong(key, value) }
    fun putFloat(key: String, value: Float) = apply { checkKey(key); bundle.putFloat(key, value) }
    fun putDouble(key: String, value: Double) = apply { checkKey(key); bundle.putDouble(key, value) }
    fun putBoolean(key: String, value: Boolean) = apply { checkKey(key); bundle.putBoolean(key, value) }
    fun putString(key: String, value: String?) = apply {
        checkKey(key); require(value == null || value.length <= MAX_STRING_LENGTH); bundle.putString(key, value)
    }
    fun putByteArray(key: String, value: ByteArray?) = apply {
        checkKey(key); require(value == null || value.size <= MAX_BYTE_ARRAY_SIZE)
        bundle.putByteArray(key, value?.copyOf())
    }
    fun putStringList(key: String, value: List<String>?) = apply {
        checkKey(key)
        require(value == null || value.size <= MAX_COLLECTION_SIZE)
        require(value == null || value.all { it.length <= MAX_STRING_LENGTH })
        bundle.putStringArrayList(key, value?.let(::ArrayList))
    }
    fun putPayload(key: String, value: DynamicPayload?) = apply {
        checkKey(key)
        bundle.putBundle(key, value?.toBundle())
    }

    fun contains(key: String): Boolean = bundle.containsKey(key)
    fun getInt(key: String, default: Int = 0): Int = bundle.getInt(key, default)
    fun getLong(key: String, default: Long = 0): Long = bundle.getLong(key, default)
    fun getFloat(key: String, default: Float = 0f): Float = bundle.getFloat(key, default)
    fun getDouble(key: String, default: Double = 0.0): Double = bundle.getDouble(key, default)
    fun getBoolean(key: String, default: Boolean = false): Boolean = bundle.getBoolean(key, default)
    fun getString(key: String): String? = bundle.getString(key)
    fun getByteArray(key: String): ByteArray? = bundle.getByteArray(key)?.copyOf()
    fun getStringList(key: String): List<String>? = bundle.getStringArrayList(key)?.toList()
    fun getPayload(key: String): DynamicPayload? = bundle.getBundle(key)?.let(::fromBundle)
    fun keys(): Set<String> = bundle.keySet().toSet()
    fun toBundle(): Bundle = Bundle(bundle)

    companion object {
        private const val MAX_KEYS = 256
        private const val MAX_KEY_LENGTH = 128
        private const val MAX_STRING_LENGTH = 64 * 1024
        private const val MAX_BYTE_ARRAY_SIZE = 256 * 1024
        private const val MAX_COLLECTION_SIZE = 10_000

        fun fromBundle(bundle: Bundle?): DynamicPayload {
            if (bundle == null) return DynamicPayload()
            require(bundle.size() <= MAX_KEYS) { "Dynamic payload has too many fields" }
            val safe = DynamicPayload()
            for (key in bundle.keySet()) {
                safe.checkKey(key)
                when (val value = bundle.get(key)) {
                    null -> safe.bundle.putString(key, null)
                    is Int -> safe.putInt(key, value)
                    is Long -> safe.putLong(key, value)
                    is Float -> safe.putFloat(key, value)
                    is Double -> safe.putDouble(key, value)
                    is Boolean -> safe.putBoolean(key, value)
                    is String -> safe.putString(key, value)
                    is ByteArray -> safe.putByteArray(key, value)
                    is Bundle -> safe.putPayload(key, fromBundle(value))
                    is ArrayList<*> -> {
                        require(value.all { it is String }) { "Only string lists are supported for '$key'" }
                        @Suppress("UNCHECKED_CAST")
                        safe.putStringList(key, value as List<String>)
                    }
                    else -> throw IllegalArgumentException(
                        "Unsupported dynamic payload type for '$key': ${value.javaClass.name}"
                    )
                }
            }
            return safe
        }
        fun build(block: DynamicPayload.() -> Unit): DynamicPayload = DynamicPayload().apply(block)
        val EMPTY: DynamicPayload get() = DynamicPayload()
    }

    private fun checkKey(key: String) {
        require(key.isNotBlank() && key.length <= MAX_KEY_LENGTH) { "Invalid dynamic payload key" }
        require(bundle.size() < MAX_KEYS || bundle.containsKey(key)) { "Dynamic payload has too many fields" }
    }
}

data class DynamicRequest(
    val route: String,
    val version: Int,
    val requestId: String,
    val headers: DynamicPayload,
    val body: DynamicPayload,
    val callerPackage: String = "",
    val callerProcess: String = ""
)

data class DynamicResponse(
    val statusCode: Int = 200,
    val headers: DynamicPayload = DynamicPayload.EMPTY,
    val body: DynamicPayload = DynamicPayload.EMPTY
) {
    val isSuccessful: Boolean get() = statusCode in 200..299

    companion object {
        fun success(body: DynamicPayload = DynamicPayload.EMPTY, statusCode: Int = 200) =
            DynamicResponse(statusCode = statusCode, body = body)
        fun error(statusCode: Int, message: String) = DynamicResponse(
            statusCode = statusCode,
            body = DynamicPayload.build { putString("message", message) }
        )
    }
}

data class DynamicCapability(
    val name: String,
    val kind: Kind,
    val versions: Set<Int>
) {
    enum class Kind { ROUTE, TOPIC }
}

internal object DynamicProtocol {
    const val SERVICE_KEY = "__falcon_dynamic__"
    const val CALL = "__dynamic_call__"
    const val PROBE = "__dynamic_probe__"
    const val CAPABILITIES = "__dynamic_capabilities__"
    const val TOPIC_PREFIX = "__dynamic_topic__#"

    fun encodeResponse(response: DynamicResponse) = Bundle().apply {
        putInt("status", response.statusCode)
        putBundle("headers", response.headers.toBundle())
        putBundle("body", response.body.toBundle())
    }

    fun decodeResponse(bundle: Bundle) = DynamicResponse(
        statusCode = bundle.getInt("status", 500),
        headers = DynamicPayload.fromBundle(bundle.getBundle("headers")),
        body = DynamicPayload.fromBundle(bundle.getBundle("body"))
    )
}
