package com.codecapture.storage

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.codecapture.domain.model.ExtractionReport
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Dual-output session exporter (FR-13, FR-15, FR-22, AC-19):
 * - Exports ZIP archive containing BOTH original photographs and reconstructed source files.
 * - Generates an offline PDF report combining session metadata, extracted code, uncertainty notes, and thumbnails.
 * - Produces Android ShareSheet intents backed by FileProvider.
 */
class SessionExporter(private val context: Context) {

    private val exportCacheDir: File
        get() = File(context.cacheDir, "exports").apply { mkdirs() }

    fun exportDualOutputZip(sessionDir: File, sessionName: String): File {
        val safeName = sessionName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "CodeCapture" }
        val zipFile = File(exportCacheDir, "${safeName}_dual_output.zip")

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            sessionDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val relPath = file.relativeTo(sessionDir).path
                zos.putNextEntry(ZipEntry(relPath))
                FileInputStream(file).use { fis -> fis.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return zipFile
    }

    fun generateSessionPdfReport(
        sessionName: String,
        photoFiles: List<File>,
        report: ExtractionReport?
    ): File {
        val safeName = sessionName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "CodeCapture" }
        val pdfFile = File(exportCacheDir, "${safeName}_report.pdf")
        val document = PdfDocument()

        val pageWidth = 595
        val pageHeight = 842
        val margin = 36f

        val titlePaint = Paint().apply {
            color = Color.rgb(20, 35, 60)
            textSize = 18f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val bodyPaint = Paint().apply {
            color = Color.DKGRAY
            textSize = 10f
            typeface = Typeface.SANS_SERIF
        }
        val codePaint = Paint().apply {
            color = Color.BLACK
            textSize = 8.5f
            typeface = Typeface.MONOSPACE
        }
        val warnPaint = Paint().apply {
            color = Color.rgb(180, 75, 0)
            textSize = 8.5f
            typeface = Typeface.MONOSPACE
        }

        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        var canvas: Canvas = page.canvas
        var y = margin + 16f

        canvas.drawText("CodeCapture Session Report — $sessionName", margin, y, titlePaint)
        y += 22f
        canvas.drawText("Original photographs preserved: ${photoFiles.size}", margin, y, bodyPaint)
        y += 16f

        if (report != null) {
            canvas.drawText(
                "Language: ${report.detectedLanguage.displayName} (${report.detectedLanguage.suggestedExtension}) | " +
                    "Confidence: ${(report.overallConfidence * 100).toInt()}% | " +
                    "Uncertain lines: ${report.uncertainLineCount} | Flagged gaps: ${report.flaggedGapCount}",
                margin,
                y,
                bodyPaint
            )
            y += 16f
            canvas.drawText(report.limitationNotice.take(105), margin, y, warnPaint)
            y += 22f

            report.lines.forEach { line ->
                if (y > pageHeight - margin - 20f) {
                    document.finishPage(page)
                    pageNumber++
                    page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
                    canvas = page.canvas
                    y = margin + 16f
                }
                val prefix = "${"%4d".format(line.lineIndex + 1)} | "
                val flag = if (line.isGapMarker || line.confidence < 0.78f || line.ambiguities.isNotEmpty()) " [?]" else ""
                val paint = if (flag.isNotEmpty()) warnPaint else codePaint
                canvas.drawText((prefix + line.text + flag).take(98), margin, y, paint)
                y += 12f
            }
        } else {
            canvas.drawText("No automatic extraction report generated yet.", margin, y, bodyPaint)
        }
        document.finishPage(page)

        // Append original reference photographs (1 per page)
        photoFiles.forEachIndexed { idx, photo ->
            if (!photo.exists()) return@forEachIndexed
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            val bmp = BitmapFactory.decodeFile(photo.absolutePath, opts) ?: return@forEachIndexed
            pageNumber++
            val imgPage = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            val c = imgPage.canvas
            c.drawText("Reference Photograph #${idx + 1}: ${photo.name}", margin, margin + 12f, titlePaint)
            val availW = pageWidth - 2 * margin
            val availH = pageHeight - 2 * margin - 30f
            val scale = minOf(availW / bmp.width, availH / bmp.height)
            val drawW = (bmp.width * scale).toInt()
            val drawH = (bmp.height * scale).toInt()
            val dest = android.graphics.Rect(
                margin.toInt(),
                (margin + 24f).toInt(),
                (margin + drawW).toInt(),
                (margin + 24f + drawH).toInt()
            )
            c.drawBitmap(bmp, null, dest, null)
            bmp.recycle()
            document.finishPage(imgPage)
        }

        FileOutputStream(pdfFile).use { fos -> document.writeTo(fos) }
        document.close()
        return pdfFile
    }

    fun buildShareIntent(file: File, mimeType: String, title: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
