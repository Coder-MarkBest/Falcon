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
                if (response.argsBundle != null) TransportResult.Success(response.argsBundle)
                else TransportResult.Success(response.args)
            }
        } catch (e: Exception) {
            FalconLogger.e("BinderTransport", "Invoke failed", e)
            TransportResult.Error(-1, e.message ?: "Unknown error")
        }
    }

    override fun subscribe(eventKey: String, callback: com.falcon.ipc.aidl.IIpcEventCallback) {
        host.subscribe(eventKey, callback)
    }

    override fun unsubscribe(eventKey: String, callback: com.falcon.ipc.aidl.IIpcEventCallback) {
        host.unsubscribe(eventKey, callback)
    }

    override fun invokeCallback(envelope: IpcEnvelope, reply: com.falcon.ipc.aidl.IIpcEventCallback) {
        host.invokeCallback(envelope, reply)
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
