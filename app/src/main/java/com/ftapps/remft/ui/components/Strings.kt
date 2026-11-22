package com.ftapps.remft.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ftapps.remft.domain.text.fill

@Composable
fun stringFilled(@StringRes id: Int, vararg values: Pair<String, Any?>): String = stringResource(id).fill(*values)
