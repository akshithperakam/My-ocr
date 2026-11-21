package com.codecapture.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.codecapture.domain.model.ExtractionStatus
import com.codecapture.ui.viewmodel.SessionsViewModel

@Composable
fun SessionGalleryScreen(
    viewModel: SessionsViewModel,
    onOpenReview: (String) -> Unit
) {
    val sessions by viewModel.sessions.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Capture Sessions", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Every session preserves original photographs alongside automatically extracted source code.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
        )

        if (sessions.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "No capture sessions yet. Capture a file from the Capture tab to begin.",
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(sessions, key = { it.sessionId }) { session ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        session.sessionName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        "${session.dateFolder} • ${session.frameCount} photos • ${session.operatingMode.name}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                AssistChip(
                                    onClick = {},
                                    label = { Text(session.extractionStatus.name) }
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (session.extractionStatus in setOf(
                                        ExtractionStatus.COMPLETED,
                                        ExtractionStatus.COMPLETED_WITH_WARNINGS
                                    )
                                ) {
                                    Button(onClick = { onOpenReview(session.sessionId) }) {
                                        Text("Review Code")
                                    }
                                }

                                if (session.extractionStatus == ExtractionStatus.RUNNING) {
                                    OutlinedButton(onClick = { viewModel.cancelExtraction(session.sessionId) }) {
                                        Text("Cancel OCR")
                                    }
                                } else {
                                    OutlinedButton(onClick = { viewModel.rerunExtraction(session.sessionId) }) {
                                        Text("Re-run OCR")
                                    }
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    viewModel.exportDualOutputZip(session.sessionId) { shareIntent ->
                                        context.startActivity(Intent.createChooser(shareIntent, "Export Dual Output"))
                                    }
                                }) {
                                    Text("Export ZIP")
                                }
                                OutlinedButton(onClick = {
                                    viewModel.exportPdfReport(session.sessionId) { shareIntent ->
                                        context.startActivity(Intent.createChooser(shareIntent, "Share PDF Report"))
                                    }
                                }) {
                                    Text("PDF Report")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
