package com.falcon.ipc.security

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Process
import com.falcon.ipc.util.FalconLogger
import java.security.MessageDigest

class SignatureGuard {

    private var selfSignatureHash: String = ""
    private var selfUid: Int = -1

    fun init(context: Context) {
        selfUid = Process.myUid()
        selfSignatureHash = computeSignatureHash(context, context.packageName)
        FalconLogger.d("Security", "SignatureGuard initialized, UID=$selfUid")
    }

    fun verify(context: Context, callingUid: Int): Boolean {
        if (callingUid != selfUid) {
            FalconLogger.w("Security", "UID mismatch: caller=$callingUid self=$selfUid")
            return false
        }

        val callerPkgs = context.packageManager.getPackagesForUid(callingUid)
            ?: return false

        return callerPkgs.all { pkg ->
            try {
                computeSignatureHash(context, pkg) == selfSignatureHash
            } catch (e: Exception) {
                FalconLogger.e("Security", "Failed to verify signature for $pkg", e)
                false
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun computeSignatureHash(context: Context, packageName: String): String {
        val pm = context.packageManager
        val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        val digest = MessageDigest.getInstance("SHA-256")
        info.signatures?.forEach { sig: Signature ->
            digest.update(sig.toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
