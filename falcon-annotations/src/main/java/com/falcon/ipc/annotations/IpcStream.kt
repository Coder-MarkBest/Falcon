package com.falcon.ipc.annotations

/**
 * Marks a **large data stream** method on an [com.falcon.ipc.service.IpcService] interface.
 *
 * ## Signature
 * ```
 * @IpcStream
 * fun streamName(): Flow<ByteArray>
 * ```
 *
 * Semantically identical to [IpcEvent], but the element type is always [ByteArray],
 * making it suitable for chunked large-payload streaming over Binder.
 *
 * Each chunk is delivered as a separate Binder transaction; ensure individual
 * chunks stay within the configured `maxBinderPayloadSize`.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class IpcStream
