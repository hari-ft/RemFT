package com.ftapps.remft.ui.notes.markdown

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.ftapps.remft.R
import com.ftapps.remft.domain.notes.highlight.CodeHighlighter
import com.ftapps.remft.domain.notes.highlight.CodeTokenKind
import com.ftapps.remft.domain.notes.markdown.Alert
import com.ftapps.remft.domain.notes.markdown.AlertKind
import com.ftapps.remft.domain.notes.markdown.Emoji
import com.ftapps.remft.domain.notes.markdown.FrontMatter
import com.ftapps.remft.domain.notes.markdown.Highlight
import com.ftapps.remft.domain.notes.markdown.MarkdownParsing
import com.ftapps.remft.domain.notes.markdown.MathBlock
import com.ftapps.remft.domain.notes.markdown.MathInline
import com.ftapps.remft.domain.notes.markdown.Outline
import com.ftapps.remft.domain.notes.markdown.OutlineHeading
import com.ftapps.remft.domain.notes.markdown.ParsedMarkdown
import com.ftapps.remft.domain.notes.markdown.children
import com.ftapps.remft.domain.notes.markdown.textContent
import com.ftapps.remft.ui.theme.RemftThemeExtras
import com.ftapps.remft.ui.theme.Radius
import com.ftapps.remft.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.commonmark.ext.footnotes.FootnoteDefinition
import org.commonmark.ext.footnotes.FootnoteReference
import org.commonmark.ext.footnotes.InlineFootnote
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as TextNode
import org.commonmark.node.ThematicBreak
import java.io.File

fun interface MarkdownImageResolver {
    suspend fun file(fileId: String): File?
}

val LocalMarkdownImages = staticCompositionLocalOf<MarkdownImageResolver> { MarkdownImageResolver { null } }

private class MarkdownActions(val onAnchor: (String) -> Unit, val onLink: (String) -> Unit)

private val LocalMarkdownActions = staticCompositionLocalOf { MarkdownActions({}, {}) }

data class MarkdownDocument(val parsed: ParsedMarkdown, val blocks: List<Node>, val headings: List<OutlineHeading>)

fun markdownDocument(source: String): MarkdownDocument {
    val parsed = MarkdownParsing.parse(source)
    val blocks = parsed.document.children()
    return MarkdownDocument(parsed, blocks, Outline.headings(blocks))
}

@Composable
fun rememberMarkdownDocument(source: String): MarkdownDocument? {
    val document by produceState<MarkdownDocument?>(initialValue = null, source) {
        value = withContext(Dispatchers.Default) { runCatching { markdownDocument(source) }.getOrNull() }
    }
    return document
}

fun openMarkdownLink(context: Context, href: String) {
    if (!MarkdownParsing.isSafeLink(href)) return
    val uri = href.trim().toUri()
    val scheme = uri.scheme?.lowercase() ?: return
    val intent = Intent(Intent.ACTION_VIEW, uri)
    if (scheme == "remft") intent.setPackage(context.packageName)
    if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    } catch (_: SecurityException) {
    }
}

