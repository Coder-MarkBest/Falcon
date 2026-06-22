package com.falcon.ipc.core

import android.os.Looper
import com.falcon.ipc.Falcon
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class GetServiceThreadTest {

    @After fun tearDown() { Falcon.instance?.shutdown() }

    private fun initFalcon(strict: Boolean) {
        Falcon.init(RuntimeEnvironment.getApplication()) {
            strictThreadPolicy = strict
        }
    }

    @Test
    fun `blocking getService on main thread with strict policy throws`() {
        initFalcon(strict = true)
        // Robolectric runs tests on the main looper thread by default.
        assert(Looper.myLooper() == Looper.getMainLooper())
        assertThrows(IllegalStateException::class.java) {
            Falcon.getInstance().getService(com.falcon.ipc.service.IpcService::class)
        }
    }

    @Test
    fun `blocking getService with no peers returns null off strict`() {
        initFalcon(strict = false)
        // No peers registered -> null, and (on main thread) only a warning.
        assertNull(Falcon.getInstance().getService(com.falcon.ipc.service.IpcService::class))
    }
}
