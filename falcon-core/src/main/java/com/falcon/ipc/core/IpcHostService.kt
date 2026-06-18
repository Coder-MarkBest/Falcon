package com.falcon.ipc.core

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.falcon.ipc.Falcon
import com.falcon.ipc.aidl.IIpcEventCallback
import com.falcon.ipc.aidl.IIpcHost
import com.falcon.ipc.protocol.ErrorCode
import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.protocol.IpcSerializer
import com.falcon.ipc.security.SignatureGuard
import com.falcon.ipc.util.CallerResolver
import com.falcon.ipc.util.FalconLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class IpcHostService : Service() {

    private lateinit var signatureGuard: SignatureGuard
    private lateinit var callerResolver: CallerResolver
    private lateinit var serviceRegistry: ServiceRegistry
    private lateinit var messageRouter: MessageRouter
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
    }

    // getCallingUid() in onBind is best-effort early rejection; the authoritative
    // per-call signature check runs in invoke() within the Binder transaction frame.
    @android.annotation.SuppressLint("BinderGetCallingInMainThread")
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
            val callerPackage = callerResolver.resolve(callingUid)
            return try {
                val result = messageRouter.handleLocal(request, callerPackage, callingPid)
                val resp = if (result is android.os.Bundle)
                    IpcEnvelope(requestId = request.requestId, argsBundle = result)
                else
                    IpcEnvelope.response(request.requestId, IpcSerializer.serializeResult(result))
                resp
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

        override fun invokeCallback(request: IpcEnvelope, reply: IIpcEventCallback) {
            // Wired to dispatcher in a later task (P2B Task 5)
            FalconLogger.w("Host", "invokeCallback not yet wired")
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
