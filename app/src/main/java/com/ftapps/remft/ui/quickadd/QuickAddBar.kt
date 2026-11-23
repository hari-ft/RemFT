package com.ftapps.remft.ui.quickadd

import com.ftapps.remft.ui.theme.Sizes
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ftapps.remft.R
import com.ftapps.remft.domain.quickadd.QuickChip
import com.ftapps.remft.domain.quickadd.QuickParse
import com.ftapps.remft.domain.quickadd.QuickTokenKind
import com.ftapps.remft.domain.repeat.RepeatRule
import com.ftapps.remft.domain.repeat.RepeatSentence
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.tasks.TaskPriority
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.domain.time.DateFormats
import com.ftapps.remft.ui.components.ChipTone
import com.ftapps.remft.ui.components.PreviewSurface
import com.ftapps.remft.ui.components.RemftIconButton
import com.ftapps.remft.ui.components.chipColors
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
import com.ftapps.remft.ui.tasks.priorityLabel
import com.ftapps.remft.ui.tasks.rememberDateFormat
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.RemftTheme
import com.ftapps.remft.ui.theme.Space

class QuickAddHighlight(private val ranges: List<IntRange>, private val style: SpanStyle) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val marked = buildAnnotatedString {
            append(text)
            ranges.forEach { range ->
                val start = range.first.coerceIn(0, text.length)
                val end = (range.last + 1).coerceIn(start, text.length)
                if (end > start) addStyle(style, start, end)
            }
        }
        return TransformedText(marked, OffsetMapping.Identity)
    }

    override fun equals(other: Any?): Boolean = other is QuickAddHighlight && other.ranges == ranges && other.style == style

    override fun hashCode(): Int = 31 * ranges.hashCode() + style.hashCode()
}

private val IgnoredChipsSaver = Saver<Set<String>, ArrayList<String>>(save = { ArrayList(it) }, restore = { it.toSet() })

@Composable
fun QuickAddBar(
    hint: String,
    tag: String,
    onSubmit: (raw: String, parsed: QuickParse) -> Unit,
    modifier: Modifier = Modifier,
    capture: Boolean = false,
) {
    val env = LocalTasksEnvironment.current
    val creator = remember(env) { QuickAddCreator(env.repository, env.series) }
    val format = rememberDateFormat()
    var text by rememberSaveable { mutableStateOf("") }
    var ignored by rememberSaveable(stateSaver = IgnoredChipsSaver) { mutableStateOf(emptySet<String>()) }
    var helpOpen by remember { mutableStateOf(false) }
    val parsed = remember(text, ignored, format, capture) { creator.parse(text, format, ignored, capture) }
    val markStyle = SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    val highlight = remember(parsed.highlightRanges, markStyle) { QuickAddHighlight(parsed.highlightRanges, markStyle) }
    val submit = submit@{
        val raw = text.trim()
        if (raw.isEmpty()) return@submit
        val finalParse = creator.parse(raw, format, ignored, capture)
        text = ""
        ignored = emptySet()
        onSubmit(raw, finalParse)
    }
    Column(modifier.fillMaxWidth().padding(vertical = Space.x2).animateContentSize()) {
        QuickAddField(
            text = text,
            onTextChange = { value ->
                if (value.isEmpty()) ignored = emptySet()
                text = value
            },
            hint = hint,
            tag = tag,
            highlight = highlight,
            onHelp = { helpOpen = true },
            onSubmit = submit,
        )
        if (parsed.chips.isNotEmpty()) {
            QuickAddChips(
                parsed = parsed,
                format = format,
                tag = tag,
                onDismiss = { chip -> ignored = ignored + chip.keys },
                onHelp = { helpOpen = true },
                modifier = Modifier.padding(top = Space.x2),
            )
        }
    }
    if (helpOpen) QuickAddHelpSheet(onDismiss = { helpOpen = false })
}

@Composable
private fun QuickAddField(
    text: String,
    onTextChange: (String) -> Unit,
    hint: String,
    tag: String,
    highlight: VisualTransformation,
    onHelp: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = scheme.surface,
        unfocusedContainerColor = scheme.surface,
        focusedBorderColor = scheme.primary,
        unfocusedBorderColor = Color.Transparent,
        cursorColor = scheme.primary,
        unfocusedPlaceholderColor = scheme.onSurfaceVariant,
        focusedPlaceholderColor = scheme.onSurfaceVariant,
    )
    val trailing: @Composable () -> Unit = {
        if (text.isEmpty()) {
            RemftIconButton(
                label = stringResource(R.string.task_quickParse_helpTooltip),
                onClick = onHelp,
                colors = IconButtonDefaults.iconButtonColors(contentColor = scheme.onSurfaceVariant),
                modifier = Modifier.testTag("$tag-help"),
            ) {
                Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = stringResource(R.string.task_quickParse_helpTooltip))
            }
        } else {
            RemftIconButton(
                label = stringResource(R.string.task_addTask),
                onClick = onSubmit,
                colors = IconButtonDefaults.iconButtonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary),
                modifier = Modifier.padding(end = Space.x1).testTag("$tag-submit"),
            ) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.task_addTask))
            }
        }
    }
    BasicTextField(
        value = text,
        onValueChange = onTextChange,
        singleLine = true,
        visualTransformation = highlight,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
        cursorBrush = SolidColor(scheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        interactionSource = interaction,
        modifier = modifier.fillMaxWidth().heightIn(min = Sizes.quickAdd).testTag(tag),
    ) { inner ->
        OutlinedTextFieldDefaults.DecorationBox(
            value = text,
            innerTextField = inner,
            enabled = true,
            singleLine = true,
            visualTransformation = highlight,
            interactionSource = interaction,
            placeholder = { Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = trailing,
            colors = colors,
            contentPadding = OutlinedTextFieldDefaults.contentPadding(top = Space.x3, bottom = Space.x3),
            container = {
                OutlinedTextFieldDefaults.Container(
                    enabled = true,
                    isError = false,
                    interactionSource = interaction,
                    colors = colors,
                    shape = MarkedShapes.pill,
                )
            },
        )
    }
}

@Composable
private fun QuickAddTokenChip(
    icon: ImageVector,
    label: String,
    tone: ChipTone,
    keepAsText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = chipColors(tone)
    InputChip(
        selected = false,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        avatar = { Icon(icon, contentDescription = null, tint = colors.content, modifier = Modifier.size(18.dp)) },
        trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = keepAsText, modifier = Modifier.size(18.dp)) },
        shape = MarkedShapes.pill,
        colors = InputChipDefaults.inputChipColors(
            containerColor = colors.container,
            labelColor = colors.content,
            leadingIconColor = colors.content,
            trailingIconColor = colors.content,
        ),
        border = null,
        modifier = modifier,
    )
}

