package com.honor.share.protocol

object PickerSelection {
    fun <T> merge(current: List<T>, incoming: List<T>): List<T> =
        if (current.isEmpty()) incoming else current + incoming
}

object FolderBrowser {
    fun parentPath(relativePath: String): String {
        val parts = relativePath.split('/').filter { it.isNotEmpty() }
        return if (parts.size <= 1) "" else parts.dropLast(1).joinToString("/")
    }

    fun childFolders(relativePaths: List<String>, at: String): List<String> {
        val prefix = if (at.isEmpty()) "" else "$at/"
        return relativePaths.mapNotNull { path ->
            if (at.isNotEmpty() && !path.startsWith(prefix)) return@mapNotNull null
            val rest = if (at.isEmpty()) path else path.removePrefix(prefix)
            val slash = rest.indexOf('/')
            if (slash <= 0) null else rest.substring(0, slash)
        }.distinct().sorted()
    }

    fun filesAt(relativePaths: List<String>, at: String): List<String> =
        relativePaths.filter { parentPath(it) == at }
}
