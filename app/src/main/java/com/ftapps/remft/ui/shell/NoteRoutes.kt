package com.ftapps.remft.ui.shell

import androidx.compose.ui.unit.Dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.ftapps.remft.ui.notes.MarkdownImportHost
import com.ftapps.remft.ui.notes.NoteEditorHost
import com.ftapps.remft.ui.notes.NotesHost
import kotlinx.serialization.Serializable

@Serializable data class NoteRoute(val id: String? = null, val external: Boolean = false)

@Serializable data object MarkdownImportRoute

fun NavGraphBuilder.notesSection(navController: NavHostController, bottomPadding: Dp, onOpenSettings: () -> Unit) {
    composable<NotesRoute> {
        NotesHost(
            bottomPadding = bottomPadding,
            onOpenSettings = onOpenSettings,
            onOpenNote = { id -> navController.navigate(NoteRoute(id)) { launchSingleTop = true } },
            onOpenExternal = { navController.navigate(NoteRoute(external = true)) { launchSingleTop = true } },
            onOpenImport = { navController.navigate(MarkdownImportRoute) { launchSingleTop = true } },
        )
    }
    composable<NoteRoute> {
        NoteEditorHost(onBack = { navController.popBackStack() })
    }
    composable<MarkdownImportRoute> {
        MarkdownImportHost(
            onBack = { navController.popBackStack() },
            onSaved = { id ->
                navController.popBackStack()
                navController.navigate(NoteRoute(id)) { launchSingleTop = true }
            },
        )
    }
}
