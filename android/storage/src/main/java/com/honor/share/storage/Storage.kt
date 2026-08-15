package com.honor.share.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.honor.share.core.ShareError
import com.honor.share.core.ShareLog
import com.honor.share.protocol.Checksums
import com.honor.share.protocol.DestinationFile
import com.honor.share.protocol.ErrorCode
import com.honor.share.protocol.FileMeta
import com.honor.share.protocol.FilenameConflict
import com.honor.share.protocol.ProtocolConstants
import com.honor.share.protocol.ReceiveSink
import com.honor.share.protocol.ReceiveSinkFactory
import java.io.File
import java.io.InputStream
import java.util.UUID

data class SelectedFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String,
    val modifiedAt: Long? = null,
    val hash: String? = null,
)

data class PublishedFile(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val size: Long,
)

object DiskSpace {
    fun usable(path: File): Long = path.usableSpace

    fun ensure(path: File, needed: Long) {
        val available = usable(path)
        if (available < needed + 1_048_576) {
            throw ShareError.from(ErrorCode.DISK_FULL, "need $needed have $available").asException()
        }
    }
}

fun ShareError.asException(): IllegalStateException = IllegalStateException(debugMessage)

object TempCleanup {
    fun stale(dir: File) {
        dir.listFiles()?.forEach { file ->
            if (file.name.endsWith(ProtocolConstants.PARTIAL_SUFFIX) || file.name.contains(ProtocolConstants.PARTIAL_SUFFIX)) {
                ShareLog.i("storage", "deleting stale partial ${file.name}")
                file.delete()
            }
        }
    }
}

class SafAccess(private val context: Context) {
    fun persist(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
        }
    }

    fun fromUri(uri: Uri): SelectedFile {
        var name = "file"
        var size = 0L
        var modifiedAt: Long? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val modifiedIndex = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                .takeIf { it >= 0 }
                ?: cursor.getColumnIndex("last_modified")
            if (cursor.moveToFirst()) {
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
                if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                    val raw = cursor.getLong(modifiedIndex)
                    modifiedAt = if (raw in 1..99_999_999_999L) raw else raw * 1000
                }
            }
        }
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        if (size <= 0) {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
                if (fd.length >= 0) size = fd.length
            }
        }
        return SelectedFile(uri, FilenameConflict.sanitizeRelativePath(name).substringAfterLast('/'), size, mime, modifiedAt)
    }

    fun hash(uri: Uri): String =
        Checksums.sha256Hex(open(uri))

    fun open(uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri)
            ?: throw ShareError.from(ErrorCode.FILE_UNAVAILABLE, "cannot open").asException()
}

class DownloadsSinkFactory(private val context: Context) : ReceiveSinkFactory {
    private val tempDir: File = File(context.cacheDir, "incoming").apply { mkdirs() }
    private val published = mutableListOf<PublishedFile>()
    var subfolder: String = ""
    val replaceNames = mutableSetOf<String>()

    val lastSavedRelative: String
        get() {
            val base = "${Environment.DIRECTORY_DOWNLOADS}/${ProtocolConstants.DEFAULT_RECEIVE_FOLDER}"
            return if (subfolder.isBlank()) base else "$base/$subfolder"
        }

    private fun mediaRelativePath(): String = "$lastSavedRelative/"

