package com.falcon.ipc.core

import com.falcon.ipc.dynamic.DynamicCapability
import com.falcon.ipc.dynamic.DynamicPayload
import com.falcon.ipc.dynamic.DynamicRequest
import com.falcon.ipc.dynamic.DynamicResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.ConcurrentHashMap

internal class DynamicRpcRegistry {
    private val routes = ConcurrentHashMap<String, ConcurrentHashMap<Int, suspend (DynamicRequest) -> DynamicResponse>>()
    private val topics = ConcurrentHashMap<String, ConcurrentHashMap<Int, MutableSharedFlow<DynamicPayload>>>()

    fun registerRoute(route: String, version: Int, handler: suspend (DynamicRequest) -> DynamicResponse) {
        validateName(route, "route")
        require(version > 0) { "Dynamic route version must be positive" }
        val previous = routes.getOrPut(route) { ConcurrentHashMap() }.putIfAbsent(version, handler)
        require(previous == null) { "Dynamic route already registered: $route v$version" }
    }

    fun registerTopic(topic: String, version: Int) {
        validateName(topic, "topic")
        require(version > 0) { "Dynamic topic version must be positive" }
        topics.getOrPut(topic) { ConcurrentHashMap() }
            .putIfAbsent(version, MutableSharedFlow(extraBufferCapacity = 64))
    }

    fun negotiateRoute(route: String, supportedVersions: Collection<Int>): Int? =
        negotiate(routes[route]?.keys, supportedVersions)

    fun negotiateTopic(topic: String, supportedVersions: Collection<Int>): Int? =
        negotiate(topics[topic]?.keys, supportedVersions)

    suspend fun invoke(request: DynamicRequest): DynamicResponse =
        routes[request.route]?.get(request.version)?.invoke(request)
            ?: DynamicResponse.error(404, "Route or version not found: ${request.route} v${request.version}")

    fun topicFlow(topic: String, version: Int): Flow<DynamicPayload>? = topics[topic]?.get(version)

    fun publish(topic: String, version: Int, payload: DynamicPayload): Boolean =
        topics[topic]?.get(version)?.tryEmit(payload) ?: false

    fun capabilities(): List<DynamicCapability> = buildList {
        routes.forEach { (name, versions) ->
            add(DynamicCapability(name, DynamicCapability.Kind.ROUTE, versions.keys.toSet()))
        }
        topics.forEach { (name, versions) ->
            add(DynamicCapability(name, DynamicCapability.Kind.TOPIC, versions.keys.toSet()))
        }
    }.sortedWith(compareBy({ it.kind.name }, { it.name }))

    fun clear() {
        routes.clear()
        topics.clear()
    }

    private fun negotiate(available: Set<Int>?, supported: Collection<Int>): Int? =
        available?.intersect(supported.toSet())?.maxOrNull()

    private fun validateName(value: String, kind: String) {
        require(value.isNotBlank()) { "Dynamic $kind must not be blank" }
        require(value.length <= 256) { "Dynamic $kind is too long" }
        require(!value.startsWith("__falcon_")) { "Dynamic $kind uses a reserved prefix" }
    }
}
