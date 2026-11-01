package com.falcon.mcp.gateway

import com.falcon.capability.*
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.concurrent.*

/** Bounded, JSON-response Streamable HTTP transport. GET streaming is intentionally unsupported. */
class McpHttpServer(
    private val dispatcher: McpDispatcher,
    private val token: String,
    port: Int = 18765
) : NanoHTTPD("127.0.0.1", port) {
    init { setAsyncRunner(BoundedRunner()) }
    override fun serve(session: IHTTPSession): Response {
        fun response(status: Response.Status, text: String = "", id: String? = null): Response =
            newFixedLengthResponse(status, "application/json", text).apply {
                addHeader("Cache-Control", "no-store"); addHeader("Connection", "close")
                id?.let { addHeader("Mcp-Session-Id", it) }
                if (status == Response.Status.METHOD_NOT_ALLOWED) addHeader("Allow", "POST, DELETE")
            }
        if (session.uri != "/mcp") return response(Response.Status.NOT_FOUND)
        if (session.headers["origin"] != null) return response(Response.Status.FORBIDDEN)
        val auth = session.headers["authorization"].orEmpty()
        if (!MessageDigest.isEqual(auth.toByteArray(), "Bearer $token".toByteArray())) {
            return response(Response.Status.UNAUTHORIZED)
        }
        if (session.method == Method.GET) return response(Response.Status.METHOD_NOT_ALLOWED)
        if (session.method == Method.DELETE) return response(if (dispatcher.close(session.headers["mcp-session-id"]))
            Response.Status.OK else Response.Status.NOT_FOUND)
        if (session.method != Method.POST) return response(Response.Status.METHOD_NOT_ALLOWED)
        val accept = session.headers["accept"].orEmpty()
        if (!accept.contains("application/json") || !accept.contains("text/event-stream")) return response(Response.Status.NOT_ACCEPTABLE)
        if (session.headers["content-type"]?.substringBefore(';')?.trim() != "application/json") return response(Response.Status.UNSUPPORTED_MEDIA_TYPE)
        val length = session.headers["content-length"]?.toIntOrNull()
        if (length == null || length !in 1..32768 || "transfer-encoding" in session.headers) return response(Response.Status.BAD_REQUEST)
        return try {
            val bytes = ByteArray(length)
            var read = 0
            while (read < length) {
                val count = session.inputStream.read(bytes, read, length - read)
                require(count > 0); read += count
            }
            val request = try { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) }
                catch (e: Exception) { return response(Response.Status.BAD_REQUEST, rpcError(-32700, "Parse error")) }
            if (request !is JsonObject) return response(Response.Status.BAD_REQUEST, rpcError(-32600, "Invalid Request"))
            val result = runBlocking { dispatcher.dispatch(request, session.headers["mcp-session-id"], session.headers["mcp-protocol-version"]) }
            response(Response.Status.lookup(result.status) ?: Response.Status.SERVICE_UNAVAILABLE, result.body?.toString().orEmpty(), result.sessionId)
        } catch (e: Exception) {
            response(Response.Status.INTERNAL_ERROR, rpcError(-32603, "Internal error"))
        }
    }
    private fun rpcError(code: Int, message: String) = obj("jsonrpc" to str("2.0"), "id" to JsonNull,
        "error" to obj("code" to num(code), "message" to str(message))).toString()

    private class BoundedRunner : AsyncRunner {
        private val clients = ConcurrentHashMap.newKeySet<ClientHandler>()
        private val pool = ThreadPoolExecutor(2, 8, 30, TimeUnit.SECONDS, ArrayBlockingQueue(16))
        override fun exec(handler: ClientHandler) {
            clients.add(handler)
            try { pool.execute(handler) } catch (e: RejectedExecutionException) { handler.close(); clients.remove(handler) }
        }
        override fun closed(handler: ClientHandler) { clients.remove(handler) }
        override fun closeAll() { clients.forEach { it.close() }; clients.clear(); pool.shutdownNow() }
    }
}
