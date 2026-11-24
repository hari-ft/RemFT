package com.ftapps.remft.ui.files

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.ftapps.remft.R
import com.ftapps.remft.data.alarm.AppLogs
import com.ftapps.remft.data.db.entity.FileRowEntity
import com.ftapps.remft.data.files.AttachmentFetchFailed
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.ui.components.RemftIconButton
import com.ftapps.remft.ui.components.RoundIconButton
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import java.io.File

object ViewerZoom {
    const val MAX_SCALE = 6f
    const val DOUBLE_TAP_SCALE = 2.5f

    fun clamp(scale: Float): Float = scale.coerceIn(1f, MAX_SCALE)

    fun toggle(scale: Float): Float = if (scale > 1.01f) 1f else DOUBLE_TAP_SCALE

    fun clampOffset(offset: Offset, scale: Float, width: Float, height: Float): Offset {
        if (scale <= 1f) return Offset.Zero
        val maxX = width * (scale - 1f) / 2f
        val maxY = height * (scale - 1f) / 2f
        return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }
}

@Composable
fun ImageViewer(request: ViewerRequest, env: FilesEnvironment, onClose: () -> Unit, onMessage: (String) -> Unit) {
    val ids = remember(request) { mutableStateListOf(*request.ids.toTypedArray()) }
    if (ids.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        RemftTheme(darkTheme = true) {
            ViewerContent(ids, request.index, env, onClose, onMessage)
        }
    }
}

@Composable
private fun ViewerContent(ids: MutableList<String>, initial: Int, env: FilesEnvironment, onClose: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = initial.coerceIn(0, ids.lastIndex)) { ids.size }
    var zoomed by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<FileRowEntity?>(null) }
    val index = pager.currentPage.coerceIn(0, (ids.size - 1).coerceAtLeast(0))
    val currentId = ids.getOrNull(index)
    val current by remember(currentId) { currentId?.let { env.files.watchFile(it) } ?: kotlinx.coroutines.flow.flowOf(null) }.collectAsState(null)
    val resources = LocalResources.current
    val fetching = stringResource(R.string.mirror_fetching)
    val couldNotDelete = stringResource(R.string.file_couldNotDelete)
    val exporter = rememberFileExporter(env, onMessage)
    val counterArgs = arrayOf("index" to index + 1, "total" to ids.size)
    val position = stringResource(R.string.file_viewerPosition).fill(*counterArgs)
    val scheme = MaterialTheme.colorScheme
    val ink = scheme.onSurface
    val inkSoft = scheme.onSurfaceVariant
    Box(Modifier.fillMaxSize().background(scheme.scrim).testTag("image-viewer")) {
        HorizontalPager(
            state = pager,
            userScrollEnabled = !zoomed,
            key = { ids.getOrElse(it) { "gone-$it" } },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val id = ids.getOrNull(page) ?: return@HorizontalPager
            ViewerPage(id, env, active = page == pager.currentPage, onZoomChanged = { if (page == pager.currentPage) zoomed = it })
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(scheme.scrim.copy(alpha = 0.72f), scheme.scrim.copy(alpha = 0f))))
                .statusBarsPadding()
                .padding(start = Space.x2, end = Space.x2, top = Space.x2, bottom = Space.x6),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(
                Icons.Outlined.Close,
                stringResource(R.string.files_viewerClose),
                onClick = onClose,
                container = RemftTheme.tonal.level2.copy(alpha = 0.85f),
                content = ink,
                modifier = Modifier.testTag("viewer-close"),
            )
            Column(Modifier.weight(1f).padding(horizontal = Space.x2)) {
                Text(
                    current?.name ?: stringResource(R.string.file_mediaUnavailable),
                    color = ink,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("viewer-title"),
                )
                current?.let { row ->
                    OneDriveNameLine(row.localPath, env, tag = "viewer-onedrive-name", itemTag = "viewer-onedrive-item")
                }
                if (ids.size > 1) {
                    Text(
                        stringResource(R.string.file_viewerCounter).fill(*counterArgs),
                        color = inkSoft,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.semantics { contentDescription = position }.testTag("viewer-counter"),
                    )
                }
            }
            RemftIconButton(
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                label = stringResource(R.string.file_openDownload),
                onClick = {
                    val row = current ?: return@RemftIconButton
                    scope.launch {
                        val outcome = FileOpener.open(context, env, row) { onMessage(fetching) }
                        FileOpener.messageOf(outcome)?.let { onMessage(resources.getString(it)) }
                    }
                },
                enabled = current != null,
                modifier = Modifier.testTag("viewer-open"),
                tint = ink,
            )
            RemftIconButton(
                icon = Icons.Outlined.Share,
                label = stringResource(R.string.sharesave_share),
                onClick = { current?.let(exporter::share) },
                enabled = current != null,
                modifier = Modifier.testTag("viewer-share"),
                tint = ink,
            )
            RemftIconButton(
                icon = Icons.Outlined.SaveAlt,
                label = stringResource(SaveTarget.of(current?.mime ?: "image/*").label),
                onClick = { current?.let(exporter::save) },
                enabled = current != null,
                modifier = Modifier.testTag("viewer-save"),
                tint = ink,
            )
            RemftIconButton(icon = Icons.Outlined.DeleteOutline, label = stringResource(R.string.common_delete), onClick = { confirm = current }, enabled = current != null, modifier = Modifier.testTag("viewer-delete"), tint = ink)
        }
    }
    confirm?.let { row ->
        var toBin by remember(row.id) { mutableStateOf(true) }
        LaunchedEffect(row.id) { toBin = env.bin.enabled() }
        ConfirmDeleteDialog(
            title = stringResource(R.string.file_deleteConfirm).fill("name" to row.name),
            body = stringResource(if (toBin) R.string.bin_toBin else R.string.file_deleteBody),
            confirm = stringResource(R.string.common_delete),
            confirmTag = "viewer-delete-confirm",
            onDismiss = { confirm = null },
        ) {
            confirm = null
            scope.launch {
                val ok = runCatching { env.attachments.delete(row.id) }.onFailure { AppLogs.warn("files.delete_failed", it) }.isSuccess
                if (!ok) {
                    onMessage(couldNotDelete)
                    return@launch
                }
                val at = ids.indexOf(row.id)
                if (ids.size <= 1) {
                    onClose()
                    return@launch
                }
                if (at >= 0) ids.removeAt(at)
                zoomed = false
                pager.scrollToPage(at.coerceIn(0, ids.lastIndex))
            }
        }
    }
}

