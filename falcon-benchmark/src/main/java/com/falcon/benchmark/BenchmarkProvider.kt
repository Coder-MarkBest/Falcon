package com.falcon.benchmark

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/** Minimal echo provider: insert stashes the last value; query returns it. */
class BenchmarkProvider : ContentProvider() {
    @Volatile private var lastValue: String? = null

    override fun onCreate(): Boolean = true

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        lastValue = values?.getAsString("value")
        return uri
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor {
        return MatrixCursor(arrayOf("value")).apply { addRow(arrayOf<Any?>(lastValue)) }
    }

    override fun getType(uri: Uri): String? = null
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
}
