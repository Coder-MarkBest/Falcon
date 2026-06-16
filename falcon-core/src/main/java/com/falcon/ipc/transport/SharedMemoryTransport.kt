package com.falcon.ipc.transport

import android.os.Build
import android.os.SharedMemory
import com.falcon.ipc.util.FalconLogger
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SharedMemoryTransport(
    private val maxAllocationSize: Int = 32 * 1024 * 1024
) {
    private val allocations = ConcurrentHashMap<String, SharedMemory>()
    private val hmacKey: SecretKeySpec by lazy {
        val key = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        SecretKeySpec(key, "HmacSHA256")
    }

    data class Allocation(
        val token: String,
        val memoryId: String,
        val size: Int
    )

    fun allocate(size: Int, callerPid: Int): Allocation? {
        if (size > maxAllocationSize) {
            FalconLogger.w("SharedMemory", "Allocation too large: $size > $maxAllocationSize")
            return null
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            FalconLogger.e("SharedMemory", "SharedMemory requires API 27+")
            return null
        }

        val memoryId = UUID.randomUUID().toString()
        val shm = SharedMemory.create("falcon_$memoryId", size)
        allocations[memoryId] = shm

        val token = generateToken(memoryId, callerPid)
        return Allocation(token, memoryId, size)
    }

    fun write(memoryId: String, data: ByteArray): Boolean {
        val shm = allocations[memoryId] ?: return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return false

        val buffer: ByteBuffer = shm.mapReadWrite()
        try {
            buffer.putInt(data.size)
            buffer.put(data)
            buffer.flip()
        } finally {
            safeUnmap(shm, buffer)
        }
        return true
    }

    fun read(memoryId: String): ByteArray? {
        val shm = allocations[memoryId] ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null

        val buffer: ByteBuffer = shm.mapReadOnly()
        try {
            val size = buffer.getInt()
            if (size < 0 || size > maxAllocationSize) return null
            val data = ByteArray(size)
            buffer.get(data)
            return data
        } finally {
            safeUnmap(shm, buffer)
        }
    }

    /**
     * Unmap a SharedMemory buffer. Uses reflection since unmap() is @SystemApi.
     */
    private fun safeUnmap(shm: SharedMemory, buffer: ByteBuffer) {
        try {
            val method = SharedMemory::class.java.getDeclaredMethod("unmap", ByteBuffer::class.java)
            method.invoke(shm, buffer)
        } catch (_: Exception) {
            // unmap not available; buffer will be reclaimed by GC
        }
    }

    fun release(memoryId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            allocations.remove(memoryId)?.close()
        }
    }

    fun verifyToken(token: String, callerPid: Int): String? {
        return try {
            val parts = token.split("|")
            if (parts.size != 3) return null

            val memoryId = parts[0]
            val pid = parts[1].toInt()
            val signature = parts[2]

            if (pid != callerPid) {
                FalconLogger.w("SharedMemory", "PID mismatch: expected=$pid actual=$callerPid")
                return null
            }

            val expectedSig = computeHmac("$memoryId|$pid")
            if (signature != expectedSig) {
                FalconLogger.w("SharedMemory", "HMAC verification failed")
                return null
            }

            memoryId
        } catch (e: Exception) {
            FalconLogger.e("SharedMemory", "Token verification failed", e)
            null
        }
    }

    private fun generateToken(memoryId: String, callerPid: Int): String {
        val payload = "$memoryId|$callerPid"
        val signature = computeHmac(payload)
        return "$payload|$signature"
    }

    private fun computeHmac(data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(hmacKey)
        return mac.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun releaseAll() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            allocations.values.forEach { it.close() }
        }
        allocations.clear()
    }
}
