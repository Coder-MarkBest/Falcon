package com.falcon.ipc.monitor

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MonitorFacadeTest {

    private lateinit var monitor: MonitorFacade

    @Before
    fun setup() {
        monitor = MonitorFacade()
    }

    @Test
    fun `NONE level records nothing`() {
        monitor.setLevel(MonitorLevel.NONE)
        monitor.recordCall("svc", "method", true, 10)
        assertTrue(monitor.getStats().isEmpty())
    }

    @Test
    fun `BASIC level records call count`() {
        monitor.setLevel(MonitorLevel.BASIC)
        monitor.recordCall("svc", "method", true, 10)
        monitor.recordCall("svc", "method", false, 20)

        val stats = monitor.getStats()
        assertEquals(1, stats.size)
        assertEquals(2L, stats[0].callCount)
        assertEquals(1L, stats[0].successCount)
        assertEquals(1L, stats[0].failCount)
    }

    @Test
    fun `DETAILED level records latency`() {
        monitor.setLevel(MonitorLevel.DETAILED)
        monitor.recordCall("svc", "method", true, 10)
        monitor.recordCall("svc", "method", true, 20)
        monitor.recordCall("svc", "method", true, 30)

        val stats = monitor.getStats()
        assertEquals(20f, stats[0].avgLatencyMs, 0.1f)
    }

    @Test
    fun `setMonitorConfig dynamically enables`() {
        monitor.setLevel(MonitorLevel.NONE)
        assertTrue(monitor.getStats().isEmpty())

        monitor.setMonitorConfig { enableCallStats = true }
        monitor.recordCall("svc", "method", true, 5)
        assertEquals(1, monitor.getStats().size)

        monitor.setMonitorConfig { enableCallStats = false }
        monitor.recordCall("svc", "method2", true, 5)
        assertEquals(1, monitor.getStats().size)
    }

    @Test
    fun `stats separated by service and method`() {
        monitor.setLevel(MonitorLevel.BASIC)
        monitor.recordCall("svcA", "method1", true, 10)
        monitor.recordCall("svcA", "method2", true, 20)
        monitor.recordCall("svcB", "method1", true, 30)

        val stats = monitor.getStats()
        assertEquals(3, stats.size)
    }
}
