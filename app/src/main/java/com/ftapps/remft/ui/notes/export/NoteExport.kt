package com.ftapps.remft.ui.notes.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.FileProvider
import com.ftapps.remft.BuildConfig
import com.ftapps.remft.R
import com.ftapps.remft.data.notes.NotesRepository
import com.ftapps.remft.domain.notes.export.MarkdownBlocks
import com.ftapps.remft.domain.notes.export.NoteBlock
import com.ftapps.remft.domain.notes.export.NoteDocx
import com.ftapps.remft.domain.notes.export.NoteExportLabels
import com.ftapps.remft.domain.notes.export.NoteMarkdownFile
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.domain.time.DateFormats
import com.ftapps.remft.domain.time.UtcText
import com.ftapps.remft.ui.notes.markdown.decodeSampled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.ZoneId

enum class NoteExportFormat(
    val extension: String,
    val mime: String,
    @param:StringRes val menuLabel: Int,
    @param:StringRes val formatLabel: Int,
    val icon: ImageVector,
) {
    Pdf("pdf", NotePdfWriter.MIME_TYPE, R.string.note_exportPdf, R.string.note_exportFormatPdf, Icons.Outlined.PictureAsPdf),
    Markdown("md", NoteMarkdownFile.MIME_TYPE, R.string.note_exportMd, R.string.note_exportFormatMd, Icons.AutoMirrored.Outlined.Article),
    Docx("docx", NoteDocx.MIME_TYPE, R.string.note_exportDocx, R.string.note_exportFormatDocx, Icons.Outlined.Description),
}

data class NoteExportResult(val file: File, val format: NoteExportFormat, val displayName: String)

class NoteExporter(private val context: Context, private val repository: NotesRepository) {
    private fun labels() = NoteExportLabels(
        alertNote = context.getString(R.string.markdown_alert_note),
        alertTip = context.getString(R.string.markdown_alert_tip),
        alertImportant = context.getString(R.string.markdown_alert_important),
        alertWarning = context.getString(R.string.markdown_alert_warning),
        alertCaution = context.getString(R.string.markdown_alert_caution),
        mediaMissing = context.getString(R.string.note_exportMediaMissing),
        unsupportedBlock = context.getString(R.string.note_exportUnsupportedBlock),
        frontMatterTitle = context.getString(R.string.markdown_frontMatter),
    )

    fun editedLabel(updatedAt: String?, format: DateFormatId): String? {
        val instant = UtcText.parse(updatedAt) ?: return null
        val local = instant.atZone(ZoneId.systemDefault()).toLocalDateTime()
        return context.getString(R.string.note_edited).fill("date" to DateFormats.dateTime(local, format))
    }

    suspend fun export(
        format: NoteExportFormat,
        title: String,
        markdown: String,
        updatedAt: String?,
        dateFormat: DateFormatId,
    ): NoteExportResult = withContext(Dispatchers.IO) {
        val name = NoteMarkdownFile.fileName(title, format.extension)
        val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, name)
        when (format) {
            NoteExportFormat.Markdown -> out.writeBytes(NoteMarkdownFile.bytes(title, markdown))
            NoteExportFormat.Docx -> {
                val blocks = MarkdownBlocks.parse(markdown)
                val images = loadImageBytes(blocks)
                out.writeBytes(NoteDocx.build(title, editedLabel(updatedAt, dateFormat), blocks, labels()) { images[it] })
            }
            NoteExportFormat.Pdf -> {
                val blocks = MarkdownBlocks.parse(markdown)
                val files = imageFiles(blocks)
                val bitmaps = HashMap<String, Bitmap?>()
                FileOutputStream(out).use { stream ->
                    NotePdfWriter.write(stream, title, editedLabel(updatedAt, dateFormat), blocks, labels()) { id ->
                        bitmaps.getOrPut(id) { files[id]?.let { decodeSampled(it, 1400) } }
                    }
                }
            }
        }
        NoteExportResult(out, format, name)
    }

    private suspend fun imageFiles(blocks: List<NoteBlock>): Map<String, File> {
        val out = HashMap<String, File>()
        blocks.filterIsInstance<NoteBlock.Image>().map { it.fileId }.distinct().forEach { id ->
            repository.attachmentFile(id)?.let { out[id] = it }
        }
        return out
    }

    private suspend fun loadImageBytes(blocks: List<NoteBlock>): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        blocks.filterIsInstance<NoteBlock.Image>().map { it.fileId }.distinct().forEach { id ->
            repository.attachmentFile(id)?.takeIf { it.length() < MAX_IMAGE_BYTES }?.let { out[id] = it.readBytes() }
        }
        return out
    }

    fun uriFor(result: NoteExportResult): Uri = FileProvider.getUriForFile(context, BuildConfig.FILES_AUTHORITY, result.file)

    fun shareIntent(result: NoteExportResult): Intent {
        val send = Intent(Intent.ACTION_SEND)
            .setType(result.format.mime)
            .putExtra(Intent.EXTRA_STREAM, uriFor(result))
            .putExtra(Intent.EXTRA_TITLE, result.displayName)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    suspend fun saveTo(result: NoteExportResult, target: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(target, "wt")?.use { output ->
                FileInputStream(result.file).use { it.copyTo(output) }
            } != null
        }.getOrDefault(false)
    }

    fun print(activityContext: Context, result: NoteExportResult) {
        if (result.format != NoteExportFormat.Pdf) return
        val manager = activityContext.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        manager.print(result.displayName, PdfPrintAdapter(result.file, result.displayName), PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
    }

    companion object {
        const val EXPORT_DIR = "exports"
        private const val MAX_IMAGE_BYTES = 25L * 1024 * 1024
    }
}

private class PdfPrintAdapter(private val file: File, private val name: String) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(name)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
            .build()
        callback.onLayoutFinished(info, oldAttributes != newAttributes)
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        try {
            FileInputStream(file).use { input -> FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) } }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message)
        }
    }
}
