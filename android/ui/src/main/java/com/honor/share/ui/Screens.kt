package com.honor.share.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import coil.compose.AsyncImage
import com.honor.share.protocol.ByteFormat
import com.honor.share.protocol.TransferState
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

@Composable
fun HonorShareRoot(model: ShareViewModel, onOpenSettings: () -> Unit) {
    HonorTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (model.screen.collectAsState().value) {
                Screen.HOME -> HomeScreen(model)
                Screen.SELECTED -> SelectedScreen(model)
                Screen.DEVICES -> DevicesScreen(model, onOpenSettings)
                Screen.PAIRING -> PairingScreen(model)
                Screen.TRANSFER -> TransferScreen(model)
                Screen.HISTORY -> HistoryScreen(model)
                Screen.FILES -> FileManagerScreen(model)
                Screen.RECEIVE -> ReceiveWaitScreen(model)
                Screen.SCAN -> QrScanScreen(model, onOpenSettings)
                Screen.PACKAGE -> PackageWaitScreen(model)
                Screen.CODE -> PackageWaitScreen(model)
                Screen.INCOMING -> IncomingScreen(model)
                Screen.PERMISSION -> PermissionScreen(onOpenSettings) { model.backHome() }
                Screen.RADIO -> RadioScreen(onOpenSettings) { model.backHome() }
            }
        }
    }
}

@Composable
private fun HomeScreen(model: ShareViewModel) {
    val files by model.libraryFiles.collectAsState()
    LaunchedEffect(Unit) { model.refreshLibrary() }
    Column(Modifier.honorScreen()) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.home_tagline), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        BigAction(
            title = stringResource(R.string.send_files),
            subtitle = stringResource(R.string.send_files_subtitle),
            icon = Icons.Default.IosShare,
            emphasized = true,
            onClick = { model.openSend() },
        )
        Spacer(Modifier.height(14.dp))
        BigAction(
            title = stringResource(R.string.receive),
            subtitle = stringResource(R.string.receive_subtitle),
            icon = Icons.AutoMirrored.Filled.CallReceived,
            emphasized = false,
            onClick = { model.openReceive() },
        )
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable { model.openFiles() }
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(stringResource(R.string.my_files), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (files.isEmpty()) stringResource(R.string.files_empty_home) else stringResource(R.string.files_ready, files.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BigAction(
    title: String,
    subtitle: String,
    icon: ImageVector,
    emphasized: Boolean,
    onClick: () -> Unit,
) {
    val background = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val titleColor = if (emphasized) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val subtitleColor = if (emphasized) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurfaceVariant
    val iconTint = if (emphasized) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(28.dp))
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = titleColor)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = subtitleColor)
        }
    }
}

@Composable
private fun SelectedScreen(model: ShareViewModel) {
    val files by model.selected.collectAsState()
    val preparing by model.preparing.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) model.onUrisPicked(uris)
    }
    Column(Modifier.honorScreen()) {
        HonorTopBar(stringResource(R.string.send_files), onBack = { model.backHome() })
        if (files.isEmpty()) {
            StepLabel(1, 2, stringResource(R.string.step_choose_label))
            InstructionCard(stringResource(R.string.send_portal_body))
            Spacer(Modifier.height(28.dp))
            HonorPrimaryButton(stringResource(R.string.choose_photos)) { picker.launch(arrayOf("image/*", "video/*", "*/*")) }
        } else {
            StepLabel(1, 2, stringResource(R.string.selected))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(files) { index, file ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = file.uri,
                            contentDescription = null,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(10.dp)),
                        )
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(model.formatSize(file.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { model.removeAt(index) }) { Text(stringResource(R.string.remove_file)) }
                    }
                }
            }
            Text("${fileCountLabel(files.size)} · ${model.formatSize(files.sumOf { it.size })}", style = MaterialTheme.typography.bodyMedium)
            if (preparing) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.preparing_files), modifier = Modifier.padding(start = 10.dp))
                }
            }
            BottomActions {
                TextButton(onClick = { picker.launch(arrayOf("image/*", "video/*", "*/*")) }) { Text(stringResource(R.string.add_files)) }
                HonorPrimaryButton(
                    text = stringResource(R.string.continue_show_code),
                    onClick = { model.preparePackageAndWait() },
                    enabled = !preparing,
                )
            }
        }
    }
}

