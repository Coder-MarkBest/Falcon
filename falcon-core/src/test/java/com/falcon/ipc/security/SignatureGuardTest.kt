package com.falcon.ipc.security

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Process
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class SignatureGuardTest {

    private lateinit var context: Context
    private lateinit var packageManager: PackageManager

    @Before
    fun setup() {
        context = mock()
        packageManager = mock()
        whenever(context.packageManager).thenReturn(packageManager)
        whenever(context.packageName).thenReturn("com.falcon.test")
    }

    private fun sha256(certBytes: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(Signature(certBytes.toByteArray()).toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun mockPackage(pkg: String, certBytes: String) {
        whenever(packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNATURES))
            .thenReturn(PackageInfo().apply { signatures = arrayOf(Signature(certBytes.toByteArray())) })
    }

    @Test
    fun `verify returns true for own signature`() {
        val uid = Process.myUid()
        whenever(packageManager.getPackagesForUid(uid)).thenReturn(arrayOf("com.falcon.test"))
        mockPackage("com.falcon.test", "self-cert")

        val guard = SignatureGuard()
        guard.init(context)  // no trusted sigs -> only self trusted
        assertTrue(guard.verify(context, uid))
    }

    @Test
    fun `verify returns false for untrusted third-party signature`() {
        val uid = 99999
        whenever(packageManager.getPackagesForUid(uid)).thenReturn(arrayOf("com.evil"))
        mockPackage("com.evil", "evil-cert")
        whenever(packageManager.getPackagesForUid(Process.myUid())).thenReturn(arrayOf("com.falcon.test"))
        mockPackage("com.falcon.test", "self-cert")

        val guard = SignatureGuard()
        guard.init(context)
        assertFalse(guard.verify(context, uid))
    }

    @Test
    fun `verify returns true for whitelisted third-party signature`() {
        val uid = 88888
        whenever(packageManager.getPackagesForUid(uid)).thenReturn(arrayOf("com.partner"))
        mockPackage("com.partner", "partner-cert")
        mockPackage("com.falcon.test", "self-cert")

        val guard = SignatureGuard()
        guard.init(context, setOf(sha256("partner-cert")))  // pin partner cert
        assertTrue(guard.verify(context, uid))
    }
}
