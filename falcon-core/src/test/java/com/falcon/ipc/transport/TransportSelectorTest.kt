package com.falcon.ipc.transport

import org.junit.Assert.*
import org.junit.Test

class TransportSelectorTest {
    @Test fun `below threshold uses binder`() {
        assertFalse(TransportSelector.shouldUseSharedMemory(63 * 1024, 64 * 1024))
    }
    @Test fun `at threshold uses shared memory`() {
        assertTrue(TransportSelector.shouldUseSharedMemory(64 * 1024, 64 * 1024))
    }
    @Test fun `above threshold uses shared memory`() {
        assertTrue(TransportSelector.shouldUseSharedMemory(128 * 1024, 64 * 1024))
    }
}
