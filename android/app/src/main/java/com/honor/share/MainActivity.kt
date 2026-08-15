package com.honor.share

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.honor.share.ui.Screen
import com.honor.share.ui.ShareViewModel
import com.honor.share.ui.HonorShareRoot

class MainActivity : ComponentActivity() {
    private val model: ShareViewModel by viewModels { ShareViewModelFactory(application as HonorShareApplication) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val nearbyOk = if (Build.VERSION.SDK_INT >= 33) {
            result[Manifest.permission.NEARBY_WIFI_DEVICES] != false
        } else {
            result[Manifest.permission.ACCESS_FINE_LOCATION] != false
        }
        if (result.isNotEmpty() && !nearbyOk) {
            model.screen.value = Screen.PERMISSION
        } else {
            model.onNearbyPermissionGranted()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val app = application as HonorShareApplication
        app.container.sasBridge.impl = { sas, name -> model.onSas(sas, name) }
        app.container.incomingBridge.impl = { request, _ -> model.onIncoming(request) }
        handleShareIntent(intent)
        permissionLauncher.launch(nearbyPermissions())
        setContent {
            val screen = model.screen.collectAsState().value
            LaunchedEffect(screen) {
                if (screen == Screen.HOME || screen == Screen.SELECTED || screen == Screen.DEVICES || screen == Screen.RECEIVE || screen == Screen.PACKAGE || screen == Screen.SCAN) {
                    permissionLauncher.launch(nearbyPermissions())
                    startForegroundTransfer()
                }
                if (screen == Screen.TRANSFER) startForegroundTransfer()
            }
            HonorShareRoot(
                model = model,
                onOpenSettings = {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", packageName, null)
                        },
                    )
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
            }
        }
        if (uris.isNotEmpty()) model.onUrisPicked(uris)
    }

    private fun startForegroundTransfer() {
        val intent = Intent(this, TransferForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    companion object {
        fun nearbyPermissions(): Array<String> {
            val list = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            if (Build.VERSION.SDK_INT >= 33) {
                list += Manifest.permission.NEARBY_WIFI_DEVICES
                list += Manifest.permission.POST_NOTIFICATIONS
            }
            return list.toTypedArray()
        }
    }
}

class ShareViewModelFactory(private val app: HonorShareApplication) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val c = app.container
        return ShareViewModel(
            application = app,
            identity = c.identity,
            discovery = c.discovery,
            transfer = c.transfer,
            history = c.history,
            radio = c.radio,
            saf = c.saf,
            scanner = c.scanner,
            listenPort = { c.transfer.listenPort },
        ) as T
    }
}
