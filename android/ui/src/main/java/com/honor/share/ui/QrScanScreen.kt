package com.honor.share.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.honor.share.protocol.PackageInvitation
import com.honor.share.protocol.ShareLink
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun QrScanScreen(model: ShareViewModel, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    DisposableEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
        onDispose { }
    }
    Column(Modifier.honorScreen()) {
        HonorTopBar(stringResource(R.string.scan_qr), onBack = { model.backHome() })
        Text(stringResource(R.string.scan_mac_first), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        val feedback = model.scanFeedback.collectAsState().value
        if (feedback != null) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(feedback), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(16.dp))
        if (!granted) {
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.camera_needed), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            HonorPrimaryButton(stringResource(R.string.open_settings), onClick = onOpenSettings)
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(28.dp)),
            ) {
                QrCamera(
                    modifier = Modifier.fillMaxSize(),
                    onCode = { raw ->
                        if (PackageInvitation.parse(raw) != null || ShareLink.parse(raw) != null) {
                            model.onLinkScanned(raw)
                            true
                        } else {
                            model.scanFeedback.value = R.string.error_invalid_invitation
                            false
                        }
                    },
                )
                Canvas(Modifier.fillMaxSize()) {
                    val dim = Color.Black.copy(alpha = 0.45f)
                    val hole = 240.dp.toPx()
                    val left = (size.width - hole) / 2f
                    val top = (size.height - hole) / 2f
                    drawRect(dim, size = ComposeSize(size.width, top))
                    drawRect(dim, topLeft = Offset(0f, top + hole), size = ComposeSize(size.width, size.height - top - hole))
                    drawRect(dim, topLeft = Offset(0f, top), size = ComposeSize(left, hole))
                    drawRect(dim, topLeft = Offset(left + hole, top), size = ComposeSize(size.width - left - hole, hole))
                    val len = 28.dp.toPx()
                    val stroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                    val white = Color.White
                    val corners = listOf(
                        Offset(left, top) to listOf(Offset(len, 0f), Offset(0f, len)),
                        Offset(left + hole, top) to listOf(Offset(-len, 0f), Offset(0f, len)),
                        Offset(left, top + hole) to listOf(Offset(len, 0f), Offset(0f, -len)),
                        Offset(left + hole, top + hole) to listOf(Offset(-len, 0f), Offset(0f, -len)),
                    )
                    corners.forEach { (origin, dirs) ->
                        dirs.forEach { dir ->
                            drawLine(white, origin, origin + dir, strokeWidth = stroke.width, cap = StrokeCap.Round)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.scan_frame_hint), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        var showCode by remember { mutableStateOf(false) }
        var typedCode by remember { mutableStateOf("") }
        if (showCode) {
            OutlinedTextField(
                value = typedCode,
                onValueChange = { typedCode = it.filter { ch -> ch.isDigit() }.take(6) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.enter_code)) },
            )
            Spacer(Modifier.height(8.dp))
            HonorPrimaryButton(stringResource(R.string.connect), enabled = typedCode.length == 6, onClick = { model.connectWithCode(typedCode) })
        } else {
            TextButton(onClick = { showCode = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.have_a_code))
            }
        }
    }
}

@Composable
private fun QrCamera(modifier: Modifier, onCode: (String) -> Boolean) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val found = remember { AtomicBoolean(false) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                val executor = Executors.newSingleThreadExecutor()
                val scanner = BarcodeScanning.getClient()
                analysis.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media == null || found.get()) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    scanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            val value = barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue
                            if (value != null && !found.get() && onCode(value)) {
                                found.set(true)
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                }
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (_: Exception) {
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}
