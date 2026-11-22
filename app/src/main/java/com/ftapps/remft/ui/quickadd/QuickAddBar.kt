package com.ftapps.remft.ui.quickadd

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
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.dp
import com.ftapps.remft.R
import com.ftapps.remft.domain.quickadd.QuickChip
import com.ftapps.remft.domain.quickadd.QuickParse
import com.ftapps.remft.domain.quickadd.QuickTokenKind
import com.ftapps.remft.domain.repeat.RepeatRule
import com.ftapps.remft.domain.repeat.RepeatSentence
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.text.fill
import com.ftapps.remft.domain.time.DateFormats
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
import com.ftapps.remft.ui.tasks.priorityColor
import com.ftapps.remft.ui.tasks.priorityLabel
import com.ftapps.remft.ui.tasks.rememberDateFormat
import com.ftapps.remft.ui.theme.MarkedShapes
import com.ftapps.remft.ui.theme.RemftThemeExtras
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

@Composable
fun QuickAddBar(
    hint: String,
    tag: String,
    onSubmit: (raw: String, parsed: QuickParse) -> Unit,
    modifier: Modifier = Modifier,
) {
    val env = LocalTasksEnvironment.current
    val creator = remember(env) { QuickAddCreator(env.repository, env.series) }
    val format = rememberDateFormat()
    var text by rememberSaveable { mutableStateOf("") }
    var ignored by remember { mutableStateOf(emptySet<String>()) }
    var helpOpen by remember { mutableStateOf(false) }
    val parsed = remember(text, ignored, format) { creator.parse(text, format, ignored) }
    val markStyle = SpanStyle(color = RemftThemeExtras.colors.link, fontWeight = FontWeight.SemiBold)
    val highlight = remember(parsed.highlightRanges, markStyle) { QuickAddHighlight(parsed.highlightRanges, markStyle) }
    val submit = submit@{
        val raw = text.trim()
        if (raw.isEmpty()) return@submit
        val finalParse = creator.parse(raw, format, ignored)
        text = ""
        ignored = emptySet()
        onSubmit(raw, finalParse)
    }
    Column(modifier.fillMaxWidth().padding(vertical = Space.x2).animateContentSize()) {
        OutlinedTextField(
            value = text,
            onValueChange = { value ->
                if (value.isEmpty()) ignored = emptySet()
                text = value
            },
            placeholder = { Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            visualTransformation = highlight,
            shape = MarkedShapes.pill,
            textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            trailingIcon = {
                if (text.isEmpty()) {
                    IconButton(onClick = { helpOpen = true }, modifier = Modifier.testTag("$tag-help")) {
                        Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = stringResource(R.string.task_quickParse_helpTooltip))
                    }
                } else {
                    IconButton(onClick = submit, modifier = Modifier.testTag("$tag-submit")) {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.task_addTask))
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag),
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
    val priorityTint = parsed.priority?.let { priorityColor(it) }
    val keepAsText = stringResource(R.string.task_quickParse_keepAsText)
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.x2),
        verticalArrangement = Arrangement.spacedBy(Space.x1),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        parsed.chips.forEach { chip ->
            val (icon, label, tint) = when (chip.kind) {
                QuickTokenKind.Due, QuickTokenKind.Time -> Triple(Icons.Outlined.Event, dueTemplate.fill("date" to dueText.orEmpty()), null)
                QuickTokenKind.Repeat -> Triple(
                    Icons.Outlined.Repeat,
                    if (hasDueChip || dueText == null) rule else repeatTemplate.fill("rule" to rule, "date" to dueText),
                    null,
                )
                QuickTokenKind.Priority -> Triple(Icons.Outlined.Flag, priorityTemplate.fill("label" to priorityName), priorityTint)
            }
            InputChip(
                selected = false,
                onClick = { onDismiss(chip) },
                label = { Text(label) },
                avatar = { Icon(icon, contentDescription = null, tint = tint ?: scheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) },
                trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = keepAsText, modifier = Modifier.size(18.dp)) },
                shape = MarkedShapes.pill,
                colors = InputChipDefaults.inputChipColors(containerColor = scheme.surface, trailingIconColor = scheme.onSurfaceVariant),
                border = InputChipDefaults.inputChipBorder(enabled = true, selected = false, borderColor = scheme.outlineVariant),
                modifier = Modifier.testTag("$tag-token-${chip.kind.id}"),
            )
        }
        IconButton(onClick = onHelp, modifier = Modifier.testTag("$tag-help-inline")) {
            Icon(
                Icons.AutoMirrored.Outlined.HelpOutline,
                contentDescription = stringResource(R.string.task_quickParse_helpTooltip),
                tint = scheme.onSurfaceVariant,
            )
        }
    }
}
