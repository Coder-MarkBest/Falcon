package com.falcon.demo

import android.os.Parcel
import android.os.Parcelable

/**
 * A cross-process data model.
 *
 * Every type that crosses the Binder boundary in Falcon must implement
 * [Parcelable]. Using `@Parcelize` (kotlin-parcelize plugin) is the easiest
 * way; here we write it by hand to keep the demo plugin-free and show exactly
 * what Falcon serializes.
 */
data class DemoUser(
    val id: Int,
    val name: String,
    val vip: Boolean
) : Parcelable {

    constructor(parcel: Parcel) : this(
        parcel.readInt(),
        parcel.readString() ?: "",
        parcel.readByte() != 0.toByte()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(id)
        parcel.writeString(name)
        parcel.writeByte(if (vip) 1 else 0)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<DemoUser> {
        override fun createFromParcel(parcel: Parcel): DemoUser = DemoUser(parcel)
        override fun newArray(size: Int): Array<DemoUser?> = arrayOfNulls(size)
    }
}
