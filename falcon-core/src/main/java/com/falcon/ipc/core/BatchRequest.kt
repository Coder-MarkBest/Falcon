package com.falcon.ipc.core

import com.falcon.ipc.protocol.IpcEnvelope
import com.falcon.ipc.protocol.IpcSerializer
import com.falcon.ipc.util.FalconLogger

class BatchRequest {
    private val _envelopes = mutableListOf<IpcEnvelope>()
    val envelopes: List<IpcEnvelope> get() = _envelopes.toList()
    val size: Int get() = _envelopes.size

    fun add(envelope: IpcEnvelope) {
        _envelopes.add(envelope)
    }
}

data class BatchResponse(
    val responses: List<IpcEnvelope>,
    val totalMs: Long
)

class BatchExecutor(
    private val router: MessageRouter
) {
    fun execute(batch: BatchRequest, callerProcess: String, callerPid: Int = 0): BatchResponse {
        val start = System.currentTimeMillis()

        val responses = batch.envelopes.map { envelope ->
            try {
                val result = router.handleLocal(envelope, callerProcess, callerPid)
                IpcEnvelope.response(envelope.requestId, IpcSerializer.serializeResult(result))
            } catch (e: Exception) {
                IpcEnvelope.error(-1, e.message ?: "Error", envelope.requestId)
            }
        }

        val elapsed = System.currentTimeMillis() - start
        FalconLogger.d("Batch", "Executed ${batch.size} calls in ${elapsed}ms")

        return BatchResponse(responses, elapsed)
    }
}
