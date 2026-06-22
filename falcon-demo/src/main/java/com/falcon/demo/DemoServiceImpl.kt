package com.falcon.demo

import com.falcon.ipc.service.IpcReply
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Server-side implementation. Runs in the `:server` process.
 *
 * You implement the interface exactly as if it were a local object — Falcon's
 * generated dispatcher handles unpacking arguments, invoking these methods, and
 * packing return values back over Binder.
 */
class DemoServiceImpl : IDemoService {

    // ── Request / response ─────────────────────────────────────────────

    override suspend fun ping(message: String): String =
        "pong: $message (from PID ${android.os.Process.myPid()})"

    override suspend fun add(a: Int, b: Int): Int = a + b

    override suspend fun getUser(id: Int): DemoUser =
        DemoUser(id = id, name = "User#$id", vip = id % 2 == 0)

    override suspend fun collisionProbe(b: Int, out: Int, env: Int, result: Int, args: Int): Int =
        b + out + env + result + args

    override suspend fun sumEach(values: List<Int>): List<Long> =
        values.map { it.toLong() * 2 }

    override suspend fun usersByName(ids: List<Int>): Map<String, DemoUser> =
        ids.associate { "User#$it" to DemoUser(id = it, name = "User#$it", vip = it % 2 == 0) }

    override suspend fun maybeList(present: Boolean): List<Int>? =
        if (present) listOf(1, 2, 3) else null

    // ── Events ──────────────────────────────────────────────────────────
    // A cold Flow. Falcon collects it once per event key and fans out to all
    // subscribers; it is cancelled when the last subscriber goes away.

    override fun clock(): Flow<Long> = flow {
        var tick = 0L
        while (true) {
            emit(tick++)
            delay(1000)
        }
    }

    // ── Stream ──────────────────────────────────────────────────────────

    override fun download(): Flow<ByteArray> = flow {
        repeat(5) { i ->
            // 4 KB chunk filled with the chunk index — stand-in for real data.
            emit(ByteArray(4 * 1024) { i.toByte() })
            delay(200)
        }
    }

    // ── Async callback ───────────────────────────────────────────────────

    override fun loadAsync(taskId: Int, reply: IpcReply<String>) {
        // In real code this might kick off DB/network work on a background
        // thread and call reply.onResult later. Here we reply immediately.
        if (taskId < 0) {
            reply.onError(1, "taskId must be >= 0")
        } else {
            reply.onResult("result for task #$taskId")
        }
    }
}
