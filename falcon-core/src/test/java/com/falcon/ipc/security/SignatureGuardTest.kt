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

    @Test
    fun `verify returns true for same UID`() {
        val uid = Process.myUid()
        whenever(packageManager.getPackagesForUid(uid)).thenReturn(arrayOf("com.falcon.test"))
        val sig = Signature("test-cert".toByteArray())
        val pkgInfo = PackageInfo().apply { signatures = arrayOf(sig) }
        whenever(packageManager.getPackageInfo("com.falcon.test", PackageManager.GET_SIGNATURES))
            .thenReturn(pkgInfo)

        val guard = SignatureGuard()
        guard.init(context)
        assertTrue(guard.verify(context, uid))
    }

    @Test
    fun `verify returns false for different UID`() {
        val guard = SignatureGuard()
        guard.init(context)
        assertFalse(guard.verify(context, 99999))
    }
}
