package com.falcon.cross.shared

import com.falcon.ipc.annotations.IpcCallback
import com.falcon.ipc.annotations.IpcEvent
import com.falcon.ipc.annotations.IpcMethod
import com.falcon.ipc.annotations.IpcStream
import com.falcon.ipc.service.IpcReply
import com.falcon.ipc.service.IpcService
import kotlinx.coroutines.flow.Flow

/**
 * Cross-app service contract showcasing every Falcon IPC pattern.
 *
 * Independently compiled in server AND client APK. Both copies MUST be
 * identical — this is the shared-API-library convention.
 *
 * ## Pattern coverage
 * | Annotation    | Methods                          |
 * |---------------|----------------------------------|
 * | @IpcMethod    | ping, add, echoBytes, getVehicleData, filterWarnings, getDoorStates, getTirePressure, getBatchData, batchProcess, findById, maybePing |
 * | @IpcEvent     | vehicleTelemetry, speedAlerts   |
 * | @IpcStream    | firmwareChunks                  |
 * | @IpcCallback  | slowLookup, validateVehicle     |
 */
interface ICrossService : IpcService {

    // ── @IpcMethod — Primitives ────────────────────────────────────────────
    @IpcMethod suspend fun ping(msg: String): String
    @IpcMethod suspend fun add(a: Int, b: Int): Int
    @IpcMethod suspend fun echoBytes(data: ByteArray): ByteArray

    // ── @IpcMethod — Parcelable return ─────────────────────────────────────
    @IpcMethod suspend fun getVehicleData(vin: String): VehicleData

    // ── @IpcMethod — Collections ───────────────────────────────────────────
    @IpcMethod suspend fun filterWarnings(all: List<String>): List<String>
    @IpcMethod suspend fun getDoorStates(): Map<String, Boolean>
    @IpcMethod suspend fun getTirePressure(installed: Boolean): Map<String, Float>?
    @IpcMethod suspend fun getBatchData(id: Int): CrossData
    @IpcMethod suspend fun batchProcess(id: Int, items: List<String>): Map<String, Boolean>

    // ── @IpcMethod — Nullable returns ──────────────────────────────────────
    @IpcMethod suspend fun findById(vin: String): VehicleData?
    @IpcMethod suspend fun maybePing(msg: String, shouldFail: Boolean): String?

    // ── @IpcEvent — Pub/sub ────────────────────────────────────────────────
    @IpcEvent fun vehicleTelemetry(): Flow<VehicleData>
    @IpcEvent fun speedAlerts(): Flow<Int>

    // ── @IpcStream — Chunked data ──────────────────────────────────────────
    @IpcStream fun firmwareChunks(): Flow<ByteArray>

    // ── @IpcCallback — Async ───────────────────────────────────────────────
    @IpcCallback fun slowLookup(query: String, reply: IpcReply<CrossData>)
    @IpcCallback fun validateVehicle(vin: String, reply: IpcReply<VehicleData>)
}
