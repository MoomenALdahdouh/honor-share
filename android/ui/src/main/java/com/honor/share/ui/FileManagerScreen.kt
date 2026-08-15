package com.honor.share.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.honor.share.protocol.ByteFormat
import com.honor.share.storage.FileFilter
import com.honor.share.storage.FileKind
import com.honor.share.storage.LibraryFile
import com.honor.share.storage.childFolders
import com.honor.share.storage.filesAt
import com.honor.share.storage.filtered
import java.text.DateFormat
import java.util.Date

@Composable
fun FileManagerScreen(model: ShareViewModel) {
    val context = LocalContext.current
    val files by model.libraryFiles.collectAsState()
    val query by model.libraryQuery.collectAsState()
    val filter by model.libraryFilter.collectAsState()
    val folder by model.libraryFolder.collectAsState()
    LaunchedEffect(Unit) { model.refreshLibrary() }

    val scoped = remember(files, query, filter) { files.filtered(query, filter) }
    val searching = query.trim().isNotEmpty()
    val folders = if (searching || filter == FileFilter.SENT) emptyList() else scoped.childFolders(folder)
    val filesHere = when {
        searching -> scoped
        filter == FileFilter.SENT -> scoped.filesAt("", sent = true)
        else -> scoped.filesAt(folder, sent = false)
    }
    val title = if (folder.isEmpty()) stringResource(R.string.files) else folder.substringAfterLast('/')

    Column(Modifier.honorScreen()) {
        HonorTopBar(title, onBack = { model.libraryBack() })
        OutlinedTextField(
            value = query,
            onValueChange = { model.libraryQuery.value = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.search_files)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            shape = RoundedCornerShape(16.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = filter == FileFilter.ALL, onClick = { model.libraryFilter.value = FileFilter.ALL }, label = { Text(stringResource(R.string.filter_all)) })
            FilterChip(selected = filter == FileFilter.RECEIVED, onClick = { model.libraryFilter.value = FileFilter.RECEIVED }, label = { Text(stringResource(R.string.received_label)) })
            FilterChip(selected = filter == FileFilter.SENT, onClick = { model.libraryFilter.value = FileFilter.SENT }, label = { Text(stringResource(R.string.sent)) })
        }
        if (folders.isEmpty() && filesHere.isEmpty()) {
            Spacer(Modifier.height(48.dp))
            Text(stringResource(R.string.files_empty_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.files_empty_body), style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(folders, key = { "dir-$it" }) { name ->
                    FolderRow(name = name, onOpen = { model.enterLibraryFolder(name) })
                }
                items(filesHere, key = { it.id }) { file ->
                    FileListRow(
                        file = file,
                        onOpen = { FileOpener.open(context, file) },
                        onShare = { FileOpener.share(context, file) },
                        onDelete = { model.deleteFile(file) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderRow(name: String, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onOpen)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

@Composable
private fun FileListRow(file: LibraryFile, onOpen: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onOpen)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileThumb(file, Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    ByteFormat.humanSize(file.size),
                    if (file.direction == "SENT") stringResource(R.string.sent) else stringResource(R.string.received_label),
                    file.deviceName.ifBlank { null },
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(file.createdAt)),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onShare) {
            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share_file))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete_file))
        }
    }
}

@Composable
private fun FileThumb(file: LibraryFile, modifier: Modifier) {
    if (file.kind == FileKind.PHOTO) {
        AsyncImage(
            model = file.uri,
            contentDescription = file.name,
            modifier = modifier.background(MaterialTheme.colorScheme.background),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            Icon(kindIcon(file.kind), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
        }
    }
}

private fun kindIcon(kind: FileKind) = when (kind) {
    FileKind.PHOTO -> Icons.Default.Image
    FileKind.VIDEO -> Icons.Default.Videocam
    FileKind.AUDIO -> Icons.Default.AudioFile
    FileKind.DOCUMENT -> Icons.Default.Description
    FileKind.OTHER -> Icons.Default.InsertDriveFile
}
