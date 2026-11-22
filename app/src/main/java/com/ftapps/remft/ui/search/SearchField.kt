package com.ftapps.remft.ui.search

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import com.ftapps.remft.R
import com.ftapps.remft.domain.Fold
import com.ftapps.remft.domain.search.SearchMatch
import com.ftapps.remft.ui.theme.Radius
import com.ftapps.remft.ui.theme.RemftThemeExtras
import com.ftapps.remft.ui.theme.Space

object SearchHighlight {
    fun ranges(text: String, query: String): List<IntRange> = rangesForWords(text, SearchMatch.queryWords(query))

    fun rangesForWords(text: String, words: List<String>): List<IntRange> {
        if (text.isEmpty() || words.isEmpty()) return emptyList()
        val folded = StringBuilder()
        val sourceStart = mutableListOf<Int>()
        val sourceEnd = mutableListOf<Int>()
        var offset = 0
        while (offset < text.length) {
            val code = text.codePointAt(offset)
            val char = String(Character.toChars(code))
            val piece = if (Character.isWhitespace(code)) " " else Fold.searchText(char)
            repeat(piece.length) {
                sourceStart.add(offset)
                sourceEnd.add(offset + char.length)
            }
            folded.append(piece)
            offset += char.length
        }
        val found = mutableListOf<IntRange>()
        for (word in words) {
            if (word.isEmpty()) continue
            var from = folded.indexOf(word)
            while (from >= 0) {
                found += sourceStart[from] until sourceEnd[from + word.length - 1]
                from = folded.indexOf(word, from + 1)
            }
        }
        return merge(found)
    }

    private fun merge(ranges: List<IntRange>): List<IntRange> {
        val sorted = ranges.filter { !it.isEmpty() }.sortedBy { it.first }
        val out = mutableListOf<IntRange>()
        for (range in sorted) {
            val last = out.lastOrNull()
            if (last != null && range.first <= last.last + 1) {
                out[out.lastIndex] = last.first..maxOf(last.last, range.last)
            } else {
                out += range
            }
        }
        return out
    }

    fun annotate(text: String, ranges: List<IntRange>, style: SpanStyle): AnnotatedString = buildAnnotatedString {
        append(text)
        ranges.forEach { range ->
            val start = range.first.coerceIn(0, text.length)
            val end = (range.last + 1).coerceIn(start, text.length)
            if (end > start) addStyle(style, start, end)
        }
    }
}

@Composable
fun searchMarkStyle(): SpanStyle = SpanStyle(color = RemftThemeExtras.colors.link, fontWeight = FontWeight.SemiBold)

@Composable
fun HighlightedText(
    text: String,
    query: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onSurface,
    maxLines: Int = 1,
) {
    val mark = searchMarkStyle()
    val annotated = remember(text, query, mark) { SearchHighlight.annotate(text, SearchHighlight.ranges(text, query), mark) }
    Text(annotated, modifier = modifier, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    hint: String,
    tag: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onSearch: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        shape = RoundedCornerShape(Radius.input),
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.testTag("$tag-clear")) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.common_clear))
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Space.x1)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .testTag(tag),
    )
}
