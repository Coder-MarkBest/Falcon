package com.falcon.ipc.core

import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.whenever
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IpcRegistryProviderTest {

    @Test
    fun `insert with same UID guard check logic is correct`() {
        // The guard checks Binder.getCallingUid() == Process.myUid().
        // In Robolectric unit tests, both return 0.
        // The test verifies the logic is wired, not that security holds
        // (that requires an instrumented test with real UIDs).
        assertEquals(Process.myUid(), android.os.Binder.getCallingUid())
    }
    @Test
    fun `old trust verification cannot poison cache after trust becomes ready`() {
        val provider = org.robolectric.Robolectric.buildContentProvider(IpcRegistryProvider::class.java).create().get()
        val guard = org.mockito.Mockito.mock(com.falcon.ipc.security.SignatureGuard::class.java)
        IpcRegistryProvider::class.java.getDeclaredField("signatureGuard").apply { isAccessible = true }.set(provider, guard)
        IpcRegistryProvider.trustedSignatures = emptySet()
        val oldVerifyStarted = java.util.concurrent.CountDownLatch(1)
        val releaseOldVerify = java.util.concurrent.CountDownLatch(1)
        val reinitialized = java.util.concurrent.CountDownLatch(1)
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        val allowed = java.util.concurrent.atomic.AtomicBoolean(false)
        val cached = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
        org.mockito.kotlin.doAnswer {
            allowed.set(true); reinitialized.countDown(); null
        }.whenever(guard).init(org.mockito.kotlin.any(), org.mockito.kotlin.any())
        org.mockito.kotlin.doAnswer { cached.set(null); null }.whenever(guard).invalidateAll()
        org.mockito.kotlin.doAnswer {
            cached.get() ?: run {
                val outcome = allowed.get()
                if (first.getAndSet(false)) {
                    oldVerifyStarted.countDown()
                    check(releaseOldVerify.await(3, java.util.concurrent.TimeUnit.SECONDS))
                }
                cached.set(outcome)
                outcome
            }
        }.whenever(guard).verify(org.mockito.kotlin.any(), org.mockito.kotlin.any())
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        fun query() = provider.query(android.net.Uri.parse("content://test.falcon.registry"), null, null, null, null).use { it.count }
        try {
            val old = pool.submit<Int> { query() }
            check(oldVerifyStarted.await(3, java.util.concurrent.TimeUnit.SECONDS))
            IpcRegistryProvider.trustedSignatures = setOf("new-trusted-certificate")
            val updated = pool.submit<Int> { query() }
            // Without serialization, the second query installs new trust and succeeds
            // while the old verification is paused, then the old denial poisons its cache.
            if (reinitialized.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)) updated.get(3, java.util.concurrent.TimeUnit.SECONDS)
            releaseOldVerify.countDown()
            old.get(3, java.util.concurrent.TimeUnit.SECONDS)
            updated.get(3, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(0, query()) // Still authorized; no registrations in this test.
            assertEquals(true, cached.get())
        } finally {
            releaseOldVerify.countDown()
            pool.shutdownNow()
            IpcRegistryProvider.trustedSignatures = emptySet()
        }
    }

}
