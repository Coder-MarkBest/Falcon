package com.falcon.demo

import com.falcon.ipc.annotations.IpcCallback
import com.falcon.ipc.annotations.IpcEvent
import com.falcon.ipc.annotations.IpcMethod
import com.falcon.ipc.annotations.IpcStream
import com.falcon.ipc.service.IpcReply
import com.falcon.ipc.service.IpcService
import kotlinx.coroutines.flow.Flow

/**
 * The demo cross-process service contract.
 *
 * One interface exercises every Falcon pattern. KSP reads the annotations and
 * generates `DemoService_Proxy` (client side) and `IDemoService_Dispatcher`
 * (server side) at compile time — no reflection, no manual AIDL.
 *
 * Any interface that `: IpcService` and has ≥1 annotated method is auto-detected;
 * there is no extra registration annotation to add.
 */
interface IDemoService : IpcService {

    // ── 1. Request / response (@IpcMethod) ─────────────────────────────
    // suspend is recommended: the proxy still does a blocking Binder call,
    // but suspend lets callers stay on a coroutine without wrapping.

    /** Echo a string back — the "hello world" of IPC. */
    @IpcMethod
    suspend fun ping(message: String): String

    /** Multiple primitive args, primitive return. */
    @IpcMethod
    suspend fun add(a: Int, b: Int): Int

    /** Parcelable return value. */
    @IpcMethod
    suspend fun getUser(id: Int): DemoUser

    /** Regression guard: params named like the generator's internal locals (b, out, env, result, args). */
    @IpcMethod
    suspend fun collisionProbe(b: Int, out: Int, env: Int, result: Int, args: Int): Int

    /** List of primitives across the wire. */
    @IpcMethod
    suspend fun sumEach(values: List<Int>): List<Long>

    /** Map with a Parcelable value across the wire. */
    @IpcMethod
    suspend fun usersByName(ids: List<Int>): Map<String, DemoUser>

    /** Nullable collection — exercises the null sentinel (null vs empty). */
    @IpcMethod
    suspend fun maybeList(present: Boolean): List<Int>?

    // ── 2. Publish / subscribe events (@IpcEvent) ──────────────────────
    // Returns a Flow. The server's Flow is collected lazily and ref-counted:
    // it starts on the first subscriber and stops when the last one cancels.

    /** Emits an incrementing counter once per second while subscribed. */
    @IpcEvent
    fun clock(): Flow<Long>

    // ── 3. Large data stream (@IpcStream) ──────────────────────────────
    // Same machinery as @IpcEvent but the element type is always ByteArray,
    // intended for chunked payloads. Each chunk is one Binder transaction.

    /** Streams [chunks] byte-array chunks of [chunkSize] bytes each. */
    @IpcStream
    fun download(): Flow<ByteArray>

    // ── 4. Async callback (@IpcCallback) ───────────────────────────────
    // Fire-and-forget call; the result arrives later via IpcReply<T>.
    // Useful when the server needs to do async work before replying.

    /** Simulates an async lookup; replies through [reply] when done. */
    @IpcCallback
    fun loadAsync(taskId: Int, reply: IpcReply<String>)
}
