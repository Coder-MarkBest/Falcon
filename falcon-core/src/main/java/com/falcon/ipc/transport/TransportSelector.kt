package com.falcon.ipc.transport

object TransportSelector {
    fun shouldUseSharedMemory(payloadSize: Int, threshold: Int): Boolean =
        payloadSize >= threshold
}
