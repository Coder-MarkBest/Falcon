package com.falcon.ipc.protocol

import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IpcSerializerTest {

    @Test
    fun `serialize and deserialize Int args`() {
        val args = arrayOf<Any?>(42, 100)
        val bytes = IpcSerializer.serializeArgs(args)
        val result = IpcSerializer.deserializeArgs(bytes, arrayOf(Int::class.java, Int::class.java))
        assertEquals(42, result[0])
        assertEquals(100, result[1])
    }

    @Test
    fun `serialize and deserialize String args`() {
        val args = arrayOf<Any?>("hello", "world")
        val bytes = IpcSerializer.serializeArgs(args)
        val result = IpcSerializer.deserializeArgs(bytes, arrayOf(String::class.java, String::class.java))
        assertEquals("hello", result[0])
        assertEquals("world", result[1])
    }

    @Test
    fun `serialize and deserialize mixed types`() {
        val args = arrayOf<Any?>(42, "hello", 3.14, true, null)
        val bytes = IpcSerializer.serializeArgs(args)
        val result = IpcSerializer.deserializeArgs(bytes, arrayOf(
            Int::class.java, String::class.java, Double::class.java, Boolean::class.java, Any::class.java
        ))
        assertEquals(42, result[0])
        assertEquals("hello", result[1])
        assertEquals(3.14, result[2] as Double, 0.001)
        assertEquals(true, result[3])
        assertNull(result[4])
    }

    @Test
    fun `serialize and deserialize ByteArray`() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val args = arrayOf<Any?>(data)
        val bytes = IpcSerializer.serializeArgs(args)
        val result = IpcSerializer.deserializeArgs(bytes, arrayOf(ByteArray::class.java))
        assertArrayEquals(data, result[0] as ByteArray)
    }

    @Test
    fun `serialize and deserialize result`() {
        val resultValue = "result_data"
        val bytes = IpcSerializer.serializeResult(resultValue)
        val result = IpcSerializer.deserializeResult(bytes, String::class.java)
        assertEquals("result_data", result)
    }

    @Test
    fun `serialize null result`() {
        val bytes = IpcSerializer.serializeResult(null)
        val result = IpcSerializer.deserializeResult(bytes, String::class.java)
        assertNull(result)
    }

    @Test
    fun `empty args`() {
        val args = emptyArray<Any?>()
        val bytes = IpcSerializer.serializeArgs(args)
        val result = IpcSerializer.deserializeArgs(bytes, emptyArray())
        assertEquals(0, result.size)
    }

    @Test
    fun `Long and Float types`() {
        val args = arrayOf<Any?>(123456789L, 2.5f)
        val bytes = IpcSerializer.serializeArgs(args)
        val result = IpcSerializer.deserializeArgs(bytes, arrayOf(Long::class.java, Float::class.java))
        assertEquals(123456789L, result[0])
        assertEquals(2.5f, result[1] as Float, 0.001f)
    }

    @Test
    fun `unsupported type throws instead of silently stringifying`() {
        class Weird(val x: Int)
        assertThrows(IllegalArgumentException::class.java) {
            IpcSerializer.serializeArgs(arrayOf(Weird(1)))
        }
    }

    @Test
    fun `byte array round trips`() {
        val original = byteArrayOf(1, 2, 3, 4, 5)
        val bytes = IpcSerializer.serializeArgs(arrayOf(original))
        val out = IpcSerializer.deserializeArgs(bytes, arrayOf(ByteArray::class.java))
        assertArrayEquals(original, out[0] as ByteArray)
    }
}
