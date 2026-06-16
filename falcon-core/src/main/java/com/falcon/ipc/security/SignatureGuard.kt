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
