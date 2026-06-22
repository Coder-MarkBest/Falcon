package com.falcon.ipc.annotations

/**
 * Marks an **async callback** method on an [com.falcon.ipc.service.IpcService] interface.
 *
 * ## Signature
 * ```
 * @IpcCallback
 * fun methodName(param1: T1, ..., reply: IpcReply<R>)
 * ```
 *
 * The last parameter must be [com.falcon.ipc.service.IpcReply]<R>, where R is the
 * reply type. The server calls `reply.onResult(data)` when the async operation
 * completes. The call returns immediately (fire-and-forget); the result arrives
 * through the callback.
 *
 * ## Supported types
 * Regular parameters and the reply type follow the same rules as [IpcMethod].
 *
 * ## IpcReply param in methodId
 * The `IpcReply<T>` parameter is **excluded** from the `methodId` hash calculation
 * so the proxy and dispatcher agree on the method identity.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class IpcCallback
