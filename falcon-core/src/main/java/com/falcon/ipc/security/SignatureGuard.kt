package com.falcon.ipc.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import com.falcon.ipc.util.FalconLogger
import java.security.MessageDigest

class SignatureGuard {

    /** Can be set to false to disable all signature checks (controlled by [SecurityConfig.signatureVerification]). */
    @Volatile var enabled: Boolean = true

    private var selfSignatureHash: String = ""
    private var selfUid: Int = -1
    private var trustedSignatures: Set<String> = emptySet()
    private val verifiedUids = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
    @Volatile private var packageReceiver: BroadcastReceiver? = null
    @Volatile private var receiverContext: Context? = null

    companion object {
        /** True iff every caller package signature is in the trusted set (and there is at least one). */
        fun isTrusted(callerSignatureHashes: Set<String>, trusted: Set<String>): Boolean =
            callerSignatureHashes.isNotEmpty() && trusted.containsAll(callerSignatureHashes)
    }

    fun init(context: Context, trustedSignatures: Set<String> = emptySet()) {
        selfUid = Process.myUid()
        selfSignatureHash = computeSignatureHash(context, context.packageName)
        this.trustedSignatures = trustedSignatures
        registerPackageReceiver(context)
        FalconLogger.d("Security", "SignatureGuard initialized, UID=$selfUid, trusted=${trustedSignatures.size}")
    }

    fun verify(context: Context, callingUid: Int): Boolean {
        if (!enabled) return true
        verifiedUids[callingUid]?.let { return it }
        val result = computeVerification(context, callingUid)
        verifiedUids[callingUid] = result
        return result
    }

    /** Invalidate cached verification result for a specific UID (e.g., after app update). */
    fun invalidateCache(uid: Int) {
        verifiedUids.remove(uid)
        FalconLogger.d("Security", "Cache invalidated for UID=$uid")
    }

    /** Invalidate all cached verification results. */
    fun invalidateAll() {
        verifiedUids.clear()
        FalconLogger.d("Security", "All signature caches invalidated")
    }

    /** Unregisters the package-change BroadcastReceiver. Call on framework shutdown. */
    fun shutdown() {
        val ctx = receiverContext
        val receiver = packageReceiver
        if (ctx != null && receiver != null) {
            try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
        receiverContext = null
        packageReceiver = null
    }

    private fun registerPackageReceiver(context: Context) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    FalconLogger.d("Security", "Package change detected, invalidating signature caches")
                    invalidateAll()
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
            packageReceiver = receiver
            receiverContext = context
        } catch (e: Exception) {
            FalconLogger.w("Security", "Failed to register package receiver: ${e.message}")
        }
    }

    private fun computeVerification(context: Context, callingUid: Int): Boolean {
        val callerPkgs = context.packageManager.getPackagesForUid(callingUid) ?: return false
        if (callerPkgs.isEmpty()) return false

        val trusted = trustedSignatures + selfSignatureHash
        val callerHashes = mutableSetOf<String>()
        for (pkg in callerPkgs) {
            val hash = try {
                computeSignatureHash(context, pkg)
            } catch (e: Exception) {
                FalconLogger.e("Security", "Failed to read signature for $pkg", e)
                return false
            }
            callerHashes.add(hash)
        }
        return isTrusted(callerHashes, trusted)
    }

    private fun computeSignatureHash(context: Context, packageName: String): String {
        val pm = context.packageManager
        val digest = MessageDigest.getInstance("SHA-256")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners?.forEach { sig ->
                digest.update(sig.toByteArray())
            }
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            info.signatures?.forEach { sig ->
                digest.update(sig.toByteArray())
            }
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
