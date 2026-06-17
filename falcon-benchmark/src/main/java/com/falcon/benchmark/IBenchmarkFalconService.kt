package com.falcon.benchmark

import com.falcon.ipc.annotations.IpcMethod
import com.falcon.ipc.service.IpcService

/**
 * Sample IpcService interface used to exercise KSP DispatcherGenerator in the benchmark module.
 */
interface IBenchmarkFalconService : IpcService {
    @IpcMethod
    fun echoString(input: String): String

    @IpcMethod
    fun computeSum(from: Int, to: Int): Long

    @IpcMethod
    fun echoBytes(data: ByteArray): ByteArray
}
