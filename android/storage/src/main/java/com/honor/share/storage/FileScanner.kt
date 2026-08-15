package com.honor.share.storage

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.honor.share.protocol.FolderBrowser
import com.honor.share.protocol.ProtocolConstants
import java.io.File

class FileScanner(private val context: Context) {
    fun scanDownloads(): List<LibraryFile> {
        val found = mutableListOf<LibraryFile>()
        if (Build.VERSION.SDK_INT >= 29) {
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.MIME_TYPE,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_ADDED,
                MediaStore.Downloads.RELATIVE_PATH,
            )
            context.contentResolver.query(
                collection,
                projection,
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf("%${ProtocolConstants.DEFAULT_RECEIVE_FOLDER}%"),
                "${MediaStore.Downloads.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
                val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
                val pathIdx = cursor.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val name = cursor.getString(nameIdx) ?: continue
                    if (name.contains(ProtocolConstants.PARTIAL_SUFFIX)) continue
                    val relative = relativeFromMedia(cursor.getString(pathIdx), name)
                    found += LibraryFile(
                        id = "ms-$id",
                        name = name,
                        mimeType = cursor.getString(mimeIdx) ?: "application/octet-stream",
                        size = cursor.getLong(sizeIdx),
                        uri = ContentUris.withAppendedId(collection, id),
                        direction = "RECEIVED",
                        deviceName = deviceFromRelative(relative),
                        createdAt = cursor.getLong(dateIdx) * 1000L,
                        relativePath = relative,
                    )
                }
            }
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            ProtocolConstants.DEFAULT_RECEIVE_FOLDER,
        )
        walkFiles(dir, "", found)
        return found
    }

    private fun walkFiles(dir: File, relative: String, found: MutableList<LibraryFile>) {
        val children = dir.listFiles() ?: return
        for (file in children) {
            if (file.name.startsWith(".")) continue
            if (file.name.contains(ProtocolConstants.PARTIAL_SUFFIX)) continue
            if (file.isDirectory) {
                val next = if (relative.isEmpty()) file.name else "$relative/${file.name}"
                walkFiles(file, next, found)
                continue
            }
            val rel = if (relative.isEmpty()) file.name else "$relative/${file.name}"
            if (found.any { it.relativePath == rel || (it.name == file.name && it.size == file.length()) }) continue
            found += LibraryFile(
                id = "file-${file.absolutePath.hashCode()}",
                name = file.name,
                mimeType = mimeFromName(file.name),
                size = file.length(),
                uri = Uri.fromFile(file),
                direction = "RECEIVED",
                deviceName = deviceFromRelative(rel),
                createdAt = file.lastModified(),
                relativePath = rel,
            )
        }
    }

    private fun relativeFromMedia(path: String?, name: String): String {
        val marker = ProtocolConstants.DEFAULT_RECEIVE_FOLDER
        val raw = path ?: return name
        val idx = raw.indexOf(marker)
        val after = if (idx >= 0) raw.substring(idx + marker.length).trim('/') else raw.trim('/')
        return if (after.isEmpty()) name else "$after/$name"
    }

    private fun deviceFromRelative(relative: String): String {
        val parts = relative.split('/').filter { it.isNotEmpty() }
        return if (parts.size >= 2) parts[1] else ""
    }

    private fun mimeFromName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }
}

fun List<LibraryFile>.childFolders(at: String): List<String> =
    FolderBrowser.childFolders(
        filter { it.direction != "SENT" }.map { it.relativePath.ifBlank { it.name } },
        at,
    )

fun List<LibraryFile>.filesAt(at: String, sent: Boolean): List<LibraryFile> = filter { file ->
    if (sent) file.direction == "SENT" && at.isEmpty()
    else file.direction != "SENT" && FolderBrowser.parentPath(file.relativePath.ifBlank { file.name }) == at
}
