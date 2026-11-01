package com.falcon.mcp.gateway

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class GatewayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            textSize = 18f; setPadding(32, 48, 32, 32)
            text = (application as GatewayApp).startupError ?: "Falcon MCP Gateway\n\n监听：127.0.0.1:18765/mcp\n能力：13个原子工具\n授权：演示主驾用户\n\n请先启动Falcon Cross Server，再打开Falcon Agent Demo。\n\n此为开发演示，进程被系统回收后需要重新连接。"
        })
    }
}
