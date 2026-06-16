package com.falcon.ipc.core

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ServiceVersionTest {

    private lateinit var registry: ServiceVersionRegistry

    @Before
    fun setup() {
        registry = ServiceVersionRegistry()
    }

    @Test
    fun `parse version string`() {
        val v = ServiceVersion.parse("2.3")
        assertEquals(2, v.major)
        assertEquals(3, v.minor)
    }

    @Test
    fun `parse invalid version returns default`() {
        val v = ServiceVersion.parse("invalid")
        assertEquals(1, v.major)
        assertEquals(0, v.minor)
    }

    @Test
    fun `version comparison`() {
        val v1 = ServiceVersion(1, 0)
        val v2 = ServiceVersion(1, 1)
        val v3 = ServiceVersion(2, 0)

        assertTrue(v1 < v2)
        assertTrue(v2 < v3)
        assertTrue(v1 < v3)
    }

    @Test
    fun `default version for unregistered service`() {
        assertEquals(ServiceVersion.DEFAULT, registry.getVersion("unknown"))
    }

    @Test
    fun `compatible when same major and server minor gte client`() {
        registry.register("svc", ServiceVersion(1, 2))
        assertTrue(registry.isCompatible("svc", ServiceVersion(1, 0)))
        assertTrue(registry.isCompatible("svc", ServiceVersion(1, 1)))
        assertTrue(registry.isCompatible("svc", ServiceVersion(1, 2)))
    }

    @Test
    fun `incompatible when different major version`() {
        registry.register("svc", ServiceVersion(2, 0))
        assertFalse(registry.isCompatible("svc", ServiceVersion(1, 0)))
    }

    @Test
    fun `incompatible when client minor gt server minor`() {
        registry.register("svc", ServiceVersion(1, 0))
        assertFalse(registry.isCompatible("svc", ServiceVersion(1, 2)))
    }

    @Test
    fun `negotiate version picks lower`() {
        registry.register("svc", ServiceVersion(1, 3))
        val negotiated = registry.negotiateVersion("svc", ServiceVersion(1, 1))
        assertEquals(ServiceVersion(1, 1), negotiated)
    }

    @Test
    fun `toString formats correctly`() {
        assertEquals("2.5", ServiceVersion(2, 5).toString())
    }
}
