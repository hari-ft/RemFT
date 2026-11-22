package com.ftapps.remft.ui.files

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ftapps.remft.R
import com.ftapps.remft.data.db.entity.FileRowEntity
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.domain.time.DateFormats
import com.ftapps.remft.domain.time.UtcText
import com.ftapps.remft.ui.tasks.SwipeToReveal
import com.ftapps.remft.ui.theme.Radius
import com.ftapps.remft.ui.theme.RemftThemeExtras
import com.ftapps.remft.ui.theme.Space
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

object FileSizes {
    data class Parts(val value: String, val unit: Int)

    private val units = listOf(R.string.files_sizeKb, R.string.files_sizeMb, R.string.files_sizeGb, R.string.files_sizeTb)

    fun parts(bytes: Long): Parts {
        if (bytes < 1024) return Parts(bytes.toString(), R.string.files_sizeBytes)
        var value = bytes.toDouble()
        var unit = -1
        do {
            value /= 1024
            unit += 1
        } while (value >= 1024 && unit < units.size - 1)
        val text = if (value >= 100) String.format(Locale.ROOT, "%.0f", value) else String.format(Locale.ROOT, "%.1f", value)
        return Parts(text, units[unit])
    }
}

@Composable
fun formatBytes(bytes: Long): String {
    val parts = FileSizes.parts(bytes)
    return stringResource(parts.unit).fill("n" to parts.value)
}

fun kindIcon(mime: String): ImageVector = when {
    mime.startsWith("image/") -> Icons.Outlined.Image
    mime.startsWith("video/") -> Icons.Outlined.Movie
    mime.startsWith("audio/") -> Icons.Outlined.Audiotrack
    mime == "application/pdf" -> Icons.Outlined.PictureAsPdf
    mime == "application/zip" || mime == "application/gzip" -> Icons.Outlined.FolderZip
    else -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

fun FileRowEntity.isImage(): Boolean = mime.startsWith("image/")

fun fileDate(text: String?, format: DateFormatId, zone: ZoneId): String? {
    val instant = UtcText.parse(text) ?: return null
    return DateFormats.date(LocalDateTime.ofInstant(instant, zone), format)
}

@Composable
fun KindTile(icon: ImageVector, modifier: Modifier = Modifier, error: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .size(40.dp)
            .background(if (error) scheme.errorContainer else scheme.surfaceContainerHighest, RoundedCornerShape(Radius.chip)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (error) scheme.onErrorContainer else scheme.onSurfaceVariant)
    }
}

@Composable
fun FileThumb(row: FileRowEntity, file: File?) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(Radius.chip))) {
        KindTile(kindIcon(row.mime))
        if (row.isImage() && file != null) {
            AsyncImage(
                model = file,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(40.dp).testTag("file-thumb-${row.id}"),
            )
        }
    }
}

@Composable
fun FileRowCard(
    row: FileRowEntity,
    env: FilesEnvironment,
    format: DateFormatId,
    onOpen: () -> Unit,
    onMore: () -> Unit,
    onDelete: () -> Unit,
    highlighted: Boolean = false,
    badge: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val file = remember(row.id, row.localPath) { env.attachments.fileOf(row) }
    val subtitle = listOfNotNull(formatBytes(row.sizeBytes), fileDate(row.createdAt, format, env.clock.zone)).joinToString(" · ")
    SwipeToReveal(
        id = row.id,
        deleteDescription = stringResource(R.string.file_deleteConfirm).fill("name" to row.name),
        onDelete = onDelete,
        modifier = Modifier.padding(vertical = Space.x1),
    ) {
        Card(
            shape = RoundedCornerShape(Radius.card),
            colors = CardDefaults.cardColors(
                containerColor = if (highlighted) scheme.primaryContainer.copy(alpha = 0.45f).compositeOver(scheme.surface) else scheme.surface,
            ),
            border = BorderStroke(1.dp, if (highlighted) scheme.primary.copy(alpha = 0.45f) else RemftThemeExtras.colors.hairline),
            modifier = Modifier.fillMaxWidth().testTag("file-row-${row.id}"),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onOpen).padding(start = Space.x3, end = Space.x1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FileThumb(row, file)
                Spacer(Modifier.width(Space.x3))
                Column(Modifier.weight(1f).padding(vertical = Space.x2)) {
                    Text(row.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1)
                }
                if (badge != null) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 140.dp)
                            .background(scheme.surfaceContainerHighest, RoundedCornerShape(Radius.capsule))
                            .padding(horizontal = Space.x2, vertical = 3.dp)
                            .testTag("file-badge-${row.id}"),
                    )
                }
                IconButton(onClick = onMore, modifier = Modifier.testTag("file-menu-${row.id}")) {
                    Icon(Icons.Outlined.MoreHoriz, contentDescription = stringResource(R.string.file_fileActions))
                }
            }
        }
    }
}

@Composable
fun UploadRowCard(job: UploadJob, env: FilesEnvironment, onRetry: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val failed = job.phase == UploadPhase.Failed
    Card(
        shape = RoundedCornerShape(Radius.card),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        border = BorderStroke(1.dp, RemftThemeExtras.colors.hairline),
        modifier = Modifier.fillMaxWidth().padding(vertical = Space.x1).testTag("upload-row-${job.localId}"),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = Space.x3, end = Space.x1), verticalAlignment = Alignment.CenterVertically) {
            KindTile(if (failed) Icons.Outlined.ErrorOutline else Icons.Outlined.UploadFile, error = failed)
            Spacer(Modifier.width(Space.x3))
            Column(Modifier.weight(1f).padding(vertical = Space.x2)) {
                Text(job.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (failed) {
                    Text(
                        stringResource(if (job.errorCode == "FILE_TOO_LARGE") R.string.error_FILE_TOO_LARGE else R.string.file_uploadFailed),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.error,
                        modifier = Modifier.testTag("upload-error-${job.localId}"),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = Space.x2))
                }
            }
            if (failed) {
                if (job.errorCode != "FILE_TOO_LARGE") {
                    IconButton(onClick = onRetry) { Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.common_retry)) }
                }
                IconButton(onClick = { env.uploads.dismiss(job.localId) }, modifier = Modifier.testTag("upload-dismiss-${job.localId}")) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.common_close))
                }
            }
        }
    }
}

@Composable
fun FolderLeadingTile() {
    KindTile(Icons.Outlined.Folder)
}
