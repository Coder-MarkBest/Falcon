package com.falcon.ipc.protocol

import android.os.Parcel
import android.os.Parcelable
import java.util.UUID

data class IpcEnvelope(
    val serviceKey: String = "",
    val method: String = "",
    val args: ByteArray? = null,
    val requestId: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val traceId: String? = null,
    val isError: Boolean = false,
    val errorCode: Int = ErrorCode.SUCCESS,
    val errorMessage: String = "",
    val largePayload: Boolean = false,
    val sharedMemory: android.os.SharedMemory? = null
) : Parcelable {

    constructor(parcel: Parcel) : this(
        serviceKey = parcel.readString() ?: "",
        method = parcel.readString() ?: "",
        args = parcel.createByteArray(),
        requestId = parcel.readString() ?: "",
        timestamp = parcel.readLong(),
        traceId = parcel.readString(),
        isError = parcel.readByte() != 0.toByte(),
        errorCode = parcel.readInt(),
        errorMessage = parcel.readString() ?: "",
        largePayload = parcel.readByte() != 0.toByte(),
        sharedMemory = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            if (android.os.Build.VERSION.SDK_INT >= 33)
                parcel.readParcelable(android.os.SharedMemory::class.java.classLoader, android.os.SharedMemory::class.java)
            else
                @Suppress("DEPRECATION")
                parcel.readParcelable<android.os.SharedMemory>(android.os.SharedMemory::class.java.classLoader)
        } else null
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(serviceKey)
        parcel.writeString(method)
        parcel.writeByteArray(args)
        parcel.writeString(requestId)
        parcel.writeLong(timestamp)
        parcel.writeString(traceId)
        parcel.writeByte(if (isError) 1 else 0)
        parcel.writeInt(errorCode)
        parcel.writeString(errorMessage)
        parcel.writeByte(if (largePayload) 1 else 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1)
            parcel.writeParcelable(sharedMemory, flags)
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

        fun response(requestId: String, data: ByteArray?): IpcEnvelope {
            return IpcEnvelope(
                requestId = requestId,
                args = data,
                isError = false
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IpcEnvelope) return false
        return serviceKey == other.serviceKey &&
            method == other.method &&
            args.contentEquals(other.args) &&
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
        result = 31 * result + (args?.contentHashCode() ?: 0)
        result = 31 * result + requestId.hashCode()
        return result
    }
}
