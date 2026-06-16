package com.falcon.ipc.transport

import android.os.IBinder
import com.falcon.ipc.aidl.IIpcHost
import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.util.FalconLogger

class BinderTransport(
    private val host: IIpcHost
) : IpcTransport {

    override val maxPayloadSize: Int = 64 * 1024

    override fun invoke(envelope: IpcEnvelope): TransportResult {
        return try {
            val response = host.invoke(envelope)
            if (response.isError) {
                TransportResult.Error(response.errorCode, response.errorMessage)
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
        host.asBinder().unlinkToDeath(recipient)
    }
}