private fun priorityTone(priority: String?): ChipTone = when (TaskPriority.parse(priority)) {
    TaskPriority.Urgent, TaskPriority.High -> ChipTone.Urgent
    TaskPriority.Medium -> ChipTone.Warning
    TaskPriority.Low -> ChipTone.File
    TaskPriority.None -> ChipTone.Neutral
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickAddChips(
    parsed: QuickParse,
    format: DateFormatId,
    tag: String,
    onDismiss: (QuickChip) -> Unit,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    val scheme = MaterialTheme.colorScheme
    val dueText = parsed.dueAt?.let { DateFormats.short(it, format, withTime = parsed.dueHasTime) }
    val hasDueChip = parsed.chips.any { it.kind == QuickTokenKind.Due }
    val rule = remember(parsed.repeat, parsed.dueAt, format) {
        parsed.repeat?.let { quick ->
            RepeatRule.fromJson(quick.toJson())?.let { repeat ->
                RepeatSentence(QuickAddRepeatStrings(resources))
                    .sentence(repeat, format, parsed.dueAt?.toLocalDate()?.toString())
                    .split(" · ")
                    .first()
            }
        }.orEmpty()
    }
    val dueTemplate = stringResource(R.string.task_quickParse_due)
    val repeatTemplate = stringResource(R.string.task_quickParse_repeat)
    val priorityTemplate = stringResource(R.string.task_quickParse_priority)
    val priorityName = parsed.priority?.let { stringResource(priorityLabel(it)) }.orEmpty()
    val priorityChipTone = priorityTone(parsed.priority)
    val keepAsText = stringResource(R.string.task_quickParse_keepAsText)
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.x2),
        verticalArrangement = Arrangement.spacedBy(Space.x1),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        parsed.chips.forEach { chip ->
            val (icon, label, tone) = when (chip.kind) {
                QuickTokenKind.Due, QuickTokenKind.Time -> Triple(Icons.Outlined.Event, dueTemplate.fill("date" to dueText.orEmpty()), ChipTone.Today)
                QuickTokenKind.Repeat -> Triple(
                    Icons.Outlined.Repeat,
                    if (hasDueChip || dueText == null) rule else repeatTemplate.fill("rule" to rule, "date" to dueText),
                    ChipTone.Repeat,
                )
                QuickTokenKind.Priority -> Triple(Icons.Outlined.Flag, priorityTemplate.fill("label" to priorityName), priorityChipTone)
            }
            QuickAddTokenChip(
                icon = icon,
                label = label,
                tone = tone,
                keepAsText = keepAsText,
                onClick = { onDismiss(chip) },
                modifier = Modifier.testTag("$tag-token-${chip.kind.id}"),
            )
        }
        RemftIconButton(label = stringResource(R.string.task_quickParse_helpTooltip), onClick = onHelp, modifier = Modifier.testTag("$tag-help-inline")) {
            Icon(
                Icons.AutoMirrored.Outlined.HelpOutline,
                contentDescription = stringResource(R.string.task_quickParse_helpTooltip),
                tint = scheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(name = "Quick add light")
@Composable
private fun QuickAddLightPreview() {
    RemftTheme(darkTheme = false) { QuickAddPreviewContent() }
}

@Preview(name = "Quick add dark")
@Composable
private fun QuickAddDarkPreview() {
    RemftTheme(darkTheme = true) { QuickAddPreviewContent() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickAddPreviewContent() {
    val sample = "Water the plants tomorrow 7pm every sunday p1"
    val style = SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    PreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(Space.x3)) {
            QuickAddField("", {}, "Quick add a task…", "preview-empty", VisualTransformation.None, onHelp = {}, onSubmit = {})
            QuickAddField(
                sample,
                {},
                "Quick add a task…",
                "preview-filled",
                QuickAddHighlight(listOf(17..28, 30..41, 43..44), style),
                onHelp = {},
                onSubmit = {},
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.x2), verticalArrangement = Arrangement.spacedBy(Space.x1)) {
                QuickAddTokenChip(Icons.Outlined.Event, "Due 23 Nov, 7:00 pm", ChipTone.Today, "Keep as text", onClick = {})
                QuickAddTokenChip(Icons.Outlined.Repeat, "Every Sunday", ChipTone.Repeat, "Keep as text", onClick = {})
                QuickAddTokenChip(Icons.Outlined.Flag, "Priority: High", ChipTone.Urgent, "Keep as text", onClick = {})
            }
        }
    }
}
