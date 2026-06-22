package com.falcon.ipc.core

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PeerManagerTest {

    @Test
    fun `registryUris resolves N URIs from N peer packages`() {
        val packages = setOf("com.a", "com.b")
        val uris = packages.map { Uri.parse("content://$it.falcon.registry/services") }
        assertEquals(2, uris.size)
        assertEquals("content://com.a.falcon.registry/services", uris[0].toString())
        assertEquals("content://com.b.falcon.registry/services", uris[1].toString())
    }
}
