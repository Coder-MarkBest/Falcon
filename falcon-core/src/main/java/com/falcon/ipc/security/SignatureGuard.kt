package com.falcon.ipc.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import com.falcon.ipc.util.FalconLogger
import java.security.MessageDigest

class SignatureGuard {

    private var selfSignatureHash: String = ""
    private var selfUid: Int = -1
    private var trustedSignatures: Set<String> = emptySet()
    private val verifiedUids = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    companion object {
        /** True iff every caller package signature is in the trusted set (and there is at least one). */
        fun isTrusted(callerSignatureHashes: Set<String>, trusted: Set<String>): Boolean =
            callerSignatureHashes.isNotEmpty() && trusted.containsAll(callerSignatureHashes)
    }

    fun init(context: Context, trustedSignatures: Set<String> = emptySet()) {
        selfUid = Process.myUid()
        selfSignatureHash = computeSignatureHash(context, context.packageName)
        this.trustedSignatures = trustedSignatures
        FalconLogger.d("Security", "SignatureGuard initialized, UID=$selfUid, trusted=${trustedSignatures.size}")
    }

    fun verify(context: Context, callingUid: Int): Boolean {
        verifiedUids[callingUid]?.let { return it }
        val result = computeVerification(context, callingUid)
        verifiedUids[callingUid] = result
        return result
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
