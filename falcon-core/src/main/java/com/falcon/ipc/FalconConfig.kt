package com.falcon.ipc

import com.falcon.ipc.monitor.IpcInterceptor
import com.falcon.ipc.monitor.MonitorLevel
import com.falcon.ipc.security.AccessRule

data class TransportConfig(
    var binderPoolSize: Int = 4,
    var sharedMemoryThreshold: Int = 64 * 1024,
    var maxSharedMemorySize: Int = 32 * 1024 * 1024
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
    var maxConcurrentCalls: Int = 50
)

class FalconConfig {
    var transport = TransportConfig()
    var reconnect = ReconnectConfig()
    var timeout = TimeoutConfig()
    var security = SecurityConfig()
    var monitorLevel: MonitorLevel = MonitorLevel.NONE
    internal val interceptors = mutableListOf<IpcInterceptor>()

    fun transport(block: TransportConfig.() -> Unit) { transport.block() }
    fun reconnect(block: ReconnectConfig.() -> Unit) { reconnect.block() }
    fun timeout(block: TimeoutConfig.() -> Unit) { timeout.block() }
    fun security(block: SecurityConfig.() -> Unit) { security.block() }

    fun addInterceptor(interceptor: IpcInterceptor) {
        interceptors.add(interceptor)
    }
}
