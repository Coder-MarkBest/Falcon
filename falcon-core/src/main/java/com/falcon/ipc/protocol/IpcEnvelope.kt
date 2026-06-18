package com.falcon.ipc.protocol

import android.os.Parcel
import android.os.Parcelable
import java.util.UUID

data class IpcEnvelope(
    val serviceKey: String = "",
    val method: String = "",
    val requestId: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val traceId: String? = null,
    val isError: Boolean = false,
    val errorCode: Int = ErrorCode.SUCCESS,
    val errorMessage: String = "",
    val methodId: Int = 0,
    val argsBundle: android.os.Bundle? = null
) : Parcelable {

    constructor(parcel: Parcel) : this(
        serviceKey = parcel.readString() ?: "",
        method = parcel.readString() ?: "",
        requestId = parcel.readString() ?: "",
        timestamp = parcel.readLong(),
        traceId = parcel.readString(),
        isError = parcel.readByte() != 0.toByte(),
        errorCode = parcel.readInt(),
        errorMessage = parcel.readString() ?: "",
        methodId = parcel.readInt(),
        argsBundle = parcel.readBundle(IpcEnvelope::class.java.classLoader)
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(serviceKey)
        parcel.writeString(method)
        parcel.writeString(requestId)
        parcel.writeLong(timestamp)
        parcel.writeString(traceId)
        parcel.writeByte(if (isError) 1 else 0)
        parcel.writeInt(errorCode)
        parcel.writeString(errorMessage)
        parcel.writeInt(methodId)
        parcel.writeBundle(argsBundle)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<IpcEnvelope> {
        override fun createFromParcel(parcel: Parcel): IpcEnvelope = IpcEnvelope(parcel)
        override fun newArray(size: Int): Array<IpcEnvelope?> = arrayOfNulls(size)

        fun error(code: Int, message: String, requestId: String = ""): IpcEnvelope {
            return IpcEnvelope(
                requestId = requestId,
                isError = true,
                errorCode = code,
                errorMessage = message
            )
        }

        fun response(requestId: String, bundle: android.os.Bundle?): IpcEnvelope {
            return IpcEnvelope(
                requestId = requestId,
                argsBundle = bundle,
                isError = false
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IpcEnvelope) return false
        return serviceKey == other.serviceKey &&
            method == other.method &&
            requestId == other.requestId &&
            timestamp == other.timestamp &&
            traceId == other.traceId &&
            isError == other.isError &&
            errorCode == other.errorCode &&
            errorMessage == other.errorMessage
    }

    override fun hashCode(): Int {
        var result = serviceKey.hashCode()
        result = 31 * result + method.hashCode()
        result = 31 * result + requestId.hashCode()
        return result
    }
}
