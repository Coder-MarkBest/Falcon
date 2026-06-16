package com.falcon.benchmark

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.falcon.benchmark.aidl.IBenchmarkService

class BenchmarkHostService : Service() {

    private val binder = object : IBenchmarkService.Stub() {
        override fun echoString(input: String): String = input

        override fun echoBytes(input: ByteArray): ByteArray = input

        override fun computeSum(from: Int, to: Int): Long {
            var sum = 0L
            for (i in from..to) sum += i
            return sum
        }
    }

    private val messengerHandler = Handler(Looper.getMainLooper()) { msg ->
        val reply = Message.obtain(null, msg.what)
        reply.data = Bundle().apply {
            when (msg.what) {
                MessengerTest.MSG_ECHO_STRING ->
                    putSerializable(MessengerTest.KEY_RESULT,
                        msg.data.getString(MessengerTest.KEY_DATA))
                MessengerTest.MSG_ECHO_BYTES ->
                    putSerializable(MessengerTest.KEY_RESULT,
                        msg.data.getByteArray(MessengerTest.KEY_DATA))
            }
        }
        msg.replyTo?.send(reply)
        true
    }
    private val messenger = Messenger(messengerHandler)

    override fun onBind(intent: Intent): IBinder {
        return when (intent.getStringExtra("transport")) {
            "messenger" -> messenger.binder
            else -> binder
        }
    }
}
