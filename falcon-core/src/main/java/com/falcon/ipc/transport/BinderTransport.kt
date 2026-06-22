package com.falcon.ipc.transport

import android.os.DeadObjectException
import android.os.IBinder
import android.os.TransactionTooLargeException
import com.falcon.ipc.aidl.IIpcHost
import com.falcon.ipc.protocol.ErrorCode
import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.util.FalconLogger

class BinderTransport(
    private val host: IIpcHost,
    override val maxPayloadSize: Int = 256 * 1024,
    private val invokeTimeoutMs: Long = 5_000
) : IpcTransport {

    private companion object {
        // Daemon pool for watchdog-guarded invokes. An orphaned (timed-out) task
        // finishes when the binder finally returns; CallerRuns avoids unbounded growth.
        val watchdogPool = java.util.concurrent.ThreadPoolExecutor(
            2, 32, 30L, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.SynchronousQueue(),
            { r -> Thread(r, "falcon-invoke-watchdog").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()
        )
    }

    override fun invoke(envelope: IpcEnvelope): TransportResult {
        if (invokeTimeoutMs <= 0) return invokeBlocking(envelope)
        val future = watchdogPool.submit<TransportResult> { invokeBlocking(envelope) }
        return try {
            future.get(invokeTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            FalconLogger.e("BinderTransport", "invoke watchdog fired after ${invokeTimeoutMs}ms — peer unresponsive")
            // Do NOT cancel(true): interrupting won't unblock a native binder call; let it finish and be GC'd.
            TransportResult.Error(ErrorCode.TRANSPORT_ERROR, "Peer unresponsive (>${invokeTimeoutMs}ms)")
        } catch (e: java.util.concurrent.ExecutionException) {
            TransportResult.Error(ErrorCode.TRANSPORT_ERROR, e.cause?.message ?: "invoke failed")
        }
    }

    private fun invokeBlocking(envelope: IpcEnvelope): TransportResult {
        return try {
            val response = host.invoke(envelope)
            if (response.isError) {
                TransportResult.Error(response.errorCode, response.errorMessage)
            } else {
                TransportResult.Success(response.argsBundle)
            }
        } catch (e: DeadObjectException) {
            FalconLogger.w("BinderTransport", "Remote process died during invoke")
            TransportResult.Error(ErrorCode.PEER_NOT_CONNECTED, "Remote process died")
        } catch (e: TransactionTooLargeException) {
            FalconLogger.e("BinderTransport", "Payload exceeds Binder transaction limit", e)
            TransportResult.Error(ErrorCode.SERIALIZATION_ERROR, "Payload too large: ${e.message}")
        } catch (e: SecurityException) {
            FalconLogger.e("BinderTransport", "Binder-level security rejection", e)
            TransportResult.Error(ErrorCode.UNAUTHORIZED, e.message ?: "Security rejection")
        } catch (e: Exception) {
            FalconLogger.e("BinderTransport", "Invoke failed", e)
            TransportResult.Error(ErrorCode.TRANSPORT_ERROR, e.message ?: "Unknown transport error")
        }
    }

    override fun subscribe(eventKey: String, callback: com.falcon.ipc.aidl.IIpcEventCallback) {
        host.subscribe(eventKey, callback)
    }

    override fun unsubscribe(eventKey: String, callback: com.falcon.ipc.aidl.IIpcEventCallback) {
        try {
            host.unsubscribe(eventKey, callback)
        } catch (e: DeadObjectException) {
            FalconLogger.w("BinderTransport", "unsubscribe failed: remote dead for $eventKey")
        } catch (e: Exception) {
            FalconLogger.e("BinderTransport", "unsubscribe failed for $eventKey", e)
        }
    }

    override fun invokeCallback(envelope: IpcEnvelope, reply: com.falcon.ipc.aidl.IIpcEventCallback) {
        try {
            host.invokeCallback(envelope, reply)
        } catch (e: DeadObjectException) {
            FalconLogger.w("BinderTransport", "invokeCallback failed: remote dead")
        } catch (e: Exception) {
            FalconLogger.e("BinderTransport", "invokeCallback failed", e)
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
