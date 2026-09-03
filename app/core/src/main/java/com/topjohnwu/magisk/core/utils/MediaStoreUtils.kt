package com.topjohnwu.magisk.core.utils

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import androidx.core.net.toFile
import androidx.core.net.toUri
import com.topjohnwu.magisk.core.AppContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

@Suppress("DEPRECATION")
object MediaStoreUtils {

    private val cr get() = AppContext.contentResolver

    private fun downloadRelPath(subFolder: String) =
        if (subFolder.isEmpty()) Environment.DIRECTORY_DOWNLOADS
        else Environment.DIRECTORY_DOWNLOADS + File.separator + subFolder

    fun fullPath(subFolder: String): String =
        File(Environment.getExternalStorageDirectory(), downloadRelPath(subFolder)).canonicalPath

    @RequiresApi(api = 30)
    @Throws(IOException::class)
    private fun insertFile(collection: Uri, displayName: String, relPath: String): MediaStoreFile {
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)





        val fileUri = cr.insert(collection, values)
            ?: throw IOException("Can't insert $displayName.")

        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA)
        cr.query(fileUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(idIndex)
                val data = cursor.getString(dataColumn)
                return MediaStoreFile(collection, id, data)
            }
        }

        throw IOException("Can't insert $displayName.")
    }

    @RequiresApi(api = 29)
    private fun queryFile(collection: Uri, displayName: String, relPath: String): UriFile? {
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA)

        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} == ?"
        val selectionArgs = arrayOf(displayName)
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        val query = cr.query(
            collection,
            projection, selection, selectionArgs, sortOrder)
        query?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val data = cursor.getString(dataColumn)
                if (data.endsWith(relPath + File.separator + displayName)) {
                    return MediaStoreFile(collection, id, data)
                }
            }
        }
        return null
    }

    @Throws(IOException::class)
    fun getFile(displayName: String, subFolder: String = ""): UriFile {
        val rp = downloadRelPath(subFolder)
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {

            val parent = File(Environment.getExternalStorageDirectory(), rp)
            parent.mkdirs()
            LegacyUriFile(File(parent, displayName))
        } else {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            queryFile(collection, displayName, rp) ?: insertFile(collection, displayName, rp)
        }
    }

    @Throws(IOException::class)
    fun getFileAtStorageRoot(displayName: String, subFolder: String): UriFile {
        require(subFolder.matches(Regex("[a-zA-Z]{4,9}")))
        require(displayName.matches(Regex("[a-zA-Z]{4,9}\\.[a-zA-Z]{3}")))
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            val parent = File(Environment.getExternalStorageDirectory(), subFolder)
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("Can't create output directory.")
            }
            LegacyUriFile(File(parent, displayName))
        } else {
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            queryFile(collection, displayName, subFolder)
                ?: insertFile(collection, displayName, subFolder)
        }
    }

    fun Uri.inputStream() = cr.openInputStream(this) ?: throw FileNotFoundException()

    fun Uri.outputStream() = cr.openOutputStream(this, "rwt") ?: throw FileNotFoundException()

    val Uri.displayName: String get() {
        if (scheme == "file") {

            return toFile().name
        }
        require(scheme == "content") { "Uri lacks 'content' scheme: $this" }
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        cr.query(this, projection, null, null, null)?.use { cursor ->
            val displayNameColumn = cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst()) {
                return cursor.getString(displayNameColumn)
            }
        }
        return this.toString()
    }

    interface UriFile {
        val uri: Uri
        val fullPath: String
        fun delete(): Boolean
    }

    private class LegacyUriFile(private val file: File) : UriFile {
        override val uri = file.toUri()
        override val fullPath get() = file.path
        override fun delete() = file.delete()
        override fun toString() = file.toString()
    }

    @RequiresApi(api = 29)
    private class MediaStoreFile(
        collection: Uri,
        private val id: Long,
        private val data: String,
    ) : UriFile {
        override val uri = ContentUris.withAppendedId(collection, id)
        override val fullPath get() = data
        override fun toString() = data
        override fun delete(): Boolean {
            val selection = "${MediaStore.MediaColumns._ID} == ?"
            val selectionArgs = arrayOf(id.toString())
            return cr.delete(uri, selection, selectionArgs) == 1
        }
    }
}
