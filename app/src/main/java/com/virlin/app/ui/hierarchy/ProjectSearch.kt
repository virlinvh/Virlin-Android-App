package com.virlin.app.ui.hierarchy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.WorkStream

data class ProjectSearchItem(val id: String, val title: String, val kind: String, val path: String)

/** Title-only matching. Ownership is resolved from the actual stream, not a stale task project field. */
internal object ProjectSearch {
    fun index(projectId: String, streams: List<WorkStream>, tasks: List<Task>): List<ProjectSearchItem> {
        val owners = streams.associateBy { it.id }
        val mine = streams.filter { it.projectId == projectId }
        val scoped = tasks.filter { task ->
            if (task.workStreamId != null) owners[task.workStreamId]?.projectId == projectId else task.projectId == projectId
        }
        val byId = scoped.associateBy { it.id }
        return mine.map { ProjectSearchItem(it.id, it.title, "Workstream", "") } + scoped.map { task ->
            val ancestors = mutableListOf<String>()
            val visited = mutableSetOf(task.id)
            var parent = task.parentTaskId
            while (parent != null && visited.add(parent)) {
                val node = byId[parent] ?: break
                ancestors += node.title
                parent = node.parentTaskId
            }
            val path = listOfNotNull(task.workStreamId?.let { owners[it]?.title }) + ancestors.reversed()
            ProjectSearchItem(task.id, task.title, if (task.parentTaskId == null) "Task" else "Subtask",
                path.joinToString(" › ").ifBlank { "Standalone task" })
        }
    }
    fun matches(items: List<ProjectSearchItem>, query: String) = items.filter {
        it.title.contains(query.trim(), ignoreCase = true)
    }
}

@Composable
internal fun ProjectSearchScreen(project: ProjectPageUi, actions: ProjectPageActions, onClose: () -> Unit) {
    var query by rememberSaveable(project.id) { mutableStateOf("") }
    val matches = remember(project.searchItems, query) { ProjectSearch.matches(project.searchItems, query) }
    Column(Modifier.fillMaxSize().testTag("project_search")) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close project search") }
            Text(project.title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        }
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            .testTag("project_search_query"), singleLine = true,
            placeholder = { Text("Search names in this project") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear search") } },
            shape = RoundedCornerShape(18.dp))
        Text("${matches.size} results · Workstreams, tasks and subtasks", style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (matches.isEmpty()) item { Text("No matching names in this project.", Modifier.testTag("project_search_empty")) }
            items(matches, key = { "${it.kind}:${it.id}" }) { item ->
                Column(Modifier.testTag("project_search_result_${item.id}")) {
                    if (item.kind == "Workstream") {
                        val stream = project.workstreams.firstOrNull { it.id == item.id }
                            ?: WorkstreamUi(item.id, item.title, 0, 0, unstructured = true)
                        ProjectSearchWorkstreamCard(stream, actions)
                    } else {
                        val row = project.searchTasks.firstOrNull { it.id == item.id }
                            ?: StreamTaskUi(item.id, null, item.title, 0)
                        ProjectStandaloneTaskCard(ProjectTaskUi(item.id, item.title, row.completed, row), row.order, actions)
                    }
                }
            }
        }
    }
}
