package com.ftapps.remft.ui.files

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ftapps.remft.R
import com.ftapps.remft.data.db.entity.FileRowEntity
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.domain.time.DateFormats
import com.ftapps.remft.domain.time.UtcText
import com.ftapps.remft.ui.components.ChipTone
import com.ftapps.remft.ui.components.GroupedCardItem
import com.ftapps.remft.ui.components.PreviewSurface
import com.ftapps.remft.ui.components.StatusChip
import com.ftapps.remft.ui.tasks.SwipeToReveal
import com.ftapps.remft.ui.theme.GroupPosition
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Space
import com.ftapps.remft.ui.theme.groupPosition
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

enum class TileTone { File, Folder, Error }

@Composable
fun KindTile(icon: ImageVector, modifier: Modifier = Modifier, error: Boolean = false) {
    KindTile(icon, if (error) TileTone.Error else TileTone.File, modifier)
}

@Composable
fun KindTile(icon: ImageVector, tone: TileTone, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val file = RemftTheme.colors.file
    val (container, content) = when (tone) {
        TileTone.File -> file.container to file.onContainer
        TileTone.Folder -> scheme.primaryContainer to scheme.onPrimaryContainer
        TileTone.Error -> scheme.errorContainer to scheme.onErrorContainer
    }
    Box(
        modifier
            .size(40.dp)
            .background(container, MarkedShapes.tile),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun FileThumb(row: FileRowEntity, file: File?) {
    Box(Modifier.size(40.dp).clip(MarkedShapes.tile)) {
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
fun FileCardContainer(
    position: GroupPosition,
    tag: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val color = if (highlighted) scheme.primaryContainer.copy(alpha = 0.45f).compositeOver(scheme.surface) else scheme.surface
    if (position == GroupPosition.Single) {
        Surface(
            shape = MarkedShapes.medium,
            color = color,
            border = if (highlighted) BorderStroke(1.dp, scheme.primary.copy(alpha = 0.45f)) else null,
            modifier = modifier.fillMaxWidth().testTag(tag),
        ) { content() }
    } else {
        GroupedCardItem(position, modifier.testTag(tag), color = color) { content() }
    }
}

@Composable
fun FileRowLayout(
    leading: @Composable () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    below: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    val click = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        modifier.fillMaxWidth().heightIn(min = 68.dp).then(click).padding(start = Space.x3, end = Space.x1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(Space.x3))
        Column(Modifier.weight(1f).padding(vertical = Space.x2), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            below()
        }
        trailing()
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
    position: GroupPosition = GroupPosition.Single,
) {
    val file = remember(row.id, row.localPath) { env.attachments.fileOf(row) }
    val subtitle = listOfNotNull(formatBytes(row.sizeBytes), fileDate(row.createdAt, format, env.clock.zone)).joinToString(" · ")
    SwipeToReveal(
        id = row.id,
        deleteDescription = stringResource(R.string.file_deleteConfirm).fill("name" to row.name),
        onDelete = onDelete,
        modifier = if (position == GroupPosition.Single) Modifier.padding(vertical = Space.x1) else Modifier,
    ) {
        FileCardContainer(position, "file-row-${row.id}", highlighted = highlighted) {
            FileRowContent(row.name, subtitle, badge, row.id, onOpen, onMore, leading = { FileThumb(row, file) })
        }
    }
}

@Composable
private fun FileRowContent(
    name: String,
    subtitle: String,
    badge: String?,
    id: String,
    onOpen: () -> Unit,
    onMore: () -> Unit,
    leading: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    FileRowLayout(
        leading = leading,
        title = name,
        onClick = onOpen,
        below = {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1)
            if (badge != null) {
                StatusChip(
                    badge,
                    ChipTone.File,
                    modifier = Modifier.padding(top = Space.x1).widthIn(max = 240.dp).testTag("file-badge-$id"),
                )
            }
        },
        trailing = {
            IconButton(onClick = onMore, modifier = Modifier.testTag("file-menu-$id")) {
                Icon(Icons.Outlined.MoreHoriz, contentDescription = stringResource(R.string.file_fileActions), tint = scheme.onSurfaceVariant)
            }
        },
    )
}

@Composable
fun UploadRowCard(job: UploadJob, env: FilesEnvironment, position: GroupPosition = GroupPosition.Single, onRetry: () -> Unit) {
    val failed = job.phase == UploadPhase.Failed
    val errorText = if (failed) {
        stringResource(if (job.errorCode == "FILE_TOO_LARGE") R.string.error_FILE_TOO_LARGE else R.string.file_uploadFailed)
    } else {
        null
    }
    FileCardContainer(
        position,
        "upload-row-${job.localId}",
        modifier = if (position == GroupPosition.Single) Modifier.padding(vertical = Space.x1) else Modifier,
    ) {
        UploadRowContent(
            name = job.name,
            errorText = errorText,
            canRetry = job.errorCode != "FILE_TOO_LARGE",
            id = job.localId.toString(),
            onRetry = onRetry,
            onDismiss = { env.uploads.dismiss(job.localId) },
        )
    }
}

@Composable
private fun UploadRowContent(
    name: String,
    errorText: String?,
    canRetry: Boolean,
    id: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val failed = errorText != null
    val file = RemftTheme.colors.file
    FileRowLayout(
        leading = { KindTile(if (failed) Icons.Outlined.ErrorOutline else Icons.Outlined.UploadFile, error = failed) },
        title = name,
        below = {
            if (errorText != null) {
                Text(
                    errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("upload-error-$id"),
                )
            } else {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().padding(top = Space.x2, end = Space.x3).clip(MarkedShapes.pill),
                    color = file.color,
                    trackColor = file.container,
                )
            }
        },
        trailing = {
            if (failed) {
                if (canRetry) {
                    IconButton(onClick = onRetry) { Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.common_retry)) }
                }
                IconButton(onClick = onDismiss, modifier = Modifier.testTag("upload-dismiss-$id")) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.common_close))
                }
            }
        },
    )
}

