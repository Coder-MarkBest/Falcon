package com.falcon.cross.server

import com.falcon.cross.shared.CrossData
import com.falcon.cross.shared.ICrossService
import com.falcon.cross.shared.VehicleData
import com.falcon.ipc.service.IpcReply
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class CrossServiceImpl : ICrossService {

    override suspend fun ping(msg: String): String =
        "pong:$msg from server PID=${android.os.Process.myPid()}"

    override suspend fun add(a: Int, b: Int): Int = a + b

    override suspend fun echoBytes(data: ByteArray): ByteArray = data

    override suspend fun getVehicleData(vin: String): VehicleData =
        VehicleData(
            vin = vin, speedKmh = 88.5f, odometerKm = 12345.6,
            fuelLevelPct = 72, engineTempC = 92.3f,
            warningFlags = listOf("low_washer_fluid"),
            doorStates = mapOf("front_left" to false, "front_right" to false,
                "rear_left" to true, "rear_right" to false, "trunk" to false),
            tirePressure = mapOf("front_left" to 2.3f, "front_right" to 2.4f,
                "rear_left" to 2.2f, "rear_right" to 2.3f)
        )

    override suspend fun filterWarnings(all: List<String>): List<String> =
        all.filter { it.startsWith("active_") }

    override suspend fun getDoorStates(): Map<String, Boolean> =
        mapOf("front_left" to false, "front_right" to false,
              "rear_left" to true, "rear_right" to false)

    override suspend fun getTirePressure(installed: Boolean): Map<String, Float>? =
        if (!installed) null
        else mapOf("front_left" to 2.3f, "front_right" to 2.4f,
                   "rear_left" to 2.2f, "rear_right" to 2.3f)

    override suspend fun getBatchData(id: Int): CrossData =
        CrossData(id = id, name = "item#$id", tags = listOf("a", "b", "c"),
                  meta = mapOf("priority" to id, "severity" to id * 2))

    override suspend fun batchProcess(id: Int, items: List<String>): Map<String, Boolean> =
        items.associate { it to (it.length % 2 == 0) }

    override suspend fun findById(vin: String): VehicleData? =
        if (vin.length < 3) null else getVehicleData(vin)

    override suspend fun maybePing(msg: String, shouldFail: Boolean): String? =
        if (shouldFail) null else "pong:$msg"

    // Non-suspend helper for callback methods that can't call suspend functions.
    private fun makeVehicleData(vin: String) = VehicleData(
        vin = vin, speedKmh = 88.5f, odometerKm = 12345.6,
        fuelLevelPct = 72, engineTempC = 92.3f,
        warningFlags = listOf("low_washer_fluid"),
        doorStates = mapOf("front_left" to false, "front_right" to false,
            "rear_left" to true, "rear_right" to false, "trunk" to false),
        tirePressure = mapOf("front_left" to 2.3f, "front_right" to 2.4f,
            "rear_left" to 2.2f, "rear_right" to 2.3f)
    )

    override fun vehicleTelemetry(): Flow<VehicleData> = flow {
        var seq = 1L
        while (true) {
            emit(VehicleData(
                vin = "TELEMETRY$seq", speedKmh = (40 + seq * 3 % 60).toFloat(),
                odometerKm = 12345.6 + seq, fuelLevelPct = ((72 - seq) % 101).toInt().coerceAtLeast(0),
                engineTempC = 92f + (seq % 5).toFloat(),
                warningFlags = if (seq % 7 == 0L) listOf("active_check_engine") else emptyList(),
                doorStates = mapOf("front_left" to (seq % 3 == 0L)),
                tirePressure = mapOf("front_left" to (2.3f + seq * 0.01f))
            ))
            seq++; delay(500)
        }
    }

    override fun speedAlerts(): Flow<Int> = flow {
        var speed = 60
        while (true) { speed += 15; if (speed > 120) speed = 40; emit(speed); delay(800) }
    }

    override fun firmwareChunks(): Flow<ByteArray> = flow {
        repeat(10) { i -> emit(ByteArray(8 * 1024) { i.toByte() }); delay(150) }
    }

    override fun slowLookup(query: String, reply: IpcReply<CrossData>) {
        if (query.isEmpty()) { reply.onError(1, "empty query"); return }
        reply.onResult(CrossData(id = query.hashCode(), name = query,
            tags = listOf(query.take(3)), meta = mapOf("length" to query.length)))
    }

    override fun validateVehicle(vin: String, reply: IpcReply<VehicleData>) {
        when {
            vin.isBlank() -> reply.onError(2, "VIN must not be blank")
            vin.length < 5 -> reply.onError(3, "VIN too short (need 5+, got ${vin.length})")
            else -> reply.onResult(makeVehicleData(vin))
        }
    }
}