@Composable
private fun ViewerPage(id: String, env: FilesEnvironment, active: Boolean, onZoomChanged: (Boolean) -> Unit) {
    val row by remember(id) { env.files.watchFile(id) }.collectAsState(null)
    val local = rememberLocalFile(env, row)
    var fetched by remember(id) { mutableStateOf<File?>(null) }
    var fetching by remember(id) { mutableStateOf(false) }
    var settled by remember(id) { mutableStateOf(false) }
    var fetchProblem by remember(id) { mutableStateOf<Int?>(null) }
    val file = local ?: fetched?.takeIf { it.isFile }
    var failed by remember(file) { mutableStateOf(false) }
    LaunchedEffect(row?.id, row?.localPath, file == null, active) {
        val current = row ?: return@LaunchedEffect
        if (file != null) {
            settled = true
            return@LaunchedEffect
        }
        if (!active || fetchProblem != null) return@LaunchedEffect
        if (!env.attachments.needsFetch(current)) {
            settled = true
            return@LaunchedEffect
        }
        fetching = true
        try {
            fetched = env.attachments.ensureLocal(current)
        } catch (e: AttachmentFetchFailed) {
            AppLogs.warn("files.fetch_failed", e, mapOf("reason" to e.problem))
            fetchProblem = FileOpener.fetchMessageOf(e.problem)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogs.warn("files.fetch_failed", e)
            fetchProblem = R.string.mirror_fetchFailed
        } finally {
            fetching = false
        }
        settled = true
    }
    var scale by remember(id) { mutableFloatStateOf(1f) }
    var offset by remember(id) { mutableStateOf(Offset.Zero) }
    var pageSize by remember(id) { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(active) {
        if (!active) {
            scale = 1f
            offset = Offset.Zero
        }
    }
    val transform = rememberTransformableState { _, zoomChange, panChange, _ ->
        scale = ViewerZoom.clamp(scale * zoomChange)
        offset = ViewerZoom.clampOffset(offset + panChange, scale, pageSize.width.toFloat(), pageSize.height.toFloat())
        onZoomChanged(scale > 1.01f)
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(id) {
                detectTapGestures(onDoubleTap = {
                    scale = ViewerZoom.toggle(scale)
                    offset = ViewerZoom.clampOffset(offset, scale, pageSize.width.toFloat(), pageSize.height.toFloat())
                    onZoomChanged(scale > 1.01f)
                })
            }
            .transformable(transform, canPan = { scale > 1.01f })
            .onSizeChanged { pageSize = it }
            .testTag("viewer-page-$id"),
        contentAlignment = Alignment.Center,
    ) {
        if (file == null) {
            val problem = fetchProblem
            when {
                fetching -> ViewerFetching()
                problem != null -> ViewerProblem(problem, "viewer-fetch-failed")
                row == null || settled -> ViewerProblem(R.string.file_viewerMissing, "viewer-missing")
            }
        } else if (failed) {
            ViewerProblem(R.string.file_viewerImageFailed, "viewer-image-failed")
        } else {
            AsyncImage(
                model = file,
                contentDescription = row?.name,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("viewer-image-$id")
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
    }
}

@Composable
private fun ViewerFetching() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(Space.x6).testTag("viewer-fetching")) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(R.string.mirror_fetching),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(Space.x4),
        )
    }
}

@Composable
private fun ViewerProblem(@StringRes text: Int, tag: String) {
    val file = RemftTheme.colors.file
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(Space.x6).testTag(tag)) {
        Box(Modifier.size(64.dp).background(file.container, MarkedShapes.large), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.BrokenImage, contentDescription = null, tint = file.onContainer, modifier = Modifier.size(32.dp))
        }
        Text(
            stringResource(text),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(Space.x4),
        )
    }
}

@Preview(name = "Viewer problem light")
@Composable
private fun ViewerProblemLightPreview() {
    RemftTheme(darkTheme = false) { ViewerProblemPreviewContent() }
}

@Preview(name = "Viewer problem dark")
@Composable
private fun ViewerProblemDarkPreview() {
    RemftTheme(darkTheme = true) { ViewerProblemPreviewContent() }
}

@Composable
private fun ViewerProblemPreviewContent() {
    RemftTheme(darkTheme = true) {
        Box(Modifier.background(MaterialTheme.colorScheme.scrim).padding(Space.x4), contentAlignment = Alignment.Center) {
            ViewerProblem(R.string.file_viewerImageFailed, "viewer-preview")
        }
    }
}
