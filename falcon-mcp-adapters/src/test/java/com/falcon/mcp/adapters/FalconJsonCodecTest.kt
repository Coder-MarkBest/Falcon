package com.falcon.mcp.adapters

import com.falcon.capability.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FalconJsonCodecTest {
    @Test fun `nested object arrays and platform primitives round trip`() {
        val input = obj("items" to JsonArray(listOf(obj("title" to str("轻松音乐"), "playable" to bool(true)))),
            "temperature" to num(23.5), "version" to num(42), "nullable" to JsonNull)
        assertEquals(input, FalconJsonCodec.decode(FalconJsonCodec.encode(input)))
    }
    @Test fun `excessive nesting and reserved marker are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { FalconJsonCodec.encode(obj("_falcon_json_array" to bool(true))) }
        var nested = obj()
        repeat(10) { nested = obj("child" to nested) }
        assertThrows(IllegalArgumentException::class.java) { FalconJsonCodec.encode(nested) }
    }
}
