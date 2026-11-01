package com.falcon.agent

import com.falcon.capability.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class IntentModelTest {
    @Test fun `mock recognizes supported request without any network`() = runTest {
        assertEquals(CabinIntent(true, true, true), MockIntentModel().understand("我要回家，有点冷，放点轻松的音乐"))
        assertEquals(CabinIntent(true, false, false), MockIntentModel().understand("我要回家"))
    }
    @Test fun `mock does not act on negated requests`() = runTest {
        try { MockIntentModel().understand("不要回家") ; fail() }
        catch (e: ToolException) { assertEquals("NEEDS_CLARIFICATION", e.code) }
    }
    @Test fun `deepseek interface validates injected transport output`() = runTest {
        val model = DeepSeekIntentModel { obj("goHome" to bool(true), "slightlyWarmer" to bool(false), "relaxingMusic" to bool(false)) }
        assertTrue(model.understand("回家").goHome)
        try {
            DeepSeekIntentModel { obj("goHome" to str("yes")) }.understand("回家"); fail()
        } catch (e: ToolException) { assertEquals("INVALID_MODEL_OUTPUT", e.code) }
    }
    @Test fun `empty model intent cannot start a workflow`() = runTest {
        val model = DeepSeekIntentModel { obj("goHome" to bool(false), "slightlyWarmer" to bool(false), "relaxingMusic" to bool(false)) }
        var calls = 0
        val tools = object : ToolClient {
            override suspend fun call(name: String, arguments: kotlinx.serialization.json.JsonObject, context: ToolContext): kotlinx.serialization.json.JsonObject {
                calls++; return obj()
            }
        }
        try { HomeJourneyAgent(model, tools).run("其他请求", AgentSession("demo_driver", "driver")); fail() }
        catch (e: ToolException) { assertEquals("UNSUPPORTED_INTENT", e.code) }
        assertEquals(0, calls)
    }

}
