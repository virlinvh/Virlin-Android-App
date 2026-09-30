package com.virlin.app.ui.apps

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.ui.hierarchy.projectDetail
import com.virlin.app.ui.map.projectMap
import com.virlin.app.ui.map.projectMapTopics

const val MindMapsHomeRoute = "apps_mind_maps"
const val MindMapsFolderRoute = "apps_mind_maps/{id}"
fun mindMapsFolder(projectId: String) = "apps_mind_maps/$projectId"

/**
 * Additional, user-created maps have no storage in this app yet: a map is a live PROJECTION of
 * a project's hierarchy, so every project has exactly one — its default — and it already
 * exists. Creating a second map would need a map entity of its own, which is out of scope here,
 * so the action says so instead of pretending.
 */
private const val ADDITIONAL_MAPS_REASON =
    "A map is a live view of this project's work, so each project has one. Maps you create " +
        "and edit separately are not supported yet."

/**
 * Builds the gallery's model from the real records. The default map is identified by the
 * project's own stable id plus its default flag, and it is DERIVED rather than stored, so
 * nothing can be duplicated or overwritten by opening this screen.
 */
private fun mindMapProjects(
    projects: List<Project>,
    streams: List<WorkStream>,
    tasks: List<Task>,
): List<MindMapProject> = projects.map { project ->
    val topics = projectMapTopics(project, streams, tasks)
    val nodes = topics.size - 1      // the project's own root is not a task
    MindMapProject(
        id = project.id,
        title = project.title,
        maps = listOf(
            MindMapEntry(
                // Stable and idempotent: the same project always yields the same map id.
                id = defaultMapId(project.id),
                projectId = project.id,
                title = project.title,
                isDefault = true,
                topics = topics,
                subtitle = if (nodes == 0) "No workstreams or tasks yet"
                else "$nodes ${if (nodes == 1) "node" else "nodes"} from this project"
            )
        )
    )
}

fun defaultMapId(projectId: String) = "map_default_$projectId"

@Composable
fun MindMapsHomeHost(navController: NavController) {
    val repository = VirlinGraph.repository
    val projects by repository.projects.collectAsState()
    val streams by repository.streams.collectAsState()
    val tasks by repository.tasks.collectAsState()
    val model = remember(projects, streams, tasks) { mindMapProjects(projects, streams, tasks) }

    var picking by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    MindMapsHomeScreen(
        projects = model,
        onBack = { navController.popBackStack() },
        onOpenProject = { navController.navigate(mindMapsFolder(it)) },
        onCreate = { picking = true },
        onProjectMenu = { menuFor = it },
        // No opened-at timestamp is recorded anywhere, so the link stays out until it is.
        onRecent = null,
    )

    if (picking) ProjectChoiceDialog(
        title = "Which project?",
        projects = model,
        onDismiss = { picking = false },
        onPick = { id ->
            picking = false
            // Its map already exists; opening it is the whole create flow, and it is idempotent.
            navController.navigate(projectMap(id))
        }
    )

    menuFor?.let { id ->
        val project = model.first { it.id == id }
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(project.title) },
            text = {
                Column {
                    DialogRow("Open the project's map") {
                        menuFor = null; navController.navigate(projectMap(id))
                    }
                    DialogRow("Open the project") {
                        menuFor = null; navController.navigate(projectDetail(id))
                    }
                    Text(ADDITIONAL_MAPS_REASON, modifier = Modifier.padding(top = 10.dp))
                }
            },
            confirmButton = { TextButton(onClick = { menuFor = null }) { Text("Close") } }
        )
    }
}

@Composable
fun MindMapsFolderHost(projectId: String?, navController: NavController) {
    val repository = VirlinGraph.repository
    val projects by repository.projects.collectAsState()
    val streams by repository.streams.collectAsState()
    val tasks by repository.tasks.collectAsState()
    val model = remember(projects, streams, tasks, projectId) {
        mindMapProjects(projects.filter { it.id == projectId }, streams, tasks)
    }
    val project = model.firstOrNull()
    var explain by remember { mutableStateOf(false) }

    if (project == null) {
        Text("This project is no longer available", Modifier.padding(20.dp))
        return
    }

    MindMapProjectScreen(
        project = project,
        onBack = { navController.popBackStack() },
        // Every map here is the project's own, so both routes lead to the same editor.
        onOpenMap = { navController.navigate(projectMap(project.id)) },
        onCreateInProject = { explain = true },
        canCreate = false,
        createUnavailableReason = ADDITIONAL_MAPS_REASON,
    )

    if (explain) AlertDialog(
        onDismissRequest = { explain = false },
        title = { Text("Not supported yet") },
        text = { Text(ADDITIONAL_MAPS_REASON) },
        confirmButton = { TextButton(onClick = { explain = false }) { Text("Close") } }
    )
}

@Composable
private fun ProjectChoiceDialog(
    title: String,
    projects: List<MindMapProject>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (projects.isEmpty()) Text("There are no projects yet.")
            else Column(Modifier.verticalScroll(rememberScrollState())) {
                projects.sortedBy { it.title.lowercase() }.forEach { project ->
                    DialogRow(project.title) { onPick(project.id) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun DialogRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier.fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp)
    )
}