@Composable
fun MarkdownView(
    document: MarkdownDocument?,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(Space.x4),
    collapsed: Set<Int> = emptySet(),
    onToggleFold: ((Int) -> Unit)? = null,
    onAnchor: (String) -> Unit = {},
    header: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val actions = remember(onAnchor) { MarkdownActions(onAnchor) { openMarkdownLink(context, it) } }
    CompositionLocalProvider(LocalMarkdownActions provides actions) {
        val hidden = remember(document, collapsed) {
            if (document == null) emptySet() else Outline.hiddenBlocks(document.blocks.size, document.headings, collapsed)
        }
        LazyColumn(modifier.testTag("markdown-view"), state = listState, contentPadding = contentPadding) {
            if (header != null) item(key = "header") { header() }
            if (document == null) return@LazyColumn
            document.blocks.forEachIndexed { index, block ->
                if (index in hidden) return@forEachIndexed
                item(key = index) {
                    Box(Modifier.padding(bottom = Space.x3)) {
                        val heading = document.headings.firstOrNull { it.blockIndex == index }
                        if (heading != null && block is Heading) {
                            HeadingBlock(block, foldable = onToggleFold, collapsed = index in collapsed, index = index)
                        } else {
                            MdBlock(block)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MarkdownColumn(source: String, modifier: Modifier = Modifier) {
    val document = rememberMarkdownDocument(source) ?: return
    val context = LocalContext.current
    val actions = remember { MarkdownActions({}) { openMarkdownLink(context, it) } }
    CompositionLocalProvider(LocalMarkdownActions provides actions) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.x3)) {
            document.blocks.forEach { MdBlock(it) }
        }
    }
}

fun headerIndexOffset(hasHeader: Boolean): Int = if (hasHeader) 1 else 0

@Composable
private fun bodyStyle(): TextStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)

@Composable
private fun headingStyle(level: Int): TextStyle {
    val type = MaterialTheme.typography
    val base = when (level) {
        1 -> type.headlineMedium
        2 -> type.headlineSmall
        3 -> type.titleLarge
        4 -> type.titleMedium
        else -> type.titleSmall
    }
    return base.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.W700)
}

@Composable
private fun HeadingBlock(block: Heading, foldable: ((Int) -> Unit)?, collapsed: Boolean, index: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        InlineText(block, headingStyle(block.level), Modifier.weight(1f).semantics { heading() })
        if (foldable != null) {
            IconButton(onClick = { foldable(index) }, modifier = Modifier.testTag("fold-$index")) {
                Icon(
                    if (collapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                    contentDescription = stringResource(R.string.note_outline),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MdBlock(node: Node) {
    when (node) {
        is Heading -> InlineText(node, headingStyle(node.level), Modifier.semantics { heading() })
        is Paragraph -> ParagraphBlock(node)
        is BulletList, is OrderedList -> ListBlock(node)
        is BlockQuote -> QuoteBlock(node)
        is Alert -> AlertBlock(node)
        is FencedCodeBlock -> FencedBlock(node)
        is IndentedCodeBlock -> CodePanel(node.literal.trimEnd('\n'), null)
        is ThematicBreak -> HorizontalDivider(Modifier.padding(vertical = Space.x2), color = MaterialTheme.colorScheme.outlineVariant)
        is TableBlock -> TableView(node)
        is HtmlBlock -> RawHtml(node.literal.trimEnd('\n'))
        is FrontMatter -> FrontMatterBlock(node.literal)
        is MathBlock -> MathPanel(node.literal)
        is FootnoteDefinition -> FootnoteBlock(node)
        else -> {
            val text = node.textContent()
            if (text.isNotBlank()) Text(text, style = bodyStyle())
        }
    }
}

@Composable
private fun BlockChildren(node: Node) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.x2)) {
        node.children().forEach { MdBlock(it) }
    }
}

@Composable
private fun ParagraphBlock(node: Paragraph) {
    val segments = remember(node) { splitAroundImages(node) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.x2)) {
        segments.forEach { segment ->
            when (segment) {
                is Segment.Inline -> InlineNodes(segment.nodes, bodyStyle())
                is Segment.Picture -> MarkdownImage(segment.image.destination, segment.image.textContent())
            }
        }
    }
}

private sealed interface Segment {
    data class Inline(val nodes: List<Node>) : Segment
    data class Picture(val image: org.commonmark.node.Image) : Segment
}

private fun splitAroundImages(node: Node): List<Segment> {
    val out = ArrayList<Segment>()
    var run = ArrayList<Node>()
    node.children().forEach { child ->
        if (child is org.commonmark.node.Image) {
            if (run.any { it !is SoftLineBreak && it !is HardLineBreak && !(it is TextNode && it.literal.isBlank()) }) out.add(Segment.Inline(run))
            run = ArrayList()
            out.add(Segment.Picture(child))
        } else {
            run.add(child)
        }
    }
    if (run.any { it !is SoftLineBreak && it !is HardLineBreak && !(it is TextNode && it.literal.isBlank()) }) out.add(Segment.Inline(run))
    return out
}

@Composable
private fun InlineText(node: Node, style: TextStyle, modifier: Modifier = Modifier) {
    InlineNodes(node.children(), style, modifier)
}

@Composable
private fun InlineNodes(nodes: List<Node>, style: TextStyle, modifier: Modifier = Modifier) {
    val colors = InlineColors(
        link = RemftThemeExtras.colors.link,
        codeBackground = MaterialTheme.colorScheme.surfaceContainerHigh,
        mark = RemftThemeExtras.colors.warning.copy(alpha = 0.30f),
        muted = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val actions = LocalMarkdownActions.current
    val text = remember(nodes, colors) { buildInline(nodes, colors, actions) }
    Text(text, style = style, modifier = modifier)
}

private data class InlineColors(val link: Color, val codeBackground: Color, val mark: Color, val muted: Color)

private fun buildInline(nodes: List<Node>, colors: InlineColors, actions: MarkdownActions): AnnotatedString = buildAnnotatedString {
    fun walk(node: Node) {
        when (node) {
            is TextNode -> append(node.literal)
            is SoftLineBreak -> append(' ')
            is HardLineBreak -> append('\n')
            is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.codeBackground)) { append(node.literal) }
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { node.children().forEach(::walk) }
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { node.children().forEach(::walk) }
            is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { node.children().forEach(::walk) }
            is Highlight -> withStyle(SpanStyle(background = colors.mark)) { node.children().forEach(::walk) }
            is MathInline -> withStyle(SpanStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, background = colors.codeBackground)) { append(node.literal) }
            is Emoji -> append(node.glyph)
            is HtmlInline -> append(node.literal)
            is FootnoteReference -> withStyle(SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.75.em, color = colors.link)) { append(node.label) }
            is InlineFootnote -> withStyle(SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.75.em, color = colors.muted)) { node.children().forEach(::walk) }
            is org.commonmark.node.Image -> withStyle(SpanStyle(color = colors.muted)) { append("[" + node.textContent().ifBlank { "image" } + "]") }
            is TaskListItemMarker -> Unit
            is Link -> {
                val href = node.destination.orEmpty()
                val anchor = MarkdownParsing.anchorOf(href)
                if (MarkdownParsing.isSafeLink(href) && (anchor != null || href.contains(':'))) {
                    val style = TextLinkStyles(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline))
                    val annotation = LinkAnnotation.Clickable(href, style) {
                        if (anchor != null) actions.onAnchor(anchor) else actions.onLink(href)
                    }
                    withLink(annotation) { node.children().forEach(::walk) }
                } else {
                    node.children().forEach(::walk)
                }
            }
            else -> node.children().forEach(::walk)
        }
    }
    nodes.forEach(::walk)
}

