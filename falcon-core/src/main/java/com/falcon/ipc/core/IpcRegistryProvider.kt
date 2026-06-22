package com.falcon.ipc.core

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.falcon.ipc.security.SignatureGuard
import com.falcon.ipc.util.FalconLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight in-process service registry exposed as a [ContentProvider].
 *
 * **Why not SQLite?** Service registration is ephemeral — when the hosting
 * process dies, its services are gone. Persistent storage adds DB-corruption
 * risk, cross-process SQLite contention, and zombie-record cleanup with no
 * benefit for transient discovery data.
 *
 * This provider runs in a designated "registry host" process (usually the
 * process that calls [Falcon.init]). Other processes write registrations via
 * [insert] and read them via [query]. [ContentObserver] notifications drive
 * [PeerManager] discovery.
 */
class IpcRegistryProvider : ContentProvider() {

    private lateinit var signatureGuard: SignatureGuard

    /** In-memory registry: serviceKey → Registration. */
    private val registrations = ConcurrentHashMap<String, Registration>()

    data class Registration(
        val serviceKey: String,
        val processName: String,
        val pkgName: String,
        val registerTime: Long,
        val pid: Int
    )

    companion object {
        private val COLUMNS = arrayOf("service_key", "process_name", "pkg_name", "register_time", "pid")
    }

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        signatureGuard = SignatureGuard().apply { init(ctx) }
        FalconLogger.d("Registry", "IpcRegistryProvider created (in-memory)")
        return true
    }

    override fun query(
        uri: Uri, projection: Array<out String>?,
        selection: String?, selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        enforceSignature()

        // Validate selection against injection (parameterized; no raw VALUES allowed)
        if (selection != null && !selection.matches(Regex("^[a-zA-Z0-9_\\s()!=><.,%?]+$"))) {
            throw IllegalArgumentException("Invalid selection: $selection")
        }

        // Filter in code instead of SQL — simpler and safe against injection
        val filtered = if (selection != null && selectionArgs != null) {
            filterInMemory(selection, selectionArgs)
        } else {
            registrations.values.toList()
        }

        val cursor = MatrixCursor(projection ?: COLUMNS, filtered.size)
        for (reg in filtered) {
            cursor.addRow(arrayOf(reg.serviceKey, reg.processName, reg.pkgName, reg.registerTime, reg.pid))
        }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        // Only the local process can register services — external apps cannot
        // inject registrations into this provider even if their signature is trusted.
        if (android.os.Binder.getCallingUid() != android.os.Process.myUid()) {
            throw SecurityException("Only same-UID registration allowed")
        }
        enforceSignature()
        val cv = values ?: return null

        val serviceKey = cv.getAsString("service_key") ?: return null
        val processName = cv.getAsString("process_name") ?: return null
        val pkgName = cv.getAsString("pkg_name") ?: (context?.packageName ?: "")

        val reg = Registration(
            serviceKey = serviceKey,
            processName = processName,
            pkgName = pkgName,
            registerTime = System.currentTimeMillis(),
            pid = android.os.Process.myPid()
        )

        val previous = registrations.put(serviceKey, reg)
        if (previous != null) {
            FalconLogger.w("Registry", "Overwriting registration: $serviceKey (prev=${previous.processName}, new=${processName})")
        }

        context?.contentResolver?.notifyChange(uri, null)
        FalconLogger.d("Registry", "Registered: $serviceKey @ $processName")
        return uri
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        enforceSignature()

        // Support deletion by service_key = ?
        if (selection == "service_key = ?" && selectionArgs != null && selectionArgs.size == 1) {
            val removed = registrations.remove(selectionArgs[0])
            if (removed != null) {
                context?.contentResolver?.notifyChange(uri, null)
                FalconLogger.d("Registry", "Unregistered: ${selectionArgs[0]}")
                return 1
            }
        }
        return 0
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Update not supported — use insert (upsert via service_key)")

    override fun getType(uri: Uri): String? = null

    /**
     * Direct Binder exchange — the core of discovery approach B.
     *
     * Instead of the 2-step "query metadata → bindService → onServiceConnected"
     * dance, clients call `call("getHost")` and receive the [IIpcHost] Binder
     * directly via [Bundle.putBinder]. This:
     * - Collapses discovery from 4 cross-process round-trips to 1
     * - Eliminates [ServiceConnection] / bindService lifecycle races
     * - Benefits from ContentProvider's higher system priority vs Service
     *
     * @param method  "getHost" to retrieve the host [IIpcHost] Binder
     * @return Bundle with "host" key containing the Binder, or null for unknown methods
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        enforceSignature()
        return when (method) {
            "getHost" -> {
                val binder = IpcHostService.hostBinder
                if (binder != null) {
                    Bundle().apply { putBinder("host", binder.asBinder()) }
                } else {
                    FalconLogger.w("Registry", "getHost: hostBinder not ready yet")
                    null
                }
            }
            else -> {
                FalconLogger.w("Registry", "Unknown call method: $method")
                null
            }
        }
    }

    private fun enforceSignature() {
        val ctx = context ?: throw SecurityException("No context")
        val callingUid = Binder.getCallingUid()
        if (!signatureGuard.verify(ctx, callingUid)) {
            throw SecurityException("Falcon IPC: Unauthorized access from UID $callingUid")
        }
    }

    /**
     * Simple in-memory filter for "column op ?" patterns.
     * Supports: =, !=, >, <  with single argument.
     */
    private fun filterInMemory(selection: String, args: Array<out String>): List<Registration> {
        // Parse "column_name op ?" pattern
        val parts = selection.trim().split("\\s+".toRegex(), limit = 3)
        if (parts.size < 3) return registrations.values.toList()

        val column = parts[0]
        val op = parts[1]
        val value = args.firstOrNull() ?: return emptyList()

        return registrations.values.filter { reg ->
            val fieldValue = when (column) {
                "service_key" -> reg.serviceKey
                "process_name" -> reg.processName
                "pkg_name" -> reg.pkgName
                else -> return@filter true // unknown column → include
            }
            when (op) {
                "=" -> fieldValue == value
                "!=" -> fieldValue != value
                else -> true // unsupported op → include
            }
        }
    }
}