@Composable
private fun DevicesScreen(model: ShareViewModel, onOpenSettings: () -> Unit) {
    val devices by model.devices.collectAsState()
    LaunchedEffect(Unit) {
                while (true) {
            delay(2000)
            model.discovery.prune()
        }
    }
    Column(Modifier.honorScreen()) {
        HonorTopBar(stringResource(R.string.choose_device), onBack = { model.backHome() })
        Text(stringResource(R.string.looking_for_mac), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { model.retryDiscovery() }) { Text(stringResource(R.string.retry)) }
        Spacer(Modifier.height(12.dp))
        if (devices.isEmpty()) {
            Spacer(Modifier.height(48.dp))
            Text(stringResource(R.string.looking_for_mac), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.no_mac_found_body), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.not_airdrop), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.open_settings)) }
        } else {
            Text(stringResource(R.string.tap_mac_to_send), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            devices.forEach { device ->
                val ready = device.port > 0 && device.host.isNotBlank()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable(enabled = ready) { model.sendTo(device) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Computer, contentDescription = null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(device.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (ready) stringResource(R.string.ready) else stringResource(R.string.resolving_device),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun PairingScreen(model: ShareViewModel) {
    val prompt = model.pairing.collectAsState().value
    Column(Modifier.honorScreen(), horizontalAlignment = Alignment.CenterHorizontally) {
        HonorTopBar(stringResource(R.string.connect), onBack = { model.confirmPairing(false) })
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.connect_title, prompt?.peerName ?: ""), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.code), style = MaterialTheme.typography.bodyMedium)
        Text(prompt?.display ?: "—", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.code_match), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            HonorPrimaryButton(stringResource(R.string.cancel), modifier = Modifier.weight(1f), onClick = { model.confirmPairing(false) })
            Spacer(Modifier.size(12.dp))
            HonorPrimaryButton(stringResource(R.string.connect), modifier = Modifier.weight(1f), onClick = { model.confirmPairing(true) })
        }
    }
}

@Composable
private fun TransferScreen(model: ShareViewModel) {
    val progress = model.progress.collectAsState().value
    val error = model.transferError.collectAsState().value
    val receiving by model.receiving.collectAsState()
    val lastSaved by model.lastSavedFolder.collectAsState()
    val title = if (progress?.state == TransferState.COMPLETED) stringResource(R.string.done)
    else if (error?.code == com.honor.share.protocol.ErrorCode.CANCELLED) stringResource(R.string.transfer_cancelled)
    else if (error != null) stringResource(error.code.userStringRes())
    else if (progress?.state == TransferState.TRANSFERRING || (progress?.bytesTransferred ?: 0) > 0) {
        if (receiving) stringResource(R.string.receiving) else stringResource(R.string.sending)
    } else stringResource(R.string.waiting_for_mac)
    Column(Modifier.honorScreen()) {
        HonorTopBar(title, onBack = { model.backHome() })
        Spacer(Modifier.height(8.dp))
        if (progress != null) {
            Text("${progress.filesCompleted} / ${progress.filesTotal} · ${fileCountLabel(progress.filesTotal)}")
            Spacer(Modifier.height(8.dp))
            val fraction = if (progress.bytesTotal == 0L) 0f else progress.bytesTransferred.toFloat() / progress.bytesTotal
            LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.overall))
            Text("${ByteFormat.humanSize(progress.bytesTransferred)} / ${ByteFormat.humanSize(progress.bytesTotal)}")
            if (progress.bytesTotal > 0) {
                Text("${(fraction * 100).toInt()}%")
            }
            if (progress.bytesPerSecond > 0) {
                Text(ByteFormat.humanSpeed(progress.bytesPerSecond))
            }
            progress.etaSeconds?.let { eta ->
                val label = if (eta >= 60) stringResource(R.string.minutes, (eta / 60).toInt()) else stringResource(R.string.seconds, eta.toInt())
                Text(stringResource(R.string.about_remaining, label))
            }
            if (progress.currentName.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.current_file))
                Text(progress.currentName)
                Text("${ByteFormat.humanSize(progress.currentBytes)} / ${ByteFormat.humanSize(progress.currentSize)}")
            }
        } else if (error == null) {
            Text(stringResource(R.string.waiting_for_mac_body), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.weight(1f))
        if (progress?.state == TransferState.TRANSFERRING || progress?.state == TransferState.VERIFYING || progress?.state == TransferState.WAITING_FOR_ACCEPTANCE) {
            HonorPrimaryButton(stringResource(R.string.cancel), onClick = { model.cancelTransfer() })
        } else if (progress?.state == TransferState.COMPLETED || error != null) {
            if (progress?.state == TransferState.COMPLETED) {
                if (receiving && lastSaved.isNotBlank()) {
                    Text(stringResource(R.string.saved_to, lastSaved), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    HonorPrimaryButton(stringResource(R.string.open_folder), onClick = { model.openSavedFolder() })
                    Spacer(Modifier.height(8.dp))
                }
                HonorPrimaryButton(stringResource(R.string.view_files), onClick = { model.openFiles() })
                Spacer(Modifier.height(8.dp))
                HonorPrimaryButton(
                    if (receiving) stringResource(R.string.receive_more) else stringResource(R.string.send_more),
                    onClick = { if (receiving) model.openReceive() else model.openSend() },
                )
                Spacer(Modifier.height(8.dp))
            }
            HonorPrimaryButton(stringResource(R.string.done), onClick = { model.backHome() })
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { if (receiving) model.openReceive() else model.openSend() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.retry))
                }
            }
        } else {
            TextButton(onClick = { model.backHome() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

@Composable
private fun HistoryScreen(model: ShareViewModel) {
    val items by model.historyItems.collectAsState()
    Column(Modifier.honorScreen()) {
        HonorTopBar(stringResource(R.string.history), onBack = { model.backHome() })
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(items, key = { it.id }) { item ->
                val cal = Calendar.getInstance()
                val today = Calendar.getInstance()
                cal.timeInMillis = item.createdAt
                val label = when {
                    today.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR) &&
                        today.get(Calendar.YEAR) == cal.get(Calendar.YEAR) -> stringResource(R.string.today)
                    else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.createdAt))
                }
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Text(if (item.direction == "SENT") stringResource(R.string.sent) else stringResource(R.string.received_label), style = MaterialTheme.typography.titleMedium)
                Text("${item.fileCount} · ${ByteFormat.humanSize(item.totalBytes)}")
                Text("${item.deviceName} · ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.createdAt))}")
                Text(item.status, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
            }
        }
        TextButton(onClick = { model.clearHistory() }) { Text(stringResource(R.string.clear_history)) }
    }
}

