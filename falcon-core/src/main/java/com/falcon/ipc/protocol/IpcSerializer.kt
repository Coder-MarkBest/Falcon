package com.falcon.ipc.protocol

import android.os.Parcel
import android.os.Parcelable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable

object IpcSerializer {

    // Type tags for binary protocol
    const val TYPE_NULL: Byte = 0
    const val TYPE_INT: Byte = 1
    const val TYPE_LONG: Byte = 2
    const val TYPE_FLOAT: Byte = 3
    const val TYPE_DOUBLE: Byte = 4
    const val TYPE_BOOLEAN: Byte = 5
    const val TYPE_STRING: Byte = 6
    const val TYPE_BYTE_ARRAY: Byte = 7
    const val TYPE_PARCELABLE: Byte = 8
    const val TYPE_LIST: Byte = 9
    const val TYPE_MAP: Byte = 10

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun serializeArgs(args: Array<Any?>): ByteArray {
        val parcel = Parcel.obtain()
        try {
            parcel.writeInt(args.size)
            for (arg in args) {
                serializeArg(parcel, arg)
            }
            val bytes = parcel.marshall()
            return bytes
        } finally {
            parcel.recycle()
        }
    }

    fun deserializeArgs(data: ByteArray, types: Array<Class<*>>): Array<Any?> {
        if (data.isEmpty()) return emptyArray()
        val parcel = Parcel.obtain()
        try {
            parcel.unmarshall(data, 0, data.size)
            parcel.setDataPosition(0)
            val count = parcel.readInt()
            return Array(count) { i ->
                val expectedType = if (i < types.size) types[i] else Any::class.java
                deserializeArg(parcel, expectedType)
            }
        } finally {
            parcel.recycle()
        }
    }

    fun serializeResult(result: Any?): ByteArray {
        val parcel = Parcel.obtain()
        try {
            serializeArg(parcel, result)
            return parcel.marshall()
        } finally {
            parcel.recycle()
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> deserializeResult(data: ByteArray, type: Class<T>): T? {
        if (data.isEmpty()) return null
        val parcel = Parcel.obtain()
        try {
            parcel.unmarshall(data, 0, data.size)
            parcel.setDataPosition(0)
            return deserializeArg(parcel, type) as T?
        } finally {
            parcel.recycle()
        }
    }

    private fun serializeArg(parcel: Parcel, arg: Any?) {
        when (arg) {
            null -> {
                parcel.writeByte(TYPE_NULL)
            }
            is Int -> {
                parcel.writeByte(TYPE_INT)
                parcel.writeInt(arg)
            }
            is Long -> {
                parcel.writeByte(TYPE_LONG)
                parcel.writeLong(arg)
            }
            is Float -> {
                parcel.writeByte(TYPE_FLOAT)
                parcel.writeFloat(arg)
            }
            is Double -> {
                parcel.writeByte(TYPE_DOUBLE)
                parcel.writeDouble(arg)
            }
            is Boolean -> {
                parcel.writeByte(TYPE_BOOLEAN)
                parcel.writeByte(if (arg) 1 else 0)
            }
            is String -> {
                parcel.writeByte(TYPE_STRING)
                parcel.writeString(arg)
            }
            is ByteArray -> {
                parcel.writeByte(TYPE_BYTE_ARRAY)
                parcel.writeInt(arg.size)
                parcel.writeByteArray(arg)
            }
            is Parcelable -> {
                parcel.writeByte(TYPE_PARCELABLE)
                parcel.writeString(arg.javaClass.name)
                parcel.writeParcelable(arg, 0)
            }
            is List<*> -> {
                parcel.writeByte(TYPE_LIST)
                parcel.writeInt(arg.size)
                arg.forEach { item -> serializeArg(parcel, item) }
            }
            is Map<*, *> -> {
                parcel.writeByte(TYPE_MAP)
                parcel.writeInt(arg.size)
                arg.forEach { (k, v) ->
                    serializeArg(parcel, k)
                    serializeArg(parcel, v)
                }
            }
            else -> {
                // Fallback: serialize as JSON string
                parcel.writeByte(TYPE_STRING)
                parcel.writeString(json.encodeToString(arg.toString()))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun deserializeArg(parcel: Parcel, type: Class<*>): Any? {
        val typeTag = parcel.readByte()
        return when (typeTag) {
            TYPE_NULL -> null
            TYPE_INT -> parcel.readInt()
            TYPE_LONG -> parcel.readLong()
            TYPE_FLOAT -> parcel.readFloat()
            TYPE_DOUBLE -> parcel.readDouble()
            TYPE_BOOLEAN -> parcel.readByte() != 0.toByte()
            TYPE_STRING -> parcel.readString()
            TYPE_BYTE_ARRAY -> {
                val size = parcel.readInt()
                require(size in 0..32 * 1024 * 1024) { "ByteArray size out of range: $size" }
                ByteArray(size).also { parcel.readByteArray(it) }
            }
            TYPE_PARCELABLE -> {
                parcel.readString() // className
                val classLoader = type.classLoader ?: Thread.currentThread().contextClassLoader
                parcel.readParcelable(classLoader)
            }
            TYPE_LIST -> {
                val size = parcel.readInt()
                require(size in 0..10_000) { "List size out of range: $size" }
                List(size) { deserializeArg(parcel, Any::class.java) }
            }
            TYPE_MAP -> {
                val size = parcel.readInt()
                require(size in 0..10_000) { "Map size out of range: $size" }
                (1..size).associate {
                    deserializeArg(parcel, Any::class.java) to deserializeArg(parcel, Any::class.java)
                }
            }
            else -> throw IllegalStateException("Unknown type tag: $typeTag")
        }
    }
}
