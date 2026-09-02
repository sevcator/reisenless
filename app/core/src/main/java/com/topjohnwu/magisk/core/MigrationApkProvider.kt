package com.topjohnwu.magisk.core

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException


class MigrationApkProvider : ContentProvider() {

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        return openMigrationApk(context, uri, mode)
    }

    override fun getType(uri: Uri) = "application/vnd.android.package-archive"
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0
}

internal fun openMigrationApk(
    context: Context?,
    uri: Uri,
    mode: String,
): ParcelFileDescriptor {
    if (mode != "r" || uri.pathSegments != listOf("apk")) {
        throw FileNotFoundException(uri.toString())
    }
    // A hidden manager's installed package is only the randomized stub;
    // expose its private full APK during identity handoff.
    val source = runCatching { AppApkPath }.getOrNull()
        ?: context?.applicationInfo?.sourceDir
        ?: throw FileNotFoundException(uri.toString())
    return ParcelFileDescriptor.open(File(source), ParcelFileDescriptor.MODE_READ_ONLY)
}
