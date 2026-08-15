package com.honor.share.ui

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import com.honor.share.storage.LibraryFile
import java.io.File

object FileOpener {
    fun open(context: Context, file: LibraryFile) {
        val uri = shareableUri(context, file.uri)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, file.mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(Intent.createChooser(intent, file.name))
        } catch (_: ActivityNotFoundException) {
            share(context, file)
        }
    }

    fun share(context: Context, file: LibraryFile) {
        val uri = shareableUri(context, file.uri)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = file.mimeType.ifBlank { "*/*" }
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openHonorShareFolder(context: Context) {
        val tree = Uri.parse("content://com.android.externalstorage.documents/document/primary:Download%2FHONOR%20Share")
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(tree, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(view)
        } catch (_: Exception) {
            context.startActivity(
                Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun shareableUri(context: Context, uri: Uri): Uri {
        if (uri.scheme == "file") {
            val path = uri.path ?: return uri
            val file = File(path)
            return FileProvider.getUriForFile(context, context.packageName + ".files", file)
        }
        return uri
    }
}