@Composable
private fun ListBlock(list: Node) {
    val ordered = list as? OrderedList
    var number = ordered?.markerStartNumber ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(Space.x1)) {
        list.children().filterIsInstance<ListItem>().forEach { item ->
            val marker = findTaskMarker(item)
            Row {
                Box(Modifier.widthIn(min = 28.dp).padding(end = Space.x2), contentAlignment = Alignment.TopEnd) {
                    when {
                        marker != null -> Icon(
                            if (marker.isChecked) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                            contentDescription = null,
                            tint = if (marker.isChecked) RemftThemeExtras.colors.success else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp).padding(top = 2.dp),
                        )
                        ordered != null -> Text("$number${ordered.markerDelimiter ?: "."}", style = bodyStyle())
                        else -> Text("•", style = bodyStyle())
                    }
                }
                Box(Modifier.weight(1f)) { BlockChildren(item) }
            }
            number++
        }
    }
}

private fun findTaskMarker(item: ListItem): TaskListItemMarker? {
    val first = item.firstChild ?: return null
    if (first is TaskListItemMarker) return first
    return (first as? Paragraph)?.firstChild as? TaskListItemMarker
}

@Composable
private fun QuoteBlock(node: BlockQuote) {
    Row(Modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(Space.x3))
        Box(Modifier.weight(1f)) {
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                BlockChildren(node)
            }
        }
    }
}

private data class AlertLook(val icon: ImageVector, val label: Int, val color: Color)

