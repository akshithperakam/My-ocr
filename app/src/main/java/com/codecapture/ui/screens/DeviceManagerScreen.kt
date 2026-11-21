package com.codecapture.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.codecapture.domain.model.HidConnectionState
import com.codecapture.ui.viewmodel.CaptureViewModel

@Composable
fun DeviceManagerScreen(viewModel: CaptureViewModel) {
    val devices by viewModel.savedDevices.collectAsState()
    val selected by viewModel.selectedDevice.collectAsState()
    val hidState by viewModel.hidState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.refreshBondedDevices()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Paired Computers", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Pair your Pixel 8a with Windows, Linux, or macOS in Android Bluetooth settings, then connect here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                )
            }
            IconButton(onClick = viewModel::refreshBondedDevices) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh Bonded Devices")
            }
        }

        if (hidState is HidConnectionState.Connected) {
            OutlinedButton(onClick = viewModel::disconnectHid, modifier = Modifier.fillMaxWidth()) {
                Text("Disconnect Active HID Host")
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(devices, key = { it.id }) { profile ->
                val isSelected = selected?.id == profile.id
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(profile.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${profile.bluetoothAddress} • ${profile.scrollCalibration.linesPerWheelTick} lines/tick",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            IconButton(onClick = { viewModel.removeDevice(profile.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove profile")
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Invert scroll direction (macOS Natural Scroll)", style = MaterialTheme.typography.bodySmall)
                            Switch(
                                checked = profile.scrollCalibration.invertScrollDirection,
                                onCheckedChange = { viewModel.toggleInvertScroll(profile) }
                            )
                        }

                        Spacer(Modifier.height(2.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { viewModel.selectDevice(profile) }) {
                                Text(if (isSelected) "Selected" else "Select")
                            }
                            Button(onClick = {
                                viewModel.selectDevice(profile)
                                viewModel.connectSelectedDevice()
                            }) {
                                Text("Connect HID")
                            }
                        }
                    }
                }
            }
        }
    }
}
