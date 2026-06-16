package com.falcon.ipc.service

interface IpcReply<T> {
    fun onResult(data: T)
    fun onError(code: Int, message: String) {}
}
