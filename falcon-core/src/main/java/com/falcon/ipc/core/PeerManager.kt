package com.falcon.ipc.core

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.falcon.ipc.ReconnectConfig
import com.falcon.ipc.TransportConfig
import com.falcon.ipc.aidl.IIpcHost
import com.falcon.ipc.transport.BinderTransport
import com.falcon.ipc.util.FalconLogger
import com.falcon.ipc.util.ProcessUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A lightweight peer connected via [BinderTransport], discovered through
 * [IpcRegistryProvider.call] (approach B — direct Binder exchange).
 *
 * No [android.content.ServiceConnection] is needed because the [IIpcHost] Binder
 * is returned synchronously via [android.os.Bundle.putBinder] in a single
 * ContentProvider round-trip.
 */
data class PeerConnection(
    val processName: String,
    val registryUri: Uri,
    val transport: BinderTransport,
    val binder: IBinder,
    val deathRecipient: IBinder.DeathRecipient
)

enum class IpcState { CONNECTED, DISCONNECTED, RECONNECTING }

class PeerManager(
    private val context: Context,
    private val registryUris: List<Uri>,
    private val threadPool: IpcThreadPool,
    private val reconnectConfig: ReconnectConfig = ReconnectConfig(),
    private val transportConfig: TransportConfig = TransportConfig()
) {
    private val connections = ConcurrentHashMap<String, PeerConnection>()
    private val pendingUris = ConcurrentHashMap<String, Uri>()
    private val reconnecting = ConcurrentHashMap.newKeySet<String>()
    // Serializes concurrent connectPeer attempts for the same process (refreshPeers vs reconnect).
    private val attempting = ConcurrentHashMap.newKeySet<String>()
    private val stateCallbacks = CopyOnWriteArrayList<(IpcState, String) -> Unit>()
    private val perPeerDelays = ConcurrentHashMap<String, AtomicLong>()
    private val retryCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val disconnectNotified = ConcurrentHashMap.newKeySet<String>()
    private val handler = Handler(Looper.getMainLooper())

    private val registryObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            refreshPeers()
        }
    }

    fun start() {
        for (uri in registryUris) {
            context.contentResolver.registerContentObserver(uri, false, registryObserver)
        }
        refreshPeers()
        FalconLogger.d("Peer", "PeerManager started across ${registryUris.size} registry URIs")
    }

    fun stop() {
        context.contentResolver.unregisterContentObserver(registryObserver)
        // Cancel any pending reconnect/refresh callbacks so they cannot resurrect
        // connections after shutdown.
        handler.removeCallbacksAndMessages(null)
        connections.values.forEach { conn ->
            try { conn.binder.unlinkToDeath(conn.deathRecipient, 0) }
            catch (e: Exception) { FalconLogger.w("Peer", "stop cleanup: ${e.message}") }
        }
        connections.clear()
        pendingUris.clear()
        reconnecting.clear()
        attempting.clear()
        retryCounts.clear()
        perPeerDelays.clear()
        disconnectNotified.clear()
    }

    fun getConnection(processName: String): PeerConnection? = connections[processName]

    fun getAllConnections(): Map<String, PeerConnection> = connections.toMap()

    fun onConnectionStateChanged(callback: (IpcState, String) -> Unit) {
        stateCallbacks.add(callback)
    }

    fun removeConnectionStateCallback(callback: (IpcState, String) -> Unit) {
        stateCallbacks.remove(callback)
    }

    private fun refreshPeers() {
        threadPool.submit {
            try {
                for (uri in registryUris) {
                    val cursor = try {
                        context.contentResolver.query(uri, null,
                            "process_name != ?",
                            arrayOf(ProcessUtils.getCurrentProcessName(context)), null)
                    } catch (e: Exception) { continue }
                    cursor?.use {
                        val colIdx = it.getColumnIndex("process_name")
                        if (colIdx < 0) return@use
                        while (it.moveToNext()) {
                            val name = it.getString(colIdx)
                            if (!connections.containsKey(name) && !reconnecting.contains(name)) {
                                connectPeer(name, uri)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                FalconLogger.e("Peer", "refreshPeers failed — peer discovery may be stale", e)
                // Re-schedule a refresh attempt after a delay to recover
                handler.postDelayed({ refreshPeers() }, 30_000L)
            }
        }
    }

    /**
     * Discover and connect to a peer via [IpcRegistryProvider.call] instead of
     * [android.content.Context.bindService].
     *
     * The ContentProvider returns the [IIpcHost] Binder directly in a Bundle
     * via [android.os.Bundle.putBinder].  This is synchronous and eliminates
     * the ServiceConnection / onServiceConnected lifecycle dance.
     */
    private fun connectPeer(processName: String, registryUri: Uri) {
        // Only one connect attempt per process at a time — prevents refreshPeers and a
        // scheduled reconnect from both connecting the same peer (which would orphan a
        // PeerConnection whose linkToDeath later kills the live connection).
        if (!attempting.add(processName)) return
        try {
            // Already connected (e.g. a stale reconnect fired after another path succeeded).
            // The death recipient drives recovery if that live connection is actually dead.
            if (connections.containsKey(processName)) return

            val hostBinder = try {
                val result = context.contentResolver.call(registryUri, "getHost", null, null)
                result?.getBinder("host")
            } catch (e: Exception) {
                FalconLogger.e("Peer", "call(getHost) failed for $processName: ${e.message}")
                null
            }

            if (hostBinder == null) {
                FalconLogger.w("Peer", "Host binder not ready for $processName — will retry")
                pendingUris[processName] = registryUri
                scheduleReconnect(processName)
                return
            }

            val deathRecipient = object : IBinder.DeathRecipient {
                override fun binderDied() {
                    FalconLogger.w("Peer", "$processName died")
                    // Identity check: only react if our connection is still the current one.
                    // A newer reconnect may have already replaced it — don't kill the live one.
                    if (connections[processName]?.deathRecipient !== this) return
                    connections.remove(processName)
                    pendingUris[processName] = registryUri   // captured from closure, not the map
                    // Guard against double DISCONNECTED
                    if (disconnectNotified.add(processName)) {
                        notifyState(IpcState.DISCONNECTED, processName)
                    }
                    scheduleReconnect(processName)
                }
            }

            val host = IIpcHost.Stub.asInterface(hostBinder)
            val transport = BinderTransport(host, transportConfig.maxBinderPayloadSize, transportConfig.invokeTimeoutMs)
            val peer = PeerConnection(processName, registryUri, transport, hostBinder, deathRecipient)
            connections[processName] = peer

            try {
                hostBinder.linkToDeath(deathRecipient, 0)
            } catch (e: Exception) {
                FalconLogger.w("Peer", "linkToDeath failed for $processName: ${e.message}")
            }

            pendingUris.remove(processName)
            reconnecting.remove(processName)
            disconnectNotified.remove(processName)
            retryCounts.remove(processName)
            perPeerDelays[processName]?.set(reconnectConfig.initialDelayMs)
            notifyState(IpcState.CONNECTED, processName)
            FalconLogger.d("Peer", "Connected to $processName")
        } finally {
            attempting.remove(processName)
        }
    }

    private fun scheduleReconnect(processName: String) {
        if (!reconnectConfig.enabled) return
        if (!reconnecting.add(processName)) return

        // Respect maxRetries (-1 = infinite)
        val maxRetries = reconnectConfig.maxRetries
        if (maxRetries >= 0) {
            val count = retryCounts.getOrPut(processName) { AtomicInteger(0) }.incrementAndGet()
            if (count > maxRetries) {
                FalconLogger.w("Peer", "Max retries ($maxRetries) exceeded for $processName — giving up")
                pendingUris.remove(processName)
                reconnecting.remove(processName)
                retryCounts.remove(processName)
                return
            }
        }

        notifyState(IpcState.RECONNECTING, processName)

        val peerDelay = perPeerDelays.getOrPut(processName) { AtomicLong(reconnectConfig.initialDelayMs) }
        val delay = peerDelay.get()
        handler.postDelayed({
            val uri = connections[processName]?.registryUri ?: pendingUris[processName] ?: return@postDelayed
            FalconLogger.d("Peer", "Reconnecting to $processName (delay=${delay}ms)")
            reconnecting.remove(processName)
            disconnectNotified.remove(processName)
            // Run connectPeer off the main thread — ContentResolver.call() blocks
            threadPool.submit { connectPeer(processName, uri) }
            peerDelay.updateAndGet {
                (it * 2).coerceAtMost(reconnectConfig.maxDelayMs)
            }
        }, delay)
    }

    private fun notifyState(state: IpcState, processName: String) {
        stateCallbacks.forEach { it(state, processName) }
    }
}
