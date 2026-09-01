package com.driverremote.agent.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import com.driverremote.agent.remote.AccessibilityRemoteService
import com.driverremote.agent.service.RemoteForegroundService
import com.driverremote.agent.ui.theme.DarkBackground
import com.driverremote.agent.ui.theme.DriverRemoteTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, RemoteForegroundService::class.java).apply {
                action = RemoteForegroundService.ACTION_START_SCREEN_CAPTURE
                putExtra(RemoteForegroundService.EXTRA_PROJECTION_DATA, result.data)
            }
            startService(intent)
            viewModel.addLog("MediaProjection granted. WebRTC VideoTrack streaming.")
        } else {
            viewModel.addLog("MediaProjection permission was cancelled.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DriverRemoteTheme {
                Surface(color = DarkBackground) {
                    DriverRemoteMainScreen(
                        state = viewModel.uiState.value,
                        onServerUrlChanged = { viewModel.updateServerUrl(it) },
                        onRefreshPairingCode = { viewModel.generateNewPairingCode() },
                        onStartAgent = { viewModel.startAgentService() },
                        onStopAgent = { viewModel.stopAgentService() },
                        onRequestScreenCapture = {
                            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
                        },
                        onStopScreenCapture = { viewModel.stopScreenCapture() },
                        onOpenAccessibilitySettings = {
                            startActivity(AccessibilityRemoteService.openAccessibilitySettingsIntent())
                        },
                        onCopyText = { _, _ -> }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkAccessibilityStatus()
        viewModel.bindService()
    }
}