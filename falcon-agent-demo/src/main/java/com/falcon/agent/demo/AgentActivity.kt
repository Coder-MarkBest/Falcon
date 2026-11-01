package com.falcon.agent.demo

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import java.io.File
import android.net.Uri
import android.os.Bundle
import android.widget.*
import com.falcon.agent.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.*

/** Developer demo: text input, selectable model, real HTTP MCP and cross-APK Falcon calls. */
class AgentActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var running: Job? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("journey-journal", MODE_PRIVATE)
        val keyFile = File(filesDir, "deepseek-key")
        val useDeepSeek = Switch(this).apply {
            text = "使用DeepSeek（输入文本将发送到模型服务）"
            isChecked = keyFile.isFile
        }
        val configure = Button(this).apply { text = "配置DeepSeek Key" }
        // Debug-only text prefill for emulator regression. Does not select a model or auto-execute.
        val debugUtterance = if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            intent.getStringExtra("test_utterance")?.takeIf { it.isNotBlank() && it.length <= 4096 } else null
        val input = EditText(this).apply { setText(debugUtterance ?: "我要回家，有点冷，放点轻松的音乐") }
        val status = TextView(this).apply { textSize = 16f; text = "可选择DeepSeek或离线Mock\n请先启动Cross Server和MCP Gateway。\n上次记录：${prefs.getString("last", "无")}" }
        val run = Button(this).apply { text = "执行场景" }
        val cancel = Button(this).apply { text = "中断任务" }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(24, 32, 24, 24)
            addView(useDeepSeek); addView(configure); addView(input); addView(run); addView(cancel); addView(status)
        }
        setContentView(ScrollView(this).apply { addView(layout) })
        configure.setOnClickListener {
            val field = EditText(this).apply {
                hint = "输入新Key（不会显示已保存的Key）"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                isSaveEnabled = false
                if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            }
            AlertDialog.Builder(this).setTitle("DeepSeek Key").setView(field)
                .setPositiveButton("保存") { _, _ ->
                    val value = field.text.toString().trim()
                    if (value.isNotEmpty()) {
                        openFileOutput("deepseek-key", MODE_PRIVATE).use { it.write(value.toByteArray()) }
                        useDeepSeek.isChecked = true
                        status.text = "DeepSeek Key已保存到应用私有存储。"
                    }
                    field.text.clear()
                }.setNegativeButton("取消", null)
                .setNeutralButton("删除已保存Key") { _, _ ->
                    keyFile.delete(); useDeepSeek.isChecked = false; status.text = "已删除Key，当前使用Mock。"
                }.show()
        }
        run.setOnClickListener {
            if (running?.isActive == true) return@setOnClickListener
            val utterance = input.text.toString()
            val realModel = useDeepSeek.isChecked
            val trace = ExecutionTrace(File(filesDir, "last-execution.jsonl")).apply { reset() }
            running = scope.launch {
                run.isEnabled = false; configure.isEnabled = false; useDeepSeek.isEnabled = false; status.text = "连接Gateway，读取工具目录…"
                try {
                    val bootstrap = withContext(Dispatchers.IO) {
                        contentResolver.call(Uri.parse("content://com.falcon.mcp.gateway.bootstrap"), "connect", null, null)
                            ?: error("Gateway未安装")
                    }
                    val client = HttpMcpToolClient(requireNotNull(bootstrap.getString("endpoint")), requireNotNull(bootstrap.getString("token")))
                    val journal = JourneyJournal { workflow, event ->
                        synchronized(prefs) {
                            val history = Json.parseToJsonElement(prefs.getString("events", "[]")!!).jsonArray
                            val entry = buildJsonObject {
                                put("workflowId", workflow); put("event", event); put("at", System.currentTimeMillis())
                            }
                            check(prefs.edit().putString("last", "$workflow:$event")
                                .putString("events", JsonArray((history + entry).takeLast(150)).toString()).commit())
                        }
                        scope.launch { status.text = "工作流：$workflow\n步骤：$event" }
                    }
                    val result = withContext(Dispatchers.Default) {
                        if (realModel) {
                            val transport = DeepSeekHttpTransport { if (keyFile.isFile) keyFile.readText() else "" }
                            ToolCallingAgent(ToolCallingModel(transport::chat), client, { stage, data ->
                                trace.record(stage, data)
                                if (stage == "deepseek.request") scope.launch { status.text = "DeepSeek自主选择工具：第${data["round"]}轮…" }
                            }).run(utterance, AgentSession("demo_driver", "driver"))
                        } else {
                            val offline = HomeJourneyAgent(MockIntentModel(), trace.tools(client), journal)
                                .run(utterance, AgentSession("demo_driver", "driver"))
                            ToolAgentResult(offline.workflowId, offline.summary, 0, 0)
                        }
                    }
                    trace.record("workflow.result", buildJsonObject { put("workflowId", result.workflowId); put("summary", result.summary); put("model", if (realModel) "DeepSeek" else "Mock") })
                    status.text = "模拟车辆执行结果\n${result.summary}\n\n工作流：${result.workflowId}\n模型：${if (realModel) "DeepSeek" else "Mock"}。车辆能力仍为模拟。"
                } catch (e: CancellationException) {
                    status.text = "任务已中断。已下发动作可能仍然完成，请核实车辆状态。"; throw e
                } catch (e: Exception) {
                    trace.record("agent.error", buildJsonObject { put("message", e.message ?: "unknown") })
                    status.text = "执行失败：${e.message}\n请按错误信息检查模型配置、网络或服务状态。"
                } finally { run.isEnabled = true; configure.isEnabled = true; useDeepSeek.isEnabled = true }
            }
        }
        cancel.setOnClickListener { running?.cancel() }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
