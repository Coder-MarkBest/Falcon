package com.falcon.ipc.protocol

import com.falcon.ipc.annotations.MethodId
import org.junit.Assert.*
import org.junit.Test

class MethodIdTest {
    @Test fun `same signature is stable`() {
        assertEquals(
            MethodId.signatureHash("getName", listOf("kotlin.Int")),
            MethodId.signatureHash("getName", listOf("kotlin.Int"))
        )
    }
    @Test fun `overloads differ by param types`() {
        assertNotEquals(
            MethodId.signatureHash("set", listOf("kotlin.Int")),
            MethodId.signatureHash("set", listOf("kotlin.String"))
        )
    }
    @Test fun `different names differ`() {
        assertNotEquals(
            MethodId.signatureHash("a", emptyList()),
            MethodId.signatureHash("b", emptyList())
        )
    }
}
