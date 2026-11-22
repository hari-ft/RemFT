package com.ftapps.remft.ui.files

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.ftapps.remft.ui.bin.RecycleBinScreen
import com.ftapps.remft.ui.completed.CompletedScreen
import kotlinx.serialization.Serializable

@Serializable data object SettingsBinRoute

@Serializable data object SettingsCompletedRoute

object FileLinks {
    private val pattern = Regex("^remft://file/([0-9ABCDEFGHJKMNPQRSTVWXYZ]{26})/?$", RegexOption.IGNORE_CASE)

    fun idOf(link: String?): String? = link?.trim()?.let { pattern.matchEntire(it)?.groupValues?.get(1)?.uppercase() }

    fun uri(fileId: String): String = "remft://file/$fileId"

    fun markdown(fileId: String, name: String, mime: String): String {
        val safe = name.replace("[", "(").replace("]", ")")
        return if (mime.startsWith("image/")) "![$safe](${uri(fileId)})" else "[$safe](${uri(fileId)})"
    }
}

fun NavGraphBuilder.filesDestinations(onBack: () -> Unit, onOpenTask: (String) -> Unit) {
    composable<SettingsBinRoute> { RecycleBinScreen(onBack = onBack) }
    composable<SettingsCompletedRoute> { CompletedScreen(onBack = onBack, onOpenTask = onOpenTask) }
}
