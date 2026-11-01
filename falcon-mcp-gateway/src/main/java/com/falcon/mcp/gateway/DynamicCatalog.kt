package com.falcon.mcp.gateway

import com.falcon.capability.*
import com.falcon.ipc.core.FalconManager
import com.falcon.ipc.dynamic.*
import com.falcon.mcp.provider.FalconJsonCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Identity and grants are installer/OEM configuration, never supplied by tool metadata. */
data class ProviderGrant(val namespace: String, val scopes: Set<String>, val profiles: Set<String>)
data class ToolBinding(val tool: PublishedTool, val invoke: suspend (JsonObject, ToolContext) -> JsonObject)
fun interface ToolCatalog { suspend fun snapshot(): Map<String, ToolBinding> }

class DiscoveredToolCatalog(private val falcon: FalconManager, private val grants: Map<String, ProviderGrant>) : ToolCatalog {
    private val mutex = Mutex()
    override suspend fun snapshot(): Map<String, ToolBinding> = mutex.withLock {
        falcon.refreshPeerDiscovery()
        val result = linkedMapOf<String, ToolBinding>()
        // Query only live, signature-checked peers. No stale catalog is served after a failed refresh.
        val providers = falcon.dynamicProviderCapabilities().filter {
            it.capability.name == ToolProtocol.CATALOG && it.capability.kind == DynamicCapability.Kind.ROUTE
        }.map { it.target }.distinct()
        for (target in providers) {
            val grant = grants[target.packageName] ?: continue
            try {
                val response = falcon.callTo(target, ToolProtocol.CATALOG, setOf(1))
                if (!response.isSuccessful) continue
                val rawCatalog = response.body.getString("catalogJson") ?: error("Missing catalog envelope")
                require(rawCatalog.toByteArray().size <= 50000)
                val catalog = Json.parseToJsonElement(rawCatalog).jsonObject
                val revision = catalog.text("revision")
                require(revision.length in 1..100)
                val items = catalog.getValue("tools").jsonArray
                require(items.size <= 32 && catalog.toString().toByteArray().size <= 50000)
                val staged = linkedMapOf<String, ToolBinding>()
                for (item in items) {
                    val tool = PublishedTool.fromWire(item.jsonObject)
                    require(tool.scope in grant.scopes && tool.profile in grant.profiles)
                    val publicName = grant.namespace + tool.name
                    require(publicName.matches(Regex("[A-Za-z0-9_-]{1,64}")) && publicName !in staged && publicName !in result)
                    val publicDescriptor = JsonObject(tool.descriptor + ("name" to str(publicName)))
                    staged[publicName] = ToolBinding(PublishedTool(publicDescriptor, tool.scope, tool.profile)) { args, context ->
                        val headers = DynamicPayload.build {
                            putString("actor", context.userId); putString("zone", context.zone)
                            putString("workflowId", context.workflowId); putString("actionId", context.actionId)
                        }
                        val reply = falcon.callTo(target, ToolProtocol.INVOKE, setOf(1), headers, FalconJsonCodec.encode(obj(
                            "name" to str(tool.name), "revision" to str(revision), "contractHash" to str(tool.contractHash), "arguments" to args)))
                        val data = FalconJsonCodec.decode(reply.body)
                        if (!reply.isSuccessful) throw ToolException((data["code"] as? JsonPrimitive)?.content ?: "PROVIDER_ERROR", "工具已变化、离线或提供方拒绝执行。")
                        data
                    }
                }
                require(result.size + staged.size <= 64)
                require(JsonArray((result.values + staged.values).map { it.tool.descriptor }).toString().toByteArray().size <= 48000)
                result.putAll(staged)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                com.falcon.ipc.util.FalconLogger.w("MCP", "Catalog unavailable for ${target.packageName}: ${e.javaClass.simpleName}")
            }
        }
        result.toMap()
    }
}
