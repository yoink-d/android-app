package com.maynkudu.yoinkd.data.storage

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

data class CreatedFile(val uri: Uri, val created: Boolean)

class UriFiles(private val context: Context) {
    private val resolver = context.contentResolver

    fun folderName(uri: String?): String {
        if (uri == null) return "Downloads/yoink'd"
        val folder = DocumentFile.fromTreeUri(context, Uri.parse(uri))
        require(folder != null && folder.isDirectory && folder.canWrite()) {
            "This folder is unavailable. Choose it again in Settings."
        }
        return folder.name ?: "Selected folder"
    }

    fun create(folder: String?, name: String, mime: String): CreatedFile {
        if (folder == null) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/yoink'd/")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create a file in Downloads/yoink'd.")
            return CreatedFile(uri, true)
        }
        folderName(folder)
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(folder))!!
        tree.findFile(name)?.let { return CreatedFile(it.uri, false) }
        val file = tree.createFile(mime, name) ?: error("Could not create a file in this folder.")
        return CreatedFile(file.uri, true)
    }

    fun publish(uri: Uri) {
        if (uri.authority == MediaStore.AUTHORITY) resolver.update(uri,
            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
    }

    // A provider URI can be lost if the process stops immediately after insertion.
    fun ownedPendingFiles(names: List<String>): List<Uri> = names.flatMap { name ->
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        resolver.query(collection, arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ? " +
                "AND ${MediaStore.Downloads.OWNER_PACKAGE_NAME} = ? AND ${MediaStore.Downloads.IS_PENDING} = 1",
            arrayOf(name, "Download/yoink'd/", context.packageName), null)?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(ContentUris.withAppendedId(collection, cursor.getLong(0))) }
        }.orEmpty()
    }

    fun delete(uri: Uri) {
        if (DocumentsContract.isDocumentUri(context, uri)) {
            if (DocumentFile.fromSingleUri(context, uri)?.exists() == true)
                check(DocumentsContract.deleteDocument(resolver, uri)) { "Could not delete this file." }
        } else resolver.delete(uri, null, null)
    }

    fun open(uri: Uri): InputStream = resolver.openInputStream(uri)
        ?: error("This saved file is unavailable. Check its folder in Settings.")

    suspend fun copy(input: InputStream, target: Uri): Long = input.use { source ->
        val output = resolver.openOutputStream(target, "wt") ?: error("Could not write to this folder.")
        output.use { destination ->
            val count = copyCancellable(source, destination)
            destination.flush()
            if (destination is FileOutputStream) destination.fd.sync()
            count
        }
    }

    suspend fun copyToPrivate(uri: Uri, target: File): Long {
        target.parentFile?.mkdirs()
        return open(uri).use { source -> FileOutputStream(target).use { output ->
            copyCancellable(source, output).also { output.fd.sync() }
        } }
    }

    fun sha256(uri: Uri): String = open(uri).use(::sha256)

    fun displayName(uri: Uri): String = resolver.query(uri,
        arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: uri.lastPathSegment ?: "Media"
}

private suspend fun copyCancellable(input: InputStream, output: OutputStream): Long {
    var total = 0L
    val buffer = ByteArray(65_536)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) return total
        output.write(buffer, 0, count)
        total += count
    }
}

fun sha256(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(65_536)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
