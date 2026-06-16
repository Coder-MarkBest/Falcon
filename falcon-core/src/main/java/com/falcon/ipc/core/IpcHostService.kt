package com.falcon.ipc.core

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.falcon.ipc.Falcon
import com.falcon.ipc.aidl.IIpcEventCallback
import com.falcon.ipc.aidl.IIpcHost
import com.falcon.ipc.protocol.ErrorCode
import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.protocol.IpcSerializer
import com.falcon.ipc.security.SignatureGuard
import com.falcon.ipc.transport.SharedMemoryTransport
import com.falcon.ipc.transport.TransportSelector
import com.falcon.ipc.util.CallerResolver
import com.falcon.ipc.util.FalconLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class IpcHostService : Service() {

    private lateinit var signatureGuard: SignatureGuard
    private lateinit var callerResolver: CallerResolver
    private lateinit var serviceRegistry: ServiceRegistry
    private lateinit var messageRouter: MessageRouter
    private lateinit var sharedMemoryTransport: SharedMemoryTransport
    private var threshold: Int = 64 * 1024
    private val eventSubscribers = ConcurrentHashMap<String, CopyOnWriteArrayList<IIpcEventCallback>>()

    override fun onCreate() {
        super.onCreate()
        val falconManager = try {
            Falcon.getInstance()
        } catch (e: IllegalStateException) {
            FalconLogger.e("Host", "Falcon not initialized", e)
            stopSelf()
            return
        }
        serviceRegistry = falconManager.serviceRegistry
        signatureGuard = falconManager.signatureGuard
        callerResolver = falconManager.callerResolver
        messageRouter = falconManager.messageRouter
        sharedMemoryTransport = falconManager.sharedMemoryTransport
        threshold = falconManager.sharedMemoryThreshold
    }

    override fun onBind(intent: Intent?): IBinder? {
        val callingUid = Binder.getCallingUid()
        if (!signatureGuard.verify(this, callingUid)) {
            FalconLogger.e("Security", "Rejected bind from UID: $callingUid")
            return null
        }
        return hostBinder
    }

    private val hostBinder = object : IIpcHost.Stub() {

        override fun invoke(request: IpcEnvelope): IpcEnvelope {
            val callingUid = Binder.getCallingUid()
            val callingPid = Binder.getCallingPid()
            if (!signatureGuard.verify(this@IpcHostService, callingUid)) {
                return IpcEnvelope.error(ErrorCode.UNAUTHORIZED, "Signature mismatch")
            }
            val callerProcess = callerResolver.resolve(callingPid)
            return try {
                val result = messageRouter.handleLocal(request, callerProcess, callingPid)
                val resultBytes = IpcSerializer.serializeResult(result)
                // NOTE: the sender's SharedMemory copy for the RESPONSE cannot be closed here —
                // the AIDL stub marshals the returned envelope after this method returns, so the
                // FD must stay open until then. It is reclaimed by SharedMemory's GC Cleaner.
                val response = if (TransportSelector.shouldUseSharedMemory(resultBytes.size, threshold)
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    val shm = sharedMemoryTransport.writeToShared(resultBytes)
                    if (shm != null) IpcEnvelope(requestId = request.requestId, largePayload = true, sharedMemory = shm)
                    else IpcEnvelope.response(request.requestId, resultBytes)
                } else {
                    IpcEnvelope.response(request.requestId, resultBytes)
                }
                response
            } catch (e: SecurityException) {
                IpcEnvelope.error(ErrorCode.PERMISSION_DENIED, e.message ?: "Denied", request.requestId)
            } catch (e: IllegalStateException) {
                IpcEnvelope.error(ErrorCode.RATE_LIMITED, e.message ?: "Rate limited", request.requestId)
            } catch (e: Exception) {
                IpcEnvelope.error(ErrorCode.UNKNOWN, e.message ?: "Error", request.requestId)
            }
        }

        override fun subscribe(eventKey: String, callback: IIpcEventCallback) {
            eventSubscribers.getOrPut(eventKey) { CopyOnWriteArrayList() }.add(callback)
            FalconLogger.d("Host", "Subscribed: $eventKey")
        }

        override fun unsubscribe(eventKey: String, callback: IIpcEventCallback) {
            eventSubscribers[eventKey]?.remove(callback)
            FalconLogger.d("Host", "Unsubscribed: $eventKey")
        }

        override fun getServiceInfo(): String {
            return serviceRegistry.getAllServices().keys.joinToString(",")
        }
    }

    fun emitEvent(eventKey: String, event: IpcEnvelope) {
        eventSubscribers[eventKey]?.forEach { callback ->
            try {
                callback.onEvent(event)
            } catch (e: Exception) {
                FalconLogger.w("Host", "Failed to deliver event to subscriber: ${e.message}")
            }
        }
    }
}
