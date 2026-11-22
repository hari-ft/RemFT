package com.ftapps.remft.ui.notes.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import com.ftapps.remft.R
import com.ftapps.remft.domain.notes.editing.MdTokenKind
import com.ftapps.remft.domain.notes.editing.SourceTokens
import com.ftapps.remft.domain.settings.NoteSourceStyling
import com.ftapps.remft.ui.theme.RemftThemeExtras
import com.ftapps.remft.ui.theme.Space

data class SourcePalette(val body: Color, val marker: Color, val accent: Color, val link: Color, val code: Color, val mark: Color)

class SourceStylingTransformation(
    private val styling: NoteSourceStyling,
    private val palette: SourcePalette,
    private val caretLine: Int,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(styled(text.text, styling, palette, caretLine), OffsetMapping.Identity)

    override fun equals(other: Any?): Boolean =
        other is SourceStylingTransformation && other.styling == styling && other.palette == palette && other.caretLine == caretLine

    override fun hashCode(): Int = (styling.hashCode() * 31 + palette.hashCode()) * 31 + caretLine
}

fun styled(text: String, styling: NoteSourceStyling, palette: SourcePalette, caretLine: Int): AnnotatedString {
    if (styling == NoteSourceStyling.Plain || text.isEmpty()) return AnnotatedString(text)
    val quiet = styling == NoteSourceStyling.MarkersOnly
    val builder = AnnotatedString.Builder(text)
    SourceTokens.scan(text).forEach { token ->
        val style = styleFor(token.kind, token.line == caretLine, quiet, palette) ?: return@forEach
        builder.addStyle(style, token.start, token.end)
    }
    return builder.toAnnotatedString()
}

private fun styleFor(kind: MdTokenKind, onCaretLine: Boolean, quiet: Boolean, palette: SourcePalette): SpanStyle? = when (kind) {
    MdTokenKind.Plain -> null
    MdTokenKind.Marker -> SpanStyle(color = if (onCaretLine) palette.body else palette.marker)
    MdTokenKind.Heading1 -> if (quiet) SpanStyle(color = palette.accent) else SpanStyle(color = palette.accent, fontSize = 1.5.em, fontWeight = FontWeight.W700)
    MdTokenKind.Heading2 -> if (quiet) SpanStyle(color = palette.accent) else SpanStyle(color = palette.accent, fontSize = 1.3.em, fontWeight = FontWeight.W700)
    MdTokenKind.Heading3 -> if (quiet) SpanStyle(color = palette.accent) else SpanStyle(color = palette.accent, fontSize = 1.15.em, fontWeight = FontWeight.W600)
    MdTokenKind.HeadingSmall -> if (quiet) SpanStyle(color = palette.accent) else SpanStyle(color = palette.accent, fontWeight = FontWeight.W600)
    MdTokenKind.Bold -> if (quiet) null else SpanStyle(fontWeight = FontWeight.W700)
    MdTokenKind.Italic -> if (quiet) null else SpanStyle(fontStyle = FontStyle.Italic)
    MdTokenKind.Strike -> if (quiet) null else SpanStyle(textDecoration = TextDecoration.LineThrough)
    MdTokenKind.Mark -> if (quiet) null else SpanStyle(background = palette.mark)
    MdTokenKind.Code, MdTokenKind.CodeBlock -> SpanStyle(color = palette.code)
    MdTokenKind.Link -> if (quiet) SpanStyle(color = palette.link) else SpanStyle(color = palette.link, textDecoration = TextDecoration.Underline)
    MdTokenKind.Quote -> if (quiet) SpanStyle(color = palette.marker) else SpanStyle(color = palette.marker, fontStyle = FontStyle.Italic)
}

@Composable
fun sourcePalette(): SourcePalette {
    val scheme = MaterialTheme.colorScheme
    val extras = RemftThemeExtras.colors
    return remember(scheme, extras) {
        SourcePalette(
            body = scheme.onSurface,
            marker = scheme.onSurfaceVariant,
            accent = scheme.onSurface,
            link = extras.link,
            code = scheme.onSurfaceVariant,
            mark = extras.warning.copy(alpha = 0.22f),
        )
    }
}

class EditorShortcuts(
    val onPalette: () -> Unit,
    val onFind: () -> Unit,
    val onReplace: () -> Unit,
    val onBold: () -> Unit,
    val onItalic: () -> Unit,
    val onLink: () -> Unit,
    val onIndent: (Boolean) -> Boolean,
    val onPaste: () -> Boolean = { false },
)

fun handleEditorKey(event: KeyEvent, shortcuts: EditorShortcuts): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val command = event.isCtrlPressed || event.isMetaPressed
    return when {
        event.key == Key.Tab && !command -> shortcuts.onIndent(event.isShiftPressed)
        command && event.key == Key.V && !event.isShiftPressed -> shortcuts.onPaste()
        command && event.key == Key.K && event.isShiftPressed -> {
            shortcuts.onLink()
            true
        }
        command && event.key == Key.K -> {
            shortcuts.onPalette()
            true
        }
        command && event.key == Key.F -> {
            shortcuts.onFind()
            true
        }
        command && event.key == Key.H -> {
            shortcuts.onReplace()
            true
        }
        command && event.key == Key.B -> {
            shortcuts.onBold()
            true
        }
        command && event.key == Key.I -> {
            shortcuts.onItalic()
            true
        }
        else -> false
    }
}

@Composable
fun SourceEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    styling: NoteSourceStyling,
    shortcuts: EditorShortcuts,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    val palette = sourcePalette()
    val caretLine = remember(value.text, value.selection.start) { SourceTokens.lineOf(value.text, value.selection.start) }
    val transformation = remember(styling, palette, caretLine) { SourceStylingTransformation(styling, palette, caretLine) }
    val label = stringResource(R.string.note_bodyLabel)
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = MaterialTheme.typography.bodyLarge.fontSize, lineHeight = 1.5.em, color = palette.body)
    Box(modifier.verticalScroll(rememberScrollState())) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = style,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            visualTransformation = transformation,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Space.x5, vertical = Space.x2)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { handleEditorKey(it, shortcuts) }
                .semantics { contentDescription = label }
                .testTag("note-source"),
            decorationBox = { inner ->
                Box {
                    if (value.text.isEmpty()) {
                        Text(stringResource(R.string.note_sourceHint), style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
                    }
                    inner()
                }
            },
        )
    }
}
