package com.falcon.agent

import com.falcon.capability.*
import org.junit.Assert.*
import org.junit.Test

class DeepSeekHttpTransportTest {
    private fun response(content: String, finish: String = "stop") = obj("choices" to kotlinx.serialization.json.JsonArray(listOf(
        obj("finish_reason" to str(finish), "message" to obj("content" to str(content)))
    ))).toString()
    @Test fun `valid structured content decoded`() {
        assertEquals(obj("goHome" to bool(true)), DeepSeekHttpTransport.decode(response("{\"goHome\":true}")))
    }
    @Test fun `reject truncated blank malformed and nonobject results`() {
        listOf(response("{}", "length"), response(" "), response("not json"), response("[]"), "{}").forEach {
            val error = assertThrows(ToolException::class.java) { DeepSeekHttpTransport.decode(it) }
            assertEquals("INVALID_MODEL_OUTPUT", error.code)
        }
    }
    @Test fun `error mapping never includes remote response body`() {
        assertEquals("MODEL_AUTH_ERROR", DeepSeekHttpTransport.httpError(401).code)
        assertEquals("MODEL_BALANCE_ERROR", DeepSeekHttpTransport.httpError(402).code)
        assertEquals("MODEL_RATE_LIMITED", DeepSeekHttpTransport.httpError(429).code)
    }
    @Test fun `request has no credentials and uses JSON mode`() {
        val request = DeepSeekHttpTransport.requestBody(DeepSeekRequest("json", "回家"))
        assertFalse(request.toString().contains("Authorization"))
        assertEquals(obj("type" to str("json_object")), request["response_format"])
    }
}
