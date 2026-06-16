package com.falcon.ipc.transport

import android.os.Build
import android.os.IBinder
import com.falcon.ipc.aidl.IIpcHost
import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.util.FalconLogger

class BinderTransport(
    private val host: IIpcHost,
    private val sharedMemoryTransport: SharedMemoryTransport? = null
) : IpcTransport {

    override val maxPayloadSize: Int = 64 * 1024

    override fun invoke(envelope: IpcEnvelope): TransportResult {
        return try {
            val response = host.invoke(envelope)
            if (response.isError) {
                TransportResult.Error(response.errorCode, response.errorMessage)
            } else if (response.largePayload && response.sharedMemory != null
                && sharedMemoryTransport != null
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                val shm = response.sharedMemory
                val bytes = try { sharedMemoryTransport.readFromShared(shm) } finally { shm.close() }
                TransportResult.Success(bytes)
            } else {
                TransportResult.Success(response.args)
            }
        } catch (e: Exception) {
            FalconLogger.e("BinderTransport", "Invoke failed", e)
            TransportResult.Error(-1, e.message ?: "Unknown error")
        }
    }

    fun isAlive(): Boolean {
        return try {
            host.asBinder().pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    fun linkToDeath(recipient: IBinder.DeathRecipient) {
        host.asBinder().linkToDeath(recipient, 0)
    }

    fun unlinkToDeath(recipient: IBinder.DeathRecipient) {
        host.asBinder().unlinkToDeath(recipient, 0)
    }
}
