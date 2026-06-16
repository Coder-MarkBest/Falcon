package com.falcon.ipc.core

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DiagnosticsManagerTest {

    private lateinit var diag: DiagnosticsManager

    @Before
    fun setup() {
        diag = DiagnosticsManager()
    }

    @Test
    fun `disabled by default`() {
        assertFalse(diag.isEnabled())
    }

    @Test
    fun `does not record when disabled`() {
        diag.record(DiagnosticEntry(System.currentTimeMillis(), "svc", "method", 10, true, "BINDER", "req-1"))
        assertEquals(0, diag.getEntryCount())
    }

    @Test
    fun `records when enabled`() {
        diag.enable()
        diag.record(DiagnosticEntry(System.currentTimeMillis(), "svc", "method", 10, true, "BINDER", "req-1"))
        assertEquals(1, diag.getEntryCount())
    }

    @Test
    fun `getRecentEntries returns latest`() {
        diag.enable()
        repeat(10) { i ->
            diag.record(DiagnosticEntry(System.currentTimeMillis(), "svc", "m$i", i.toLong(), true, "BINDER", "req-$i"))
        }
        val recent = diag.getRecentEntries(3)
        assertEquals(3, recent.size)
        assertEquals("m9", recent.last().method)
    }

    @Test
    fun `getStats aggregates correctly`() {
        diag.enable()
        diag.record(DiagnosticEntry(1, "svc", "add", 10, true, "BINDER", "r1"))
        diag.record(DiagnosticEntry(2, "svc", "add", 20, true, "BINDER", "r2"))
        diag.record(DiagnosticEntry(3, "svc", "add", 30, false, "BINDER", "r3"))

        val stats = diag.getStats()
        val addStats = stats["svc#add"]!!
        assertEquals(3, addStats.callCount)
        assertEquals(2, addStats.successCount)
        assertEquals(1, addStats.failCount)
        assertEquals(20.0, addStats.avgLatencyMs, 0.001)
    }

    @Test
    fun `clear removes all entries`() {
        diag.enable()
        diag.record(DiagnosticEntry(1, "svc", "m", 10, true, "BINDER", "r1"))
        diag.clear()
        assertEquals(0, diag.getEntryCount())
    }

    @Test
    fun `disable stops recording`() {
        diag.enable()
        diag.record(DiagnosticEntry(1, "svc", "m", 10, true, "BINDER", "r1"))
        diag.disable()
        diag.record(DiagnosticEntry(2, "svc", "m", 10, true, "BINDER", "r2"))
        assertEquals(1, diag.getEntryCount())
    }
}
