package com.falcon.ipc.dynamic

import com.falcon.ipc.Falcon
import com.falcon.ipc.protocol.IpcException
import com.falcon.ipc.util.ProcessUtils
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DynamicTargetTest {
    @After fun cleanup() { Falcon.instance?.shutdown() }
    @Test fun `explicit remote target cannot fall back to same-named local route`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val falcon = Falcon.init(context) { timeout { connectMs = 20 } }
        var invoked = false
        falcon.route("demo/run") { invoked = true; DynamicResponse.success() }
        try {
            falcon.callTo(DynamicTarget("com.other", "com.other:server"), "demo/run")
            fail("Must not invoke local route")
        } catch (_: IpcException) { }
        assertFalse(invoked)
        falcon.callTo(DynamicTarget(context.packageName, ProcessUtils.getCurrentProcessName(context)), "demo/run")
        assertTrue(invoked)
    }
    @Test fun `provider discovery retains local identity`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val falcon = Falcon.init(context)
        falcon.route("demo/run") { DynamicResponse.success() }
        val entry = falcon.dynamicProviderCapabilities().single { it.capability.name == "demo/run" }
        assertEquals(context.packageName, entry.target.packageName)
        assertEquals(ProcessUtils.getCurrentProcessName(context), entry.target.processName)
    }
}
