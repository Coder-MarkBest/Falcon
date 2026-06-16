package com.falcon.ipc.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.falcon.ipc.aidl.IIpcHost
import com.falcon.ipc.transport.BinderTransport
import com.falcon.ipc.transport.SharedMemoryTransport
import com.falcon.ipc.util.FalconLogger
import com.falcon.ipc.util.ProcessUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

data class PeerConnection(
    val processName: String,
    val transport: BinderTransport,
    val binder: IBinder,
    val deathRecipient: IBinder.DeathRecipient,
    val serviceConnection: ServiceConnection
)

enum class IpcState { CONNECTED, DISCONNECTED, RECONNECTING }

class PeerManager(
    private val context: Context,
    private val registryUri: Uri,
    private val threadPool: IpcThreadPool,
    private val sharedMemoryTransport: SharedMemoryTransport? = null
) {
    private val connections = ConcurrentHashMap<String, PeerConnection>()
    private val stateCallbacks = CopyOnWriteArrayList<(IpcState, String) -> Unit>()
    private val reconnectDelayMs = AtomicLong(500)
    private val maxReconnectDelayMs = 30_000L
    private val handler = Handler(Looper.getMainLooper())

    private val registryObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            refreshPeers()
        }
    }

    fun start() {
        context.contentResolver.registerContentObserver(registryUri, false, registryObserver)
        refreshPeers()
        FalconLogger.d("Peer", "PeerManager started")
    }

    fun stop() {
        context.contentResolver.unregisterContentObserver(registryObserver)
        connections.values.forEach { conn ->
            try { conn.binder.unlinkToDeath(conn.deathRecipient, 0) }
            catch (_: Exception) {}
            try { context.unbindService(conn.serviceConnection) }
            catch (_: Exception) {}
        }
        connections.clear()
    }

    fun getConnection(processName: String): PeerConnection? = connections[processName]

    fun getAllConnections(): Map<String, PeerConnection> = connections.toMap()

    fun onConnectionStateChanged(callback: (IpcState, String) -> Unit) {
        stateCallbacks.add(callback)
    }

    private fun refreshPeers() {
        threadPool.submit {
            val names = queryPeerProcessNames()
            handler.post {
                names.forEach { name ->
                    if (!connections.containsKey(name)) bindPeer(name)
                }
            }
        }
    }

    private fun queryPeerProcessNames(): Set<String> {
        val cursor = context.contentResolver.query(
            registryUri, null,
            "process_name != ?",
            arrayOf(ProcessUtils.getCurrentProcessName(context)),
            null
        ) ?: return emptySet()
        val names = mutableSetOf<String>()
        cursor.use {
            val colIdx = it.getColumnIndex("process_name")
            if (colIdx < 0) return emptySet()
            while (it.moveToNext()) names.add(it.getString(colIdx))
        }
        return names
    }

    private fun bindPeer(processName: String) {
        val intent = Intent("com.falcon.ipc.HOST_SERVICE").apply {
            setPackage(context.packageName)
            putExtra("target_process", processName)
        }

        val deathRecipient = IBinder.DeathRecipient {
            FalconLogger.w("Peer", "$processName died")
            connections.remove(processName)
            notifyState(IpcState.DISCONNECTED, processName)
            scheduleReconnect(processName)
        }

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val host = IIpcHost.Stub.asInterface(binder)
                val transport = BinderTransport(host, sharedMemoryTransport)
                val peer = PeerConnection(processName, transport, binder, deathRecipient, this)
                connections[processName] = peer

                binder.linkToDeath(deathRecipient, 0)

                reconnectDelayMs.set(500)
                notifyState(IpcState.CONNECTED, processName)
                FalconLogger.d("Peer", "Connected to $processName")
            }

            override fun onServiceDisconnected(name: ComponentName) {
                connections.remove(processName)
                notifyState(IpcState.DISCONNECTED, processName)
                scheduleReconnect(processName)
            }
        }

        try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            FalconLogger.e("Peer", "Failed to bind $processName", e)
            scheduleReconnect(processName)
        }
    }

    private fun scheduleReconnect(processName: String) {
        notifyState(IpcState.RECONNECTING, processName)
        val delay = reconnectDelayMs.get()
        handler.postDelayed({
            FalconLogger.d("Peer", "Reconnecting to $processName (delay=${delay}ms)")
            bindPeer(processName)
            reconnectDelayMs.updateAndGet { (it * 2).coerceAtMost(maxReconnectDelayMs) }
        }, delay)
    }

    private fun notifyState(state: IpcState, processName: String) {
        stateCallbacks.forEach { it(state, processName) }
    }
}
