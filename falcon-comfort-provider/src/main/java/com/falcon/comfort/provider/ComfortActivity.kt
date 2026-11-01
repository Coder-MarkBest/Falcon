package com.falcon.comfort.provider

import android.app.Activity
import android.os.Bundle
import android.widget.*

class ComfortActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ComfortApp
        val status = TextView(this).apply { textSize = 20f }
        fun refresh() { status.text = "独立舒适性应用（模拟数据）\n注册状态：" + getSharedPreferences("tools", MODE_PRIVATE).getBoolean("enabled", false) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 40, 32, 32)
            addView(status)
            addView(Button(this@ComfortActivity).apply { text = "注册空气质量工具"; setOnClickListener { app.register(); refresh() } })
            addView(Button(this@ComfortActivity).apply { text = "注销空气质量工具"; setOnClickListener { app.unregister(); refresh() } })
        })
        refresh()
    }
}
