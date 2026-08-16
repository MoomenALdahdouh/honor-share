package com.honor.share.storage

import android.net.Uri

enum class FileKind { PHOTO, VIDEO, AUDIO, DOCUMENT, OTHER }

enum class FileFilter { ALL, RECEIVED, SENT, PHOTOS, VIDEOS, DOCS }

enum class FileGroupBy { DATE, TYPE, DEVICE, NONE }

enum class FileSort { NEWEST, OLDEST, NAME, SIZE }

enum class FileViewMode { LIST, GRID }

data class LibraryFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val uri: Uri,
    val direction: String,
    val deviceName: String,
    val createdAt: Long,
    val kind: FileKind = kindOf(mimeType, name),
    val relativePath: String = name,
) {
    val parentPath: String
        get() = com.honor.share.protocol.FolderBrowser.parentPath(relativePath.ifBlank { name })
}

fun kindOf(mime: String, name: String): FileKind {
    val lower = mime.lowercase()
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        lower.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp") -> FileKind.PHOTO
        lower.startsWith("video/") || ext in setOf("mp4", "mov", "mkv", "webm", "avi") -> FileKind.VIDEO
        lower.startsWith("audio/") || ext in setOf("mp3", "m4a", "wav", "aac", "flac") -> FileKind.AUDIO
        lower == "application/pdf" ||
            ext in setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "csv") -> FileKind.DOCUMENT
        else -> FileKind.OTHER
    }
}

fun LibraryFile.matches(filter: FileFilter): Boolean = when (filter) {
    FileFilter.ALL -> true
    FileFilter.RECEIVED -> direction == "RECEIVED"
    FileFilter.SENT -> direction == "SENT"
    FileFilter.PHOTOS -> kind == FileKind.PHOTO
    FileFilter.VIDEOS -> kind == FileKind.VIDEO
    FileFilter.DOCS -> kind == FileKind.DOCUMENT
}

fun List<LibraryFile>.filtered(query: String, filter: FileFilter): List<LibraryFile> {
    val needle = query.trim().lowercase()
    return filter { file ->
        file.matches(filter) && (needle.isEmpty() || file.name.lowercase().contains(needle))
    }
}

fun List<LibraryFile>.sortedBy(sort: FileSort): List<LibraryFile> = when (sort) {
    FileSort.NEWEST -> sortedByDescending { it.createdAt }
    FileSort.OLDEST -> sortedBy { it.createdAt }
    FileSort.NAME -> sortedBy { it.name.lowercase() }
    FileSort.SIZE -> sortedByDescending { it.size }
}

data class FileSection(val title: String, val files: List<LibraryFile>)

fun List<LibraryFile>.grouped(group: FileGroupBy, dateLabel: (Long) -> String, typeLabel: (FileKind) -> String): List<FileSection> {
    if (group == FileGroupBy.NONE) return listOf(FileSection("", this))
    val buckets = when (group) {
        FileGroupBy.DATE -> groupBy { dateLabel(it.createdAt) }
        FileGroupBy.TYPE -> groupBy { typeLabel(it.kind) }
        FileGroupBy.DEVICE -> groupBy { it.deviceName.ifBlank { "Direct Share" } }
        FileGroupBy.NONE -> emptyMap()
    }
    return buckets.map { (title, files) -> FileSection(title, files) }
}
