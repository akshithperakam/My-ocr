package com.codecapture.ui.screens

import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.codecapture.camera.CameraCaptureManager
import com.codecapture.domain.model.CapturePhase
import com.codecapture.domain.model.HidConnectionState
import com.codecapture.domain.model.OperatingMode
import com.codecapture.ui.viewmodel.CaptureViewModel
import kotlin.math.roundToInt

@Composable
fun MainCaptureScreen(
    viewModel: CaptureViewModel,
    cameraManager: CameraCaptureManager,
    onOpenDevices: () -> Unit,
    onOpenSessions: () -> Unit
) {
    val hud by viewModel.hudState.collectAsState()
    val hidState by viewModel.hidState.collectAsState()
    val config by viewModel.config.collectAsState()
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    val sessionName by viewModel.sessionName.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(Unit) {
        onDispose { cameraManager.release() }
    }

    val isAutoRunning = hud.phase in setOf(
        CapturePhase.CAPTURING,
        CapturePhase.ANALYSING,
        CapturePhase.SCROLLING,
        CapturePhase.VERIFYING,
        CapturePhase.PAUSED
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Top Status Bar: Bluetooth HID + Selected Computer
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val connected = hidState is HidConnectionState.Connected
                    Icon(
                        imageVector = if (connected) Icons.Default.BluetoothConnected else Icons.Default.BluetoothDisabled,
                        contentDescription = "Bluetooth HID Status",
                        tint = if (connected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = when (val s = hidState) {
                                is HidConnectionState.Connected -> "HID Connected: ${s.deviceName}"
                                is HidConnectionState.Connecting -> "Connecting to ${s.deviceName}..."
                                is HidConnectionState.ReadyForConnection -> "HID Ready (${selectedDevice?.displayName ?: "Select computer"})"
                                is HidConnectionState.Error -> "HID Error: ${s.message}"
                                else -> "HID Disconnected"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Mode: ${config.operatingMode.name} | Target Overlap: ${(config.targetOverlapRatio * 100).roundToInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                        )
                    }
                }
                OutlinedButton(onClick = onOpenDevices) {
                    Text("Computers")
                }
            }
        }

        // CameraX Preview + Editor Region Overlay + Auto-Capture HUD
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black, RoundedCornerShape(12.dp))
        ) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FIT_CENTER
                        cameraManager.bindToLifecycle(
                            lifecycleOwner = lifecycleOwner,
                            previewView = this,
                            jpegQuality = config.jpegQuality
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Draw detected or manual editor viewport rectangle
            val region = hud.manualRegion ?: hud.calibration?.editorRegion
            if (region != null) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawRect(
                        color = Color(0xFF4FC3F7),
                        topLeft = Offset(region.left * size.width, region.top * size.height),
                        size = Size(region.width * size.width, region.height * size.height),
                        style = Stroke(width = 4f)
                    )
                }
            }

            // Live Auto-Capture Metrics Overlay (Section 7)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xCC0E141B)),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "Capture #${hud.capturedCount}  •  ${hud.phase.name}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Overlap: ${hud.lastEstimatedOverlapRatio?.let { "${(it * 100).roundToInt()}%" } ?: "--"}  |  Conf: ${(hud.lastConfidence * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "Sharpness: ${hud.lastQuality?.sharpnessScore?.let { "%.1f".format(it) } ?: "--"}  |  Glare: ${hud.lastQuality?.glareRatio?.let { "${(it * 100).roundToInt()}%" } ?: "--"}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = hud.scrollStatusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            // Explicit Error / Warning Banner
            hud.activeError?.let { err ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xDD7F1D1D)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(10.dp)
                ) {
                    Text(
                        text = "${err.code.name}: ${err.message}",
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White
                    )
                }
            }
        }

        // Session Name + Operating Mode (Fixed / Handheld) + Overlap Slider
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = sessionName,
                onValueChange = viewModel::updateSessionName,
                label = { Text("Session") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = config.operatingMode == OperatingMode.FIXED,
                onClick = { viewModel.setOperatingMode(OperatingMode.FIXED) },
                label = { Text("Fixed") }
            )
            FilterChip(
                selected = config.operatingMode == OperatingMode.HANDHELD,
                onClick = { viewModel.setOperatingMode(OperatingMode.HANDHELD) },
                label = { Text("Handheld") }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Overlap ${(config.targetOverlapRatio * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.width(96.dp)
            )
            Slider(
                value = config.targetOverlapRatio,
                onValueChange = viewModel::setTargetOverlap,
                valueRange = 0.15f..0.40f,
                modifier = Modifier.weight(1f)
            )
        }

        // Manual Controls (Always available per FR-10) + Automatic Capture Controls (FR-11)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = viewModel::manualScrollUp) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "Manual Scroll Up")
                }
                IconButton(onClick = viewModel::manualScrollDown) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = "Manual Scroll Down")
                }
                OutlinedButton(onClick = viewModel::calibrate) {
                    Text("Calibrate")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = viewModel::manualCapture) {
                    Icon(Icons.Default.Camera, contentDescription = "Manual Capture")
                    Spacer(Modifier.width(4.dp))
                    Text("Snap")
                }

                if (!isAutoRunning) {
                    Button(onClick = viewModel::startAutoCapture) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Start Auto Capture")
                        Spacer(Modifier.width(4.dp))
                        Text("Auto")
                    }
                } else {
                    if (hud.isPaused) {
                        Button(onClick = viewModel::resumeAutoCapture) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Resume")
                        }
                    } else {
                        OutlinedButton(onClick = viewModel::pauseAutoCapture) {
                            Icon(Icons.Default.Pause, contentDescription = "Pause")
                        }
                    }
                    Button(
                        onClick = viewModel::stopAndFinishSession,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop & Extract")
                    }
                }
            }
        }

        if (hud.phase == CapturePhase.END_OF_CONTENT) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = viewModel::overrideEndOfContent, modifier = Modifier.weight(1f)) {
                    Text("Override EOF")
                }
                Button(onClick = onOpenSessions, modifier = Modifier.weight(1f)) {
                    Text("View Extracted Code")
                }
            }
        }
    }
}
