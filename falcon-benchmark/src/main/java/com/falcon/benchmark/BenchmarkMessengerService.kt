package com.falcon.benchmark

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger

/**
 * Dedicated Messenger host service — separate from [BenchmarkHostService] so Android
 * doesn't deduplicate the AIDL and Messenger binds to the same component name.
 */
class BenchmarkMessengerService : Service() {

    private val handler = Handler(Looper.getMainLooper()) { msg ->
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
    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent): IBinder = messenger.binder
}
