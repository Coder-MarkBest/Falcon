package com.falcon.ipc.core

import com.falcon.ipc.Falcon
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FalconManagerPeerPackagesTest {

    @After fun tearDown() { Falcon.instance?.shutdown() }

    @Test
    fun `peerPackages defaults to empty`() {
        Falcon.init(RuntimeEnvironment.getApplication()) {}
        assertEquals(emptySet<String>(), Falcon.getInstance().peerPackages)
    }

    @Test
    fun `peerPackages DSL wires through`() {
        Falcon.init(RuntimeEnvironment.getApplication()) {
            peerPackages("com.oem.nav", "com.oem.media")
        }
        assertEquals(setOf("com.oem.nav", "com.oem.media"),
                     Falcon.getInstance().peerPackages)
    }

    @Test
    fun `queriesCheckMessage contains expected packages and XML fragment`() {
        val missing = setOf("com.oem.nav", "com.oem.media")
        val msg = FalconManager.buildQueriesErrorMessage(missing)
        assertTrue(msg.contains("com.oem.nav"))
        assertTrue(msg.contains("com.oem.media"))
        assertTrue(msg.contains("<queries>"))
        assertTrue(msg.contains("<package android:name=\"com.oem.nav\""))
    }
}
