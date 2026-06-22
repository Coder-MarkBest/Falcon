package com.falcon.ipc

import com.falcon.ipc.monitor.IpcInterceptor
import com.falcon.ipc.monitor.MonitorLevel
import com.falcon.ipc.security.AccessRule

data class TransportConfig(
    var binderPoolSize: Int = 4,
    var maxBinderPayloadSize: Int = 256 * 1024,
    /**
     * Watchdog for a single blocking invoke(): if a peer doesn't return within this many
     * ms, invoke() returns TRANSPORT_ERROR instead of pinning the calling thread.
     *
     * **0 = off (default)** — the hot path runs directly with no extra thread hop.
     * Set > 0 only on paths where an unresponsive peer must not block the caller; each
     * guarded call then runs on a watchdog thread (one context switch + Future overhead).
     */
    var invokeTimeoutMs: Long = 0
)

data class ReconnectConfig(
    var enabled: Boolean = true,
    var initialDelayMs: Long = 500,
    var maxDelayMs: Long = 30_000,
    var maxRetries: Int = -1
)

data class TimeoutConfig(
    var connectMs: Long = 3_000,
    var callMs: Long = 5_000,
    var streamChunkMs: Long = 10_000
)

data class SecurityConfig(
    var signatureVerification: Boolean = true,
    var accessRules: Map<String, AccessRule> = emptyMap(),
    var rateLimitPerSecond: Int = 1000,
    var maxConcurrentCalls: Int = 50,
    var trustedSignatures: Set<String> = emptySet()
)

data class EventConfig(
    /** Channel capacity for cross-process event delivery. */
    var bufferCapacity: Int = 64,
    /** What to do when the buffer is full: SUSPEND, DROP_OLDEST, DROP_LATEST. */
    var onOverflow: kotlinx.coroutines.channels.BufferOverflow =
        kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
)

class FalconConfig {
    var transport = TransportConfig()
    var reconnect = ReconnectConfig()
    var timeout = TimeoutConfig()
    var security = SecurityConfig()
    var event = EventConfig()
    var monitorLevel: MonitorLevel = MonitorLevel.NONE
    /** When true, calling the blocking getService() on the main thread throws instead of warning. */
    var strictThreadPolicy: Boolean = false
    /** Other app packages this app communicates with via Falcon (multi-APK). */
    var peerPackages: Set<String> = emptySet()
    fun peerPackages(vararg packages: String) { peerPackages = packages.toSet() }
    internal val interceptors = mutableListOf<IpcInterceptor>()
    internal val generatedRegistries = mutableListOf<com.falcon.ipc.runtime.FalconGeneratedRegistry>()

    fun transport(block: TransportConfig.() -> Unit) { transport.block() }
    fun reconnect(block: ReconnectConfig.() -> Unit) { reconnect.block() }
    fun timeout(block: TimeoutConfig.() -> Unit) { timeout.block() }
    fun security(block: SecurityConfig.() -> Unit) { security.block() }
    fun event(block: EventConfig.() -> Unit) { event.block() }

    fun addInterceptor(interceptor: IpcInterceptor) {
        interceptors.add(interceptor)
    }

    fun generated(registry: com.falcon.ipc.runtime.FalconGeneratedRegistry) {
        generatedRegistries.add(registry)
    }
}
