package com.falcon.mcp.gateway

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/** Native-only demo credential exchange. ContentProvider.call requires explicit UID checks. */
class BootstrapProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = requireNotNull(context)
        if (ctx.packageManager.checkSignatures(Binder.getCallingUid(), ctx.applicationInfo.uid) != PackageManager.SIGNATURE_MATCH) {
            throw SecurityException("Same-signature client required")
        }
        require(method == "connect")
        return Bundle().apply {
            putString("endpoint", "http://127.0.0.1:18765/mcp")
            putString("token", GatewayApp.token(ctx))
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
