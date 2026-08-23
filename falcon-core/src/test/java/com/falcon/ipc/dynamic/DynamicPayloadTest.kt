package com.falcon.ipc.dynamic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DynamicPayloadTest {
    @Test
    fun `payload round trips platform wire types without shared models`() {
        val payload = DynamicPayload.build {
            putInt("count", 3)
            putString("name", "Falcon")
            putByteArray("data", byteArrayOf(1, 2, 3))
            putStringList("tags", listOf("ipc", "dynamic"))
            putPayload("nested", DynamicPayload.build { putBoolean("ready", true) })
        }

        val decoded = DynamicPayload.fromBundle(payload.toBundle())
        assertEquals(3, decoded.getInt("count"))
        assertEquals("Falcon", decoded.getString("name"))
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded.getByteArray("data"))
        assertEquals(listOf("ipc", "dynamic"), decoded.getStringList("tags"))
        assertEquals(true, decoded.getPayload("nested")?.getBoolean("ready"))
    }

    @Test
    fun `toBundle returns a defensive container copy`() {
        val payload = DynamicPayload.build { putString("key", "original") }
        payload.toBundle().putString("key", "changed")
        assertEquals("original", payload.getString("key"))
        assertFalse(payload.contains("missing"))
    }
}
