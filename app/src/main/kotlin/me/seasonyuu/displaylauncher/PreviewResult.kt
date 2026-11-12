package me.seasonyuu.displaylauncher

import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Parcelable

/** Propagates RETURN_VALUE to the descriptor, transferring ownership without leaking a service FD. */
class PreviewResult(val status: String, val message: String, val timestamp: Long = 0, val image: ParcelFileDescriptor? = null) : Parcelable {
    override fun describeContents() = if (image == null) 0 else Parcelable.CONTENTS_FILE_DESCRIPTOR
    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(status)
        dest.writeString(message)
        dest.writeLong(timestamp)
        dest.writeParcelable(image, flags)
    }
    companion object {
        @JvmField val CREATOR = object : Parcelable.Creator<PreviewResult> {
            @Suppress("DEPRECATION")
            override fun createFromParcel(source: Parcel) = PreviewResult(
                source.readString().orEmpty(), source.readString().orEmpty(), source.readLong(),
                source.readParcelable(ParcelFileDescriptor::class.java.classLoader)
            )
            override fun newArray(size: Int): Array<PreviewResult?> = arrayOfNulls(size)
        }
    }
}