@Composable
private fun ReceiveWaitScreen(model: ShareViewModel) {
    var payload by remember { mutableStateOf(model.shareLink()) }
    LaunchedEffect(Unit) {
        while (true) {
            payload = model.shareLink()
            delay(2000)
        }
    }
    Column(Modifier.honorScreen(), horizontalAlignment = Alignment.CenterHorizontally) {
        HonorTopBar(stringResource(R.string.receive), onBack = { model.backHome() })
        Text(stringResource(R.string.waiting_receive), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.qr_receive_body), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        QrPanel(
            payload = payload,
            caption = stringResource(R.string.qr_receive_caption),
            missing = stringResource(R.string.qr_missing),
        )
    }
}

@Composable
private fun PackageWaitScreen(model: ShareViewModel) {
    val pkg by model.currentPackage.collectAsState()
    val progress by model.progress.collectAsState()
    val invite = pkg?.invitation
    var remaining by remember { mutableStateOf(invite?.remainingSeconds() ?: 0L) }
    LaunchedEffect(invite?.inviteId) {
        while (true) {
            remaining = invite?.remainingSeconds() ?: 0L
            if (remaining <= 0L) break
            delay(1000)
        }
    }
    LaunchedEffect(progress?.state) {
        if (progress?.state == TransferState.TRANSFERRING || progress?.state == TransferState.COMPLETED) {
            model.screen.value = Screen.TRANSFER
        }
    }
    Column(Modifier.honorScreen(), horizontalAlignment = Alignment.CenterHorizontally) {
        HonorTopBar(
            stringResource(R.string.ready_to_send),
            onBack = { model.backHome() },
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StepLabel(2, 2, stringResource(R.string.step_code_label))
            Text("${fileCountLabel(pkg?.files?.size ?: 0)} · ${ByteFormat.humanSize(pkg?.totalBytes ?: 0)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            if (invite != null) {
                QrPanel(
                    payload = invite.encode(),
                    caption = stringResource(R.string.mac_enter_code),
                    missing = stringResource(R.string.qr_missing),
                )
                Spacer(Modifier.height(16.dp))
            }
            Text(invite?.displayCode() ?: "------", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            if (invite != null && remaining > 0) {
                val mm = remaining / 60
                val ss = remaining % 60
                Text(stringResource(R.string.expires_in, "%02d:%02d".format(mm, ss)), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stringResource(R.string.error_invitation_expired), color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(20.dp))
            HonorPrimaryButton(stringResource(R.string.create_new_code), onClick = { model.regenerateInvitation() })
        }
    }
}

@Composable
private fun IncomingScreen(model: ShareViewModel) {
    val request = model.incoming.collectAsState().value
    val comparison = request?.comparison
    Column(Modifier.honorScreen()) {
        HonorTopBar(stringResource(R.string.incoming_files), onBack = { model.confirmIncoming(false) })
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.wants_to_send, request?.peer?.name ?: ""), style = MaterialTheme.typography.titleMedium)
        val payload = request?.payload
        if (payload != null) {
            Text(fileCountLabel(payload.files.size))
            Text(ByteFormat.humanSize(payload.totalBytes))
        }
        if (comparison != null) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.already_on_device, comparison.alreadyPresent.size))
            Text(stringResource(R.string.need_transfer, comparison.needsTransfer.size + comparison.conflicts.size))
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.save_to), style = MaterialTheme.typography.bodyMedium)
        Text(
            model.transfer.lastSavedPath().ifBlank { stringResource(R.string.save_to_downloads) },
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.weight(1f))
        if (comparison != null && comparison.conflicts.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = {
                    comparison.conflicts.forEach { request.resolutions[it.incoming.fileId] = com.honor.share.protocol.ConflictAction.KEEP_BOTH }
                }) { Text(stringResource(R.string.keep_all)) }
                TextButton(onClick = {
                    comparison.conflicts.forEach { request.resolutions[it.incoming.fileId] = com.honor.share.protocol.ConflictAction.REPLACE }
                }) { Text(stringResource(R.string.replace_all)) }
                TextButton(onClick = {
                    comparison.conflicts.forEach { request.resolutions[it.incoming.fileId] = com.honor.share.protocol.ConflictAction.SKIP }
                }) { Text(stringResource(R.string.skip_all)) }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { model.confirmIncoming(false) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.decline)) }
            HonorPrimaryButton(stringResource(R.string.receive), modifier = Modifier.weight(1f), onClick = { model.confirmIncoming(true) })
        }
    }
}

@Composable
private fun PermissionScreen(onOpenSettings: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.honorScreen(), horizontalAlignment = Alignment.CenterHorizontally) {
        HonorTopBar(stringResource(R.string.nearby_permission_title), onBack = onBack)
        Spacer(Modifier.height(16.dp))
        HonorPrimaryButton(stringResource(R.string.open_settings), onClick = onOpenSettings)
    }
}

@Composable
private fun RadioScreen(onOpenSettings: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.honorScreen(), horizontalAlignment = Alignment.CenterHorizontally) {
        HonorTopBar(stringResource(R.string.wifi_off_title), onBack = onBack)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.wifi_off_body), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        HonorPrimaryButton(stringResource(R.string.open_settings), onClick = onOpenSettings)
    }
}

@Composable
internal fun TopBack(title: String, onBack: () -> Unit) {
    HonorTopBar(title, onBack = onBack)
}

@Composable
private fun fileCountLabel(count: Int): String =
    if (count == 1) stringResource(R.string.file_count_one) else stringResource(R.string.files_count, count)
