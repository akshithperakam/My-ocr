package com.codecapture.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.codecapture.ui.viewmodel.ReviewViewModel
import java.io.File

@Composable
fun ReviewCorrectionScreen(
    viewModel: ReviewViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val report = state.report

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Column {
                    Text(
                        report?.outputFileName ?: "Extraction Review",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (report != null) {
                        Text(
                            "${report.detectedLanguage.displayName} • ${(report.overallConfidence * 100).toInt()}% conf • ${report.uncertainLineCount} uncertain • ${report.diagnostics.size} checks",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            OutlinedButton(onClick = viewModel::rerunExtraction) {
                Text("Re-run")
            }
        }

        if (report == null) {
            Text(
                if (state.isLoading) "Loading extraction report..." else "No extraction output found yet for this session.",
                modifier = Modifier.padding(16.dp)
            )
            return
        }

        // Section 16.3 explicit limitation notice
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = report.limitationNotice,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp)
            )
        }

        // Language / Extension override selector (FR-19)
        val extensions = listOf(".kt", ".java", ".py", ".ts", ".tsx", ".js", ".jsx", ".sql", ".json", ".html", ".css", ".txt")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            extensions.forEach { ext ->
                FilterChip(
                    selected = report.detectedLanguage.suggestedExtension.equals(ext, ignoreCase = true),
                    onClick = { viewModel.overrideExtension(ext) },
                    label = { Text(ext) }
                )
            }
        }

        // Linked Original Photograph Comparison Panel for Selected Line (FR-21)
        val selectedLine = report.lines.getOrNull(state.selectedLineIndex)
        val sourceImagePath = selectedLine?.provenance?.firstOrNull()?.imagePath
        if (sourceImagePath != null && File(sourceImagePath).exists()) {
            val previewBitmap = remember(sourceImagePath) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeFile(sourceImagePath, opts)?.asImageBitmap()
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        "Source Photo: ${File(sourceImagePath).name} (Line ${state.selectedLineIndex + 1} • ${if (selectedLine.isUserCorrected) "User-corrected" else "Machine OCR ${(selectedLine.confidence * 100).toInt()}%"})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (previewBitmap != null) {
                        Image(
                            bitmap = previewBitmap,
                            contentDescription = "Original capture reference",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(130.dp)
                                .padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        // Editable Reconstructed Code Lines with Uncertainty & Gap Highlighting (FR-20, FR-21)
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(report.lines, key = { it.lineIndex }) { line ->
                val isUncertain = !line.isUserCorrected &&
                    (line.isGapMarker || line.confidence < 0.78f || line.ambiguities.isNotEmpty())
                val rowBg = when {
                    line.lineIndex == state.selectedLineIndex -> Color(0xFF1E3A5F)
                    line.isGapMarker -> Color(0xFF4A1D1D)
                    isUncertain -> Color(0xFF423214)
                    line.isUserCorrected -> Color(0xFF153823)
                    else -> MaterialTheme.colorScheme.surface
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(rowBg)
                        .clickable { viewModel.selectLine(line.lineIndex) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${line.lineIndex + 1}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.width(34.dp)
                    )
                    OutlinedTextField(
                        value = line.text,
                        onValueChange = { updated -> viewModel.editLineText(line.lineIndex, updated) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        state.saveBanner?.let { msg ->
            Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { viewModel.saveAndAccept(markFinal = false) },
                modifier = Modifier.weight(1f)
            ) {
                Text("Save Draft")
            }
            Button(
                onClick = { viewModel.saveAndAccept(markFinal = true) },
                modifier = Modifier.weight(1f)
            ) {
                Text("Accept File")
            }
        }
    }
}
