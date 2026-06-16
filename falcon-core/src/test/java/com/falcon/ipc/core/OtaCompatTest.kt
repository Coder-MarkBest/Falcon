package com.falcon.ipc.core

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OtaCompatTest {

    private lateinit var ota: OtaCompatManager

    @Before
    fun setup() {
        ota = OtaCompatManager()
    }

    @Test
    fun `local version registered`() {
        ota.registerLocalVersion("svc", ServiceVersion(1, 0))
        val info = ota.getVersionInfo("svc")
        assertEquals(ServiceVersion(1, 0), info.localVersion)
    }

    @Test
    fun `compatible when same version`() {
        ota.registerLocalVersion("svc", ServiceVersion(1, 0))
        ota.registerRemoteVersion("svc", ServiceVersion(1, 0))
        assertTrue(ota.getVersionInfo("svc").isCompatible)
    }

    @Test
    fun `compatible when same major different minor`() {
        ota.registerLocalVersion("svc", ServiceVersion(1, 2))
        ota.registerRemoteVersion("svc", ServiceVersion(1, 0))
        assertTrue(ota.getVersionInfo("svc").isCompatible)
    }

    @Test
    fun `incompatible when different major`() {
        ota.registerLocalVersion("svc", ServiceVersion(2, 0))
        ota.registerRemoteVersion("svc", ServiceVersion(1, 0))
        assertFalse(ota.getVersionInfo("svc").isCompatible)
    }

    @Test
    fun `needsDowngrade when local gt remote`() {
        ota.registerLocalVersion("svc", ServiceVersion(1, 3))
        ota.registerRemoteVersion("svc", ServiceVersion(1, 1))
        assertTrue(ota.getVersionInfo("svc").needsDowngrade)
    }

    @Test
    fun `downgrade callback fires`() {
        var downgradedService: String? = null
        ota.onDowngrade { serviceKey, _ -> downgradedService = serviceKey }

        ota.registerLocalVersion("svc", ServiceVersion(1, 3))
        ota.registerRemoteVersion("svc", ServiceVersion(1, 1))

        assertEquals("svc", downgradedService)
    }

    @Test
    fun `no remote version means compatible`() {
        ota.registerLocalVersion("svc", ServiceVersion(1, 0))
        val info = ota.getVersionInfo("svc")
        assertTrue(info.isCompatible)
        assertFalse(info.needsDowngrade)
    }
}