@Composable
private fun alertLook(kind: AlertKind): AlertLook {
    val scheme = MaterialTheme.colorScheme
    val extras = RemftThemeExtras.colors
    return when (kind) {
        AlertKind.Note -> AlertLook(Icons.Outlined.Info, R.string.markdown_alert_note, scheme.primary)
        AlertKind.Tip -> AlertLook(Icons.Outlined.Lightbulb, R.string.markdown_alert_tip, extras.success)
        AlertKind.Important -> AlertLook(Icons.Outlined.ChatBubbleOutline, R.string.markdown_alert_important, scheme.secondary)
        AlertKind.Warning -> AlertLook(Icons.Outlined.WarningAmber, R.string.markdown_alert_warning, extras.warning)
        AlertKind.Caution -> AlertLook(Icons.Outlined.ReportProblem, R.string.markdown_alert_caution, scheme.error)
    }
}

@Composable
private fun AlertBlock(node: Alert) {
    val look = alertLook(node.kind)
    val labelColor = if (node.kind == AlertKind.Warning) MaterialTheme.colorScheme.onSurface else look.color
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(look.color.copy(alpha = 0.10f), RoundedCornerShape(Radius.chip))
            .testTag("alert-${node.kind.marker.lowercase()}"),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(look.color, RoundedCornerShape(topStart = Radius.chip, bottomStart = Radius.chip)))
        Column(Modifier.padding(Space.x3)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(look.icon, contentDescription = null, tint = look.color, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Space.x2))
                Text(stringResource(look.label), style = MaterialTheme.typography.titleSmall, color = labelColor)
            }
            Spacer(Modifier.height(Space.x1))
            BlockChildren(node)
        }
    }
}

@Composable
private fun FencedBlock(node: FencedCodeBlock) {
    val mermaid = MarkdownParsing.mermaidSource(node)
    val math = MarkdownParsing.mathFenceSource(node)
    when {
        mermaid != null -> MermaidDiagram(mermaid, Modifier.fillMaxWidth())
        math != null -> MathPanel(math.trimEnd('\n'))
        else -> CodePanel(node.literal.trimEnd('\n'), node.info)
    }
}

