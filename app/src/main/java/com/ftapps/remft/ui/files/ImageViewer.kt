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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.ftapps.remft.R
import com.ftapps.remft.data.db.entity.FileRowEntity
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Space
import kotlinx.coroutines.launch

object ViewerZoom {
    const val MAX_SCALE = 6f
    const val DOUBLE_TAP_SCALE = 2.5f

    fun clamp(scale: Float): Float = scale.coerceIn(1f, MAX_SCALE)

    fun toggle(scale: Float): Float = if (scale > 1.01f) 1f else DOUBLE_TAP_SCALE
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
    val counterArgs = arrayOf("index" to index + 1, "total" to ids.size)
    val position = stringResource(R.string.file_viewerPosition).fill(*counterArgs)
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("image-viewer")) {
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
            Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).statusBarsPadding().padding(horizontal = Space.x1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose, modifier = Modifier.testTag("viewer-close")) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.files_viewerClose), tint = Color.White)
            }
            Column(Modifier.weight(1f).padding(horizontal = Space.x2)) {
                Text(
                    current?.name ?: stringResource(R.string.file_mediaUnavailable),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("viewer-title"),
                )
                if (ids.size > 1) {
                    Text(
                        stringResource(R.string.file_viewerCounter).fill(*counterArgs),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { contentDescription = position }.testTag("viewer-counter"),
                    )
                }
            }
            IconButton(
                onClick = {
                    val row = current ?: return@IconButton
                    scope.launch {
                        val outcome = FileOpener.open(context, env, row) { onMessage(fetching) }
                        FileOpener.messageOf(outcome)?.let { onMessage(resources.getString(it)) }
                    }
                },
                enabled = current != null,
                modifier = Modifier.testTag("viewer-open"),
            ) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.file_openDownload), tint = Color.White)
            }
            IconButton(onClick = { confirm = current }, enabled = current != null, modifier = Modifier.testTag("viewer-delete")) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = stringResource(R.string.common_delete), tint = Color.White)
            }
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
                val ok = runCatching { env.attachments.delete(row.id) }.isSuccess
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
    val file = remember(row?.id, row?.localPath) { row?.let { env.attachments.fileOf(it) } }
    var failed by remember(file) { mutableStateOf(false) }
    var scale by remember(id) { mutableFloatStateOf(1f) }
    var offset by remember(id) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(active) {
        if (!active) {
            scale = 1f
            offset = Offset.Zero
        }
    }
    val transform = rememberTransformableState { _, zoomChange, panChange, _ ->
        scale = ViewerZoom.clamp(scale * zoomChange)
        offset = if (scale > 1f) offset + panChange else Offset.Zero
        onZoomChanged(scale > 1.01f)
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(id) {
                detectTapGestures(onDoubleTap = {
                    scale = ViewerZoom.toggle(scale)
                    if (scale == 1f) offset = Offset.Zero
                    onZoomChanged(scale > 1.01f)
                })
            }
            .transformable(transform, canPan = { scale > 1.01f })
            .testTag("viewer-page-$id"),
        contentAlignment = Alignment.Center,
    ) {
        if (file == null) {
            ViewerProblem(R.string.file_viewerMissing, "viewer-missing")
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
private fun ViewerProblem(@StringRes text: Int, tag: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.testTag(tag)) {
        Icon(Icons.Outlined.BrokenImage, contentDescription = null, tint = Color.White.copy(alpha = 0.7f))
        Text(stringResource(text), color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(Space.x4))
    }
}
