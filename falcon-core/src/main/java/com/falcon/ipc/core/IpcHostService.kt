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

    companion object {
        /** Exposed for [IpcRegistryProvider.call] to return the host Binder via [Bundle.putBinder]. */
        @Volatile
        var hostBinder: IIpcHost.Stub? = null
            private set
    }

    private lateinit var signatureGuard: SignatureGuard
    private lateinit var callerResolver: CallerResolver
    private lateinit var serviceRegistry: ServiceRegistry
    private lateinit var messageRouter: MessageRouter
    private lateinit var threadPool: IpcThreadPool
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
        threadPool = falconManager.threadPool
        // Expose hostBinder for discovery via ContentProvider.call("getHost")
        IpcHostService.hostBinder = hostBinder
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
            val callerProcess = callerResolver.resolveProcessName(callingPid) ?: callerPackage
            return try {
                val result = messageRouter.handleLocal(request, callerPackage, callingPid, callerProcess)
                IpcEnvelope(requestId = request.requestId, argsBundle = result as android.os.Bundle)
            } catch (e: SecurityException) {
                IpcEnvelope.error(ErrorCode.PERMISSION_DENIED, e.message ?: "Denied", request.requestId)
            } catch (e: IllegalStateException) {
                IpcEnvelope.error(ErrorCode.RATE_LIMITED, e.message ?: "Rate limited", request.requestId)
            } catch (e: com.falcon.ipc.protocol.IpcException) {
                IpcEnvelope.error(e.errorCode, e.message ?: "IPC error", request.requestId)
            } catch (e: Exception) {
                IpcEnvelope.error(ErrorCode.UNKNOWN, e.message ?: "Error", request.requestId)
            }
        }

        override fun subscribe(eventKey: String, callback: IIpcEventCallback) {
            val parts = eventKey.split("#")
            val methodId = if (parts.size == 2) parts[1].toIntOrNull() else null
            if (methodId != null) {
                val serviceKey = parts[0]
                // Multi-app: if this build has no event for the methodId, surface METHOD_NOT_FOUND
                // instead of silently registering a subscription that never emits.
                val dispatcher = serviceRegistry.getDispatcher(serviceKey)
                if (dispatcher == null || dispatcher.eventFlow(methodId) == null) {
                    try { callback.onEvent(IpcEnvelope.error(ErrorCode.METHOD_NOT_FOUND, "No event for methodId $methodId")) }
                    catch (e: Exception) { FalconLogger.w("Host", "event error reply failed: ${e.message}") }
                    return
                }
            }
            eventSubscribers.getOrPut(eventKey) { CopyOnWriteArrayList() }.add(callback)
            FalconLogger.d("Host", "Subscribed: $eventKey")
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
            val d = serviceRegistry.getDispatcher(request.serviceKey)
            if (d == null) {
                FalconLogger.w("Host", "invokeCallback: service not found: ${request.serviceKey}")
                try {
                    reply.onEvent(IpcEnvelope.error(ErrorCode.SERVICE_NOT_FOUND, "Service not found: ${request.serviceKey}", request.requestId))
                } catch (e: Exception) { FalconLogger.w("Host", "callback error reply failed: ${e.message}") }
                return
            }
            try {
                d.invokeCallback(request.methodId, request.argsBundle ?: Bundle()) { env ->
                    // env is either a success (argsBundle) or error (isError) envelope; stamp
                    // the original requestId and forward it. The proxy stub reads event.isError.
                    try { reply.onEvent(env.copy(requestId = request.requestId)) }
                    catch (e: Exception) { FalconLogger.w("Host", "callback reply failed: ${e.message}") }
                }
            } catch (e: Exception) {
                FalconLogger.e("Host", "invokeCallback service threw: ${e.message}", e)
                try {
                    reply.onEvent(IpcEnvelope.error(ErrorCode.UNKNOWN, "Service error: ${e.message}", request.requestId))
                } catch (e2: Exception) { FalconLogger.w("Host", "callback error reply failed: ${e2.message}") }
            }
        }
    }

    fun emitEvent(eventKey: String, event: IpcEnvelope) {
        eventSubscribers[eventKey]?.forEach { callback ->
            threadPool.submit {
                try {
                    callback.onEvent(event)
                } catch (e: Exception) {
                    FalconLogger.w("Host", "Failed to deliver event to subscriber: ${e.message}")
                }
            }
        }
    }

    private fun emitBundle(eventKey: String, bundle: Bundle) {
        eventSubscribers[eventKey]?.forEach { cb ->
            threadPool.submit {
                try {
                    cb.onEvent(IpcEnvelope(serviceKey = eventKey, method = "__event__", argsBundle = bundle))
                } catch (e: Exception) {
                    FalconLogger.w("Host", "event delivery failed: ${e.message}")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        IpcHostService.hostBinder = null
        eventCollector.shutdown()
    }
}
