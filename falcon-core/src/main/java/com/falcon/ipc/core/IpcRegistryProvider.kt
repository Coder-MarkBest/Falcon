package com.falcon.ipc.core

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Binder
import com.falcon.ipc.security.SignatureGuard

class IpcRegistryProvider : ContentProvider() {

    companion object {
        const val TABLE_SERVICES = "services"
    }

    private lateinit var dbHelper: RegistryDbHelper
    private lateinit var signatureGuard: SignatureGuard

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        dbHelper = RegistryDbHelper(ctx)
        signatureGuard = SignatureGuard().apply { init(ctx) }
        return true
    }

    override fun query(
        uri: Uri, projection: Array<out String>?,
        selection: String?, selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        enforceSignature()
        val db = dbHelper.readableDatabase
        return db.query(TABLE_SERVICES, projection, selection, selectionArgs, null, null, sortOrder)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        enforceSignature()
        val db = dbHelper.writableDatabase
        val now = System.currentTimeMillis()
        values?.put("register_time", now)
        values?.put("pid", android.os.Process.myPid())
        db.insertWithOnConflict(TABLE_SERVICES, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        context?.contentResolver?.notifyChange(uri, null)
        return uri
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        enforceSignature()
        val db = dbHelper.writableDatabase
        val count = db.delete(TABLE_SERVICES, selection, selectionArgs)
        if (count > 0) context?.contentResolver?.notifyChange(uri, null)
        return count
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun getType(uri: Uri): String? = null

    private fun enforceSignature() {
        val ctx = context ?: throw SecurityException("No context")
        val callingUid = Binder.getCallingUid()
        if (!signatureGuard.verify(ctx, callingUid)) {
            throw SecurityException("Falcon IPC: Unauthorized access from UID $callingUid")
        }
    }

    private class RegistryDbHelper(context: android.content.Context) :
        SQLiteOpenHelper(context, "falcon_registry.db", null, 1) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE $TABLE_SERVICES (
                    service_key TEXT PRIMARY KEY,
                    process_name TEXT NOT NULL,
                    pkg_name TEXT NOT NULL,
                    register_time INTEGER NOT NULL,
                    pid INTEGER NOT NULL
                )
            """)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_SERVICES")
            onCreate(db)
        }
    }
}
