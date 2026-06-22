package com.falcon.ipc.annotations

/**
 * Marks a **publish-subscribe event** method on an [com.falcon.ipc.service.IpcService] interface.
 *
 * ## Signature
 * ```
 * @IpcEvent
 * fun eventName(): Flow<T>   // T must be Parcelable-compatible
 * ```
 *
 * The returned [kotlinx.coroutines.flow.Flow] is collected lazily — the server
 * starts producing events when the first subscriber collects, and stops when the
 * last subscriber cancels (ref-counted).
 *
 * ## Supported element types
 * Same as [IpcMethod]: primitives, String, ByteArray, Parcelable, Enum.
 *
 * ## Delivery ordering
 * Events are dispatched via a shared thread pool. There is **no guarantee**
 * of ordering between events. For ordered delivery, use a single-thread
 * collector on the subscriber side.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class IpcEvent