    private fun fileDestDir(): File {
        val root = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            ProtocolConstants.DEFAULT_RECEIVE_FOLDER,
        )
        val dir = if (subfolder.isBlank()) root else File(root, subfolder)
        dir.mkdirs()
        return dir
    }

    @Synchronized
    fun drainPublished(): List<PublishedFile> {
        val copy = published.toList()
        published.clear()
        replaceNames.clear()
        return copy
    }

    init {
        TempCleanup.stale(tempDir)
        TempCleanup.stale(File(context.cacheDir, "partials").apply { mkdirs() })
    }

    override fun hasSpace(bytes: Long): Boolean = tempDir.usableSpace > bytes + 2_000_000

    override fun open(file: FileMeta, offset: Long): ReceiveSink {
        val safeName = FilenameConflict.sanitizeRelativePath(file.name).substringAfterLast('/')
        val temp = File(tempDir, ".${safeName}${ProtocolConstants.PARTIAL_SUFFIX}")
        if (offset == 0L && temp.exists()) temp.delete()
        val stream = java.io.FileOutputStream(temp, offset > 0)
        return object : ReceiveSink {
            override var bytesWritten: Long = offset
            override fun write(data: ByteArray) {
                stream.write(data)
                bytesWritten += data.size
            }

            override fun commit(expectedSha256: String) {
                stream.close()
                publish(temp, safeName, file.mimeType, file.size)
                temp.delete()
            }

            override fun abort() {
                try {
                    stream.close()
                } catch (_: Exception) {
                }
                temp.delete()
            }
        }
    }

    @Synchronized
    private fun publish(temp: File, displayName: String, mime: String, size: Long) {
        val unique = uniqueDownloadName(displayName)
        val uri: Uri
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, unique)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, mediaRelativePath())
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw ShareError.from(ErrorCode.DESTINATION_UNAVAILABLE, "mediastore insert").asException()
            context.contentResolver.openOutputStream(uri)?.use { out ->
                temp.inputStream().use { it.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        } else {
            val dir = fileDestDir()
            val dest = File(dir, unique)
            temp.copyTo(dest, overwrite = unique in replaceNames)
            uri = Uri.fromFile(dest)
        }
        published += PublishedFile(uri, unique, mime, if (size > 0) size else temp.length())
    }

    private fun uniqueDownloadName(desired: String): String {
        if (desired in replaceNames) {
            deleteExisting(desired)
            return desired
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val taken = hashSetOf<String>()
            val projection = arrayOf(MediaStore.Downloads.DISPLAY_NAME)
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf("%${ProtocolConstants.DEFAULT_RECEIVE_FOLDER}/${subfolder}%"),
                null,
            )?.use { cursor ->
                val idx = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (idx >= 0) taken += cursor.getString(idx)
                }
            }
            return FilenameConflict.uniqueName(desired) { taken.contains(it) }
        }
        val dir = fileDestDir()
        return FilenameConflict.uniqueName(desired) { File(dir, it).exists() }
    }

    private fun deleteExisting(name: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf(name, "%${ProtocolConstants.DEFAULT_RECEIVE_FOLDER}/${subfolder}%"),
            )
        } else {
            File(fileDestDir(), name).delete()
        }
    }

    fun destinationIndex(matchingSizes: Set<Long>): List<DestinationFile> {
        if (matchingSizes.isEmpty()) return emptyList()
        val found = mutableListOf<DestinationFile>()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val projection = arrayOf(
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.SIZE,
                )
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                    arrayOf("%${ProtocolConstants.DEFAULT_RECEIVE_FOLDER}/${subfolder}%"),
                    null,
                )?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(MediaStore.Downloads.SIZE)
                    val idIdx = cursor.getColumnIndex(MediaStore.Downloads._ID)
                    while (cursor.moveToNext()) {
                        val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else -1L
                        if (size !in matchingSizes) continue
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx) ?: continue else continue
                        val id = if (idIdx >= 0) cursor.getLong(idIdx) else continue
                        val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                        val hash = context.contentResolver.openInputStream(uri)?.let { stream ->
                            runCatching { Checksums.sha256Hex(stream) }.getOrNull()
                        }
                        found += DestinationFile(name, size, hash)
                    }
                }
            } else {
                fileDestDir().listFiles()?.forEach { file ->
                    if (file.isFile && file.length() in matchingSizes) {
                        val hash = runCatching { Checksums.sha256Hex(file.inputStream()) }.getOrNull()
                        found += DestinationFile(file.name, file.length(), hash)
                    }
                }
            }
        } catch (error: Exception) {
            ShareLog.w("storage", "destination index: ${error.message}")
        }
        return found
    }
}

fun SelectedFile.toMeta(): FileMeta = FileMeta(
    fileId = UUID.randomUUID().toString(),
    name = name,
    size = size,
    mimeType = mimeType.ifBlank { "application/octet-stream" },
    relativePath = name,
    sha256 = hash,
    modifiedAt = modifiedAt,
)
