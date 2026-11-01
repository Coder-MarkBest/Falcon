package com.falcon.mcp.adapters

import com.falcon.capability.*
import com.falcon.ipc.Falcon
import com.falcon.ipc.dynamic.DynamicTarget
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FalconToolClientReadinessTest {
    private var manager: com.falcon.ipc.core.FalconManager? = null
    @After fun cleanup() { manager?.shutdown() }
    @Test fun `readiness deadline is distinct from an unknown dispatched outcome`() = runBlocking {
        val manager = Falcon.init(RuntimeEnvironment.getApplication()) { timeout { connectMs = 30 } }.also { this@FalconToolClientReadinessTest.manager = it }
        val tools = FalconToolClient(manager, DynamicTarget("com.missing", "com.missing:server"))
        try {
            tools.call("media_play", obj("contentRef" to str("demo:relaxing")), ToolContext("demo_driver", "driver", "workflow", "action"))
            fail()
        } catch (e: ToolException) { assertEquals("PROVIDER_NOT_READY", e.code) }
    }
}
