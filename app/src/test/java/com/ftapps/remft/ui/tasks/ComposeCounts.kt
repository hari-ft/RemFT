package com.ftapps.remft.ui.tasks

import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText

fun SemanticsNodeInteractionsProvider.onAllNodesWithTextCount(text: String): Int =
    onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size

fun SemanticsNodeInteractionsProvider.onAllNodesWithTagCount(tag: String): Int =
    onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
