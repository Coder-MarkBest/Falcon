package com.falcon.ipc.core

import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.util.FalconLogger

data class BatchRequest(
    val envelopes: MutableList<IpcEnvelope> = mutableListOf()
) {
    fun add(envelope: IpcEnvelope) {
        envelopes.add(envelope)
    }

    val size: Int get() = envelopes.size
}

data class BatchResponse(
    val responses: List<IpcEnvelope>,
    val totalMs: Long
)

class BatchExecutor(
    private val router: MessageRouter
) {
    fun execute(batch: BatchRequest, callerProcess: String): BatchResponse {
        val start = System.currentTimeMillis()

        val responses = batch.envelopes.map { envelope ->
            try {
                val result = router.handleLocal(envelope, callerProcess)
                IpcEnvelope.response(envelope.requestId, result?.toString()?.toByteArray())
            } catch (e: Exception) {
                IpcEnvelope.error(-1, e.message ?: "Error", envelope.requestId)
            }
        }

        val elapsed = System.currentTimeMillis() - start
        FalconLogger.d("Batch", "Executed ${batch.size} calls in ${elapsed}ms")

        return BatchResponse(responses, elapsed)
    }
}
