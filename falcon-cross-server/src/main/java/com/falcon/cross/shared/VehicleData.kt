package com.falcon.cross.shared

import android.os.Parcel
import android.os.Parcelable

/**
 * Automotive-themed Parcelable demonstrating complex cross-app wire types.
 *
 * Independently compiled in both server and client APKs — both copies
 * MUST be byte-identical.
 */
data class VehicleData(
    val vin: String,
    val speedKmh: Float,
    val odometerKm: Double,
    val fuelLevelPct: Int,
    val engineTempC: Float,
    val warningFlags: List<String>,
    val doorStates: Map<String, Boolean>,
    val tirePressure: Map<String, Float>?
) : Parcelable {

    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",
        parcel.readFloat(),
        parcel.readDouble(),
        parcel.readInt(),
        parcel.readFloat(),
        parcel.createStringArrayList() ?: emptyList(),
        readStringBooleanMap(parcel),
        readNullableTirePressure(parcel)
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(vin)
        parcel.writeFloat(speedKmh)
        parcel.writeDouble(odometerKm)
        parcel.writeInt(fuelLevelPct)
        parcel.writeFloat(engineTempC)
        parcel.writeStringList(warningFlags)
        parcel.writeInt(doorStates.size)
        doorStates.forEach { parcel.writeString(it.key); parcel.writeInt(if (it.value) 1 else 0) }
        if (tirePressure == null) {
            parcel.writeInt(-1)
        } else {
            parcel.writeInt(tirePressure.size)
            tirePressure.forEach { parcel.writeString(it.key); parcel.writeFloat(it.value) }
        }
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<VehicleData> {
        private fun readStringBooleanMap(parcel: Parcel): Map<String, Boolean> {
            val size = parcel.readInt()
            return LinkedHashMap<String, Boolean>(size).apply {
                repeat(size) { put(parcel.readString()!!, parcel.readInt() != 0) }
            }
        }

        private fun readNullableTirePressure(parcel: Parcel): Map<String, Float>? {
            val size = parcel.readInt()
            if (size < 0) return null
            return LinkedHashMap<String, Float>(size).apply {
                repeat(size) { put(parcel.readString()!!, parcel.readFloat()) }
            }
        }

        override fun createFromParcel(parcel: Parcel): VehicleData = VehicleData(parcel)
        override fun newArray(size: Int): Array<VehicleData?> = arrayOfNulls(size)
    }
}
