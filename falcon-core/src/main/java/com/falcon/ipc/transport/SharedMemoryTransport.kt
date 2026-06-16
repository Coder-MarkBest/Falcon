package com.falcon.ipc.transport

import android.os.Build
import android.os.SharedMemory
import androidx.annotation.RequiresApi
import com.falcon.ipc.util.FalconLogger
import java.nio.ByteBuffer

/**
 * Stateless helper for zero-copy payloads. The SharedMemory object itself is
 * Parcelable and travels in the IpcEnvelope across Binder (kernel dups the FD),
 * so no local registry / token model is needed.
 */
@RequiresApi(Build.VERSION_CODES.O_MR1)
class SharedMemoryTransport(
    private val maxAllocationSize: Int = 32 * 1024 * 1024
) {
    fun writeToShared(data: ByteArray): SharedMemory? {
        if (data.size > maxAllocationSize) {
            FalconLogger.w("SharedMemory", "Payload too large: ${data.size} > $maxAllocationSize")
            return null
        }
        val shm = SharedMemory.create("falcon_shm", data.size.coerceAtLeast(1))
        val buffer: ByteBuffer = shm.mapReadWrite()
        try {
            buffer.put(data)
        } finally {
            SharedMemory.unmap(buffer)
        }
        shm.setProtect(android.system.OsConstants.PROT_READ)
        return shm
    }

    fun readFromShared(shm: SharedMemory): ByteArray {
        val buffer: ByteBuffer = shm.mapReadOnly()
        try {
            val data = ByteArray(shm.size)
            buffer.get(data)
            return data
        } finally {
            SharedMemory.unmap(buffer)
        }
    }
}