@Composable
fun FolderLeadingTile() {
    KindTile(Icons.Outlined.Folder, TileTone.Folder)
}

@Preview(name = "File rows light")
@Composable
private fun FileRowsLightPreview() {
    RemftTheme(darkTheme = false) { FileRowsPreviewContent() }
}

@Preview(name = "File rows dark")
@Composable
private fun FileRowsDarkPreview() {
    RemftTheme(darkTheme = true) { FileRowsPreviewContent() }
}

@Composable
private fun FileRowsPreviewContent() {
    val samples = listOf(
        Triple("Lease agreement.pdf", "1.2 MB · 14 Nov 2026", "application/pdf"),
        Triple("Balcony plants.jpg", "860 KB · 12 Nov 2026", "image/jpeg"),
        Triple("Recipes.zip", "4.8 MB · 2 Nov 2026", "application/zip"),
    )
    PreviewSurface {
        Column {
            FileCardContainer(GroupPosition.Single, "preview-folder", modifier = Modifier.padding(bottom = Space.x3)) {
                FileRowLayout(leading = { FolderLeadingTile() }, title = "Home documents", onClick = {}, below = {
                    Text("4 items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                })
            }
            samples.forEachIndexed { index, (name, subtitle, mime) ->
                FileCardContainer(groupPosition(index, samples.size + 1), "preview-$index", highlighted = index == 1) {
                    FileRowContent(
                        name,
                        subtitle,
                        if (index == 0) "Task · Renew the lease" else null,
                        "preview-$index",
                        onOpen = {},
                        onMore = {},
                        leading = { KindTile(kindIcon(mime)) },
                    )
                }
            }
            FileCardContainer(GroupPosition.Last, "preview-upload") {
                UploadRowContent("Holiday photos.png", errorText = "Upload failed", canRetry = true, id = "u", onRetry = {}, onDismiss = {})
            }
        }
    }
}
