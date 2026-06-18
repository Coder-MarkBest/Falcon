package com.falcon.ipc.core

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import com.falcon.ipc.Falcon
import com.falcon.ipc.aidl.IIpcEventCallback
import com.falcon.ipc.aidl.IIpcHost
import com.falcon.ipc.protocol.ErrorCode
import com.falcon.ipc.protocol.IpcEnvelope
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
    private val eventCollector = EventCollector()
    // Per-subscription death cleanup: each subscribe() callback is a distinct binder.
    // The AtomicBoolean guards the ref-count decrement so it runs exactly once whether
    // released via explicit unsubscribe() OR the subscriber process dying.
    private val deathRecipients =
        ConcurrentHashMap<IIpcEventCallback, Pair<IBinder.DeathRecipient, java.util.concurrent.atomic.AtomicBoolean>>()

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
                IpcEnvelope(requestId = request.requestId, argsBundle = result as android.os.Bundle)
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
            val parts = eventKey.split("#")
            val methodId = if (parts.size == 2) parts[1].toIntOrNull() else null
            if (methodId != null) {
                val serviceKey = parts[0]
                eventCollector.onSubscribe(eventKey,
                    { serviceRegistry.getDispatcher(serviceKey)?.eventFlow(methodId) },
                    { bundle -> emitBundle(eventKey, bundle) })
                // Release the subscription if the client process dies without unsubscribing.
                val released = java.util.concurrent.atomic.AtomicBoolean(false)
                val recipient = IBinder.DeathRecipient {
                    if (released.compareAndSet(false, true)) {
                        eventSubscribers[eventKey]?.remove(callback)
                        eventCollector.onUnsubscribe(eventKey)
                        deathRecipients.remove(callback)
                    }
                }
                try {
                    callback.asBinder().linkToDeath(recipient, 0)
                    deathRecipients[callback] = recipient to released
                } catch (e: Exception) {
                    // Binder already dead — undo the subscription immediately.
                    if (released.compareAndSet(false, true)) {
                        eventSubscribers[eventKey]?.remove(callback)
                        eventCollector.onUnsubscribe(eventKey)
                    }
                }
            }
        }

        override fun unsubscribe(eventKey: String, callback: IIpcEventCallback) {
            eventSubscribers[eventKey]?.remove(callback)
            val entry = deathRecipients.remove(callback)
            if (entry != null) {
                try { callback.asBinder().unlinkToDeath(entry.first, 0) } catch (_: Exception) {}
                if (entry.second.compareAndSet(false, true)) eventCollector.onUnsubscribe(eventKey)
            } else {
                eventCollector.onUnsubscribe(eventKey)
            }
            FalconLogger.d("Host", "Unsubscribed: $eventKey")
        }

        override fun getServiceInfo(): String {
            return serviceRegistry.getAllServices().keys.joinToString(",")
        }

        override fun invokeCallback(request: IpcEnvelope, reply: com.falcon.ipc.aidl.IIpcEventCallback) {
            val d = serviceRegistry.getDispatcher(request.serviceKey) ?: return
            d.invokeCallback(request.methodId, request.argsBundle ?: Bundle()) { b ->
                try { reply.onEvent(IpcEnvelope(requestId = request.requestId, argsBundle = b)) }
                catch (e: Exception) { FalconLogger.w("Host", "callback reply failed: ${e.message}") }
            }
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

    private fun emitBundle(eventKey: String, bundle: Bundle) {
        eventSubscribers[eventKey]?.forEach { cb ->
            try { cb.onEvent(IpcEnvelope(serviceKey = eventKey, method = "__event__", argsBundle = bundle)) }
            catch (e: Exception) { FalconLogger.w("Host", "event delivery failed: ${e.message}") }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        eventCollector.shutdown()
    }
}
