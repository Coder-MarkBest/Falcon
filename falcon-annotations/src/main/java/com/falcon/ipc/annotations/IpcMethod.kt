package com.falcon.ipc.annotations

/**
 * Marks a **request-response** method on an [com.falcon.ipc.service.IpcService] interface.
 *
 * ## Signature
 * ```
 * @IpcMethod
 * suspend fun methodName(param1: T1, ...): R   // suspend — recommended
 * // or
 * @IpcMethod
 * fun methodName(param1: T1, ...): R           // non-suspend
 * ```
 *
 * ## Supported types
 * | Category | Types |
 * |----------|-------|
 * | Primitives | Int, Long, Float, Double, Boolean |
 * | Strings/Bytes | String, ByteArray |
 * | Parcelable | Any class implementing `android.os.Parcelable` |
 * | Enum | Any Kotlin enum |
 * | Return only | `Unit` (void method) |
 *
 * **Unsupported types cause a KSP compile-time error.**
 *
 * ## methodId stability
 * The `methodId` is a FNV-1a hash of `methodName + listOf(paramQualifiedNames)`.
 * Renaming a method or changing parameter types is a **breaking API change** —
 * the client and server must be updated together.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class IpcMethod
