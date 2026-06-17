package com.falcon.ipc.protocol

import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BundleCodecTest {
    @Test fun `int round trips`() {
        val b = Bundle(); BundleCodec.putInt(b, "0", 42)
        assertEquals(42, BundleCodec.getInt(b, "0"))
    }
    @Test fun `string round trips`() {
        val b = Bundle(); BundleCodec.putString(b, "0", "hi")
        assertEquals("hi", BundleCodec.getString(b, "0"))
    }
    @Test fun `byte array round trips`() {
        val b = Bundle(); BundleCodec.putByteArray(b, "0", byteArrayOf(1,2,3))
        assertArrayEquals(byteArrayOf(1,2,3), BundleCodec.getByteArray(b, "0"))
    }
}