@Composable
fun CodePanel(code: String, info: String?) {
    val palette = RemftThemeExtras.colors.code
    val ink = MaterialTheme.colorScheme.onSurface
    val highlighted = remember(code, info, palette) {
        val tokens = runCatching { CodeHighlighter.highlight(code, info) }.getOrDefault(emptyList())
        buildAnnotatedString {
            append(code)
            tokens.forEach { token ->
                val color = when (token.kind) {
                    CodeTokenKind.Keyword -> palette.keyword
                    CodeTokenKind.String -> palette.string
                    CodeTokenKind.Comment -> palette.comment
                    CodeTokenKind.Number -> palette.number
                    CodeTokenKind.Name -> palette.name
                    CodeTokenKind.Meta -> palette.meta
                }
                if (token.start in 0..code.length && token.end in token.start..code.length) addStyle(SpanStyle(color = color), token.start, token.end)
            }
        }
    }
    val clipboard = LocalClipboard.current
    val clipScope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(Radius.chip))
            .border(1.dp, RemftThemeExtras.colors.hairline, RoundedCornerShape(Radius.chip)),
    ) {
        SelectionContainer {
            Text(
                highlighted,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = ink),
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = Space.x3, top = Space.x3, bottom = Space.x3, end = 56.dp),
            )
        }
        IconButton(
            onClick = {
                clipScope.launch { runCatching { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("code", code))) } }
                copied = true
            },
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Icon(
                Icons.Outlined.ContentCopy,
                contentDescription = stringResource(if (copied) R.string.markdown_copied else R.string.markdown_copyCode),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MathPanel(source: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(Radius.chip))
            .horizontalScroll(rememberScrollState())
            .padding(Space.x3)
            .testTag("math-block"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            source,
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = false,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RawHtml(source: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Radius.chip))
            .padding(Space.x3)
            .testTag("raw-html"),
    ) {
        Text(stringResource(R.string.markdown_rawHtml), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Space.x1))
        Text(
            source,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun FrontMatterBlock(source: String) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(Radius.chip))
            .testTag("front-matter"),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open = !open }.padding(horizontal = Space.x3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.markdown_frontMatter), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
        }
        if (open) {
            Column(Modifier.padding(start = Space.x3, end = Space.x3, bottom = Space.x3)) {
                source.lines().filter { it.isNotBlank() }.forEach { line ->
                    val key = line.substringBefore(':', "")
                    if (key.isNotEmpty() && !key.startsWith(' ')) {
                        Row {
                            Text(key, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.W600), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(Space.x2))
                            Text(line.substringAfter(':').trim(), style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun FootnoteBlock(node: FootnoteDefinition) {
    Row {
        Text(node.label + ".", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Space.x2))
        Box(Modifier.weight(1f)) {
            Column {
                node.children().forEach { child ->
                    if (child is Paragraph) InlineText(child, MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface)) else MdBlock(child)
                }
            }
        }
    }
}

@Composable
private fun TableView(node: TableBlock) {
    val rows = remember(node) {
        val out = ArrayList<TableRow>()
        fun collect(n: Node) {
            if (n is TableRow) out.add(n) else n.children().forEach(::collect)
        }
        collect(node)
        out
    }
    if (rows.isEmpty()) return
    val columns = rows.maxOf { it.children().size }
    val cells = rows.flatMap { row ->
        val list = row.children().filterIsInstance<TableCell>()
        List(columns) { list.getOrNull(it) }
    }
    val border = MaterialTheme.colorScheme.outlineVariant
    val headerBackground = MaterialTheme.colorScheme.surfaceContainer
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("table")) {
        TableGrid(columns, Modifier.border(1.dp, border, RoundedCornerShape(4.dp))) {
            cells.forEach { cell ->
                val header = cell?.isHeader == true
                Box(
                    Modifier
                        .background(if (header) headerBackground else Color.Transparent)
                        .border(0.5.dp, border)
                        .padding(horizontal = Space.x3, vertical = Space.x2),
                    contentAlignment = when (cell?.alignment) {
                        TableCell.Alignment.CENTER -> Alignment.TopCenter
                        TableCell.Alignment.RIGHT -> Alignment.TopEnd
                        else -> Alignment.TopStart
                    },
                ) {
                    if (cell != null) {
                        val style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (header) FontWeight.W600 else FontWeight.Normal,
                        )
                        InlineText(cell, style)
                    }
                }
            }
        }
    }
}

@Composable
private fun TableGrid(columns: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val maxCell = 280.dp.roundToPx()
        val minCell = 48.dp.roundToPx()
        val rows = (measurables.size + columns - 1) / columns
        val widths = IntArray(columns) { minCell }
        measurables.forEachIndexed { i, m ->
            val c = i % columns
            widths[c] = maxOf(widths[c], minOf(maxCell, m.maxIntrinsicWidth(Constraints.Infinity)))
        }
        val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixedWidth(widths[i % columns])) }
        val heights = IntArray(rows)
        placeables.forEachIndexed { i, p -> heights[i / columns] = maxOf(heights[i / columns], p.height) }
        val totalWidth = widths.sum()
        val totalHeight = heights.sum()
        layout(totalWidth.coerceAtMost(constraints.maxWidth.takeIf { it != Constraints.Infinity } ?: totalWidth).coerceAtLeast(totalWidth), totalHeight) {
            var y = 0
            for (r in 0 until rows) {
                var x = 0
                for (c in 0 until columns) {
                    val index = r * columns + c
                    if (index < placeables.size) {
                        val p = placeables[index]
                        p.place(x, y)
                    }
                    x += widths[c]
                }
                y += heights[r]
            }
        }
    }
}

@Composable
fun MarkdownImage(destination: String?, alt: String) {
    val fileId = MarkdownParsing.remftFileId(destination)
    val resolver = LocalMarkdownImages.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, fileId) {
        if (fileId == null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = resolver.file(fileId) ?: return@runCatching null
                decodeSampled(file, 1600)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val image = bitmap
    if (image != null) {
        Image(
            image,
            contentDescription = alt.ifBlank { stringResource(R.string.markdown_image) },
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().sizeIn(maxHeight = 480.dp).testTag("md-image"),
        )
    } else {
        val label = alt.ifBlank { stringResource(if (fileId == null) R.string.markdown_relativeImage else R.string.markdown_image) }
        Row(
            Modifier
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Radius.chip))
                .padding(horizontal = Space.x3, vertical = Space.x2)
                .semantics { contentDescription = label }
                .testTag("image-chip"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (fileId == null) Icons.Outlined.ErrorOutline else Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Space.x2))
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

fun decodeSampled(file: File, maxSide: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}
