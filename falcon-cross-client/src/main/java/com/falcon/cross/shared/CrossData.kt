package com.falcon.cross.shared

import android.os.Parcel
import android.os.Parcelable

/**
 * Cross-APK wire type. Independently compiled in both server and client APKs
 * — both copies MUST be byte-identical (the shared-API-library convention).
 */
data class CrossData(
    val id: Int,
    val name: String,
    val tags: List<String>,
    val meta: Map<String, Int>?
) : Parcelable {

    constructor(parcel: Parcel) : this(
        parcel.readInt(),
        parcel.readString() ?: "",
        parcel.createStringArrayList() ?: emptyList(),
        readNullableMap(parcel)
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(id)
        parcel.writeString(name)
        parcel.writeStringList(tags)
        if (meta == null) { parcel.writeInt(-1) } else {
            parcel.writeInt(meta.size)
            meta.forEach { parcel.writeString(it.key); parcel.writeInt(it.value) }
        }
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<CrossData> {
        private fun readNullableMap(parcel: Parcel): Map<String, Int>? {
            val size = parcel.readInt()
            if (size < 0) return null
            return LinkedHashMap<String, Int>(size).apply {
                repeat(size) { put(parcel.readString()!!, parcel.readInt()) }
            }
        }

        override fun createFromParcel(parcel: Parcel): CrossData = CrossData(parcel)
        override fun newArray(size: Int): Array<CrossData?> = arrayOfNulls(size)
    }
}
