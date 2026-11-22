package com.ftapps.remft.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.ftapps.remft.R
import com.ftapps.remft.domain.tasks.DeepLinkTarget
import com.ftapps.remft.domain.tasks.DeepLinks
import com.ftapps.remft.ui.files.FileLinks
import com.ftapps.remft.ui.files.LocalFilesEnvironment
import com.ftapps.remft.ui.tasks.HomeRequest
import com.ftapps.remft.ui.tasks.LocalTasksEnvironment
import com.ftapps.remft.ui.tasks.TaskDetailScreen
import kotlinx.serialization.Serializable

@Serializable
data class TaskRoute(val id: String)

fun NavGraphBuilder.taskDestinations(navController: NavHostController) {
    composable<TaskRoute> { entry ->
        TaskDetailScreen(taskId = entry.toRoute<TaskRoute>().id, onBack = { navController.popBackStack() })
    }
}

fun NavHostController.openTask(id: String) {
    navigate(TaskRoute(id)) { launchSingleTop = true }
}

private fun NavHostController.openSection(route: Any) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun TaskDeepLinkEffect(link: String?, navController: NavHostController, onHandled: () -> Unit) {
    val env = LocalTasksEnvironment.current
    val files = LocalFilesEnvironment.current
    val context = LocalContext.current
    LaunchedEffect(link) {
        if (link == null) return@LaunchedEffect
        if (DeepLinks.isUnknownAppLink(link)) {
            android.widget.Toast.makeText(context, R.string.error_routeNotFound, android.widget.Toast.LENGTH_LONG).show()
        }
        when (val target = DeepLinks.appLinkTarget(link)) {
            null -> Unit
            DeepLinkTarget.Home -> navController.openSection(HomeRoute)
            DeepLinkTarget.Add -> {
                env.homeRequests.post(HomeRequest.Create)
                navController.openSection(HomeRoute)
            }
            is DeepLinkTarget.Day -> {
                env.homeRequests.post(HomeRequest.SelectDay(target.date))
                navController.openSection(HomeRoute)
            }
            is DeepLinkTarget.OpenTask -> navController.openTask(target.id)
            DeepLinkTarget.Files -> {
                files?.requests?.open(FileLinks.idOf(link))
                navController.openSection(FilesRoute)
            }
        }
        onHandled()
    }
}
