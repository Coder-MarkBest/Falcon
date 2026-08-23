package com.falcon.ipc.core

import com.falcon.ipc.dynamic.DynamicPayload
import com.falcon.ipc.dynamic.DynamicRequest
import com.falcon.ipc.dynamic.DynamicResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DynamicRpcRegistryTest {
    @Test
    fun `negotiation selects highest mutually supported version`() {
        val registry = DynamicRpcRegistry()
        registry.registerRoute("navigation/location/get", 1) { DynamicResponse.success() }
        registry.registerRoute("navigation/location/get", 3) { DynamicResponse.success() }

        assertEquals(3, registry.negotiateRoute("navigation/location/get", setOf(2, 3, 4)))
        assertEquals(null, registry.negotiateRoute("navigation/location/get", setOf(2, 4)))
    }

    @Test
    fun `route receives caller metadata and dynamic body`() = runTest {
        val registry = DynamicRpcRegistry()
        registry.registerRoute("navigation/poi/search", 1) { request ->
            DynamicResponse.success(DynamicPayload.build {
                putString("query", request.body.getString("keyword"))
                putString("caller", request.callerPackage)
            })
        }
        val response = registry.invoke(DynamicRequest(
            "navigation/poi/search", 1, "req-1", DynamicPayload.EMPTY,
            DynamicPayload.build { putString("keyword", "charger") }, "com.cluster", ":cluster"
        ))
        assertTrue(response.isSuccessful)
        assertEquals("charger", response.body.getString("query"))
        assertEquals("com.cluster", response.body.getString("caller"))
    }

    @Test
    fun `topic emits without a shared event class`() = runTest {
        val registry = DynamicRpcRegistry()
        registry.registerTopic("navigation/location/changed", 2)
        val received = async {
            registry.topicFlow("navigation/location/changed", 2)!!.first()
        }
        testScheduler.runCurrent()
        assertTrue(registry.publish(
            "navigation/location/changed", 2,
            DynamicPayload.build { putDouble("latitude", 39.9) }
        ))
        assertEquals(39.9, received.await().getDouble("latitude"), 0.0)
    }
}
