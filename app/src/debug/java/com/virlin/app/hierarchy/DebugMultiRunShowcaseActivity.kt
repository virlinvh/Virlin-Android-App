package com.virlin.app.hierarchy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.virlin.app.ui.hierarchy.ProjectPageActions
import com.virlin.app.ui.hierarchy.ProjectPageUi
import com.virlin.app.ui.hierarchy.VirlinProjectPage
import com.virlin.app.ui.hierarchy.WorkstreamState
import com.virlin.app.ui.hierarchy.WorkstreamUi
import com.virlin.app.ui.theme.VirlinTheme

/** Debug-only host for visually auditing the real production Multi Run renderer. */
class DebugMultiRunShowcaseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val noOpActions = ProjectPageActions(
            onBack = { finish() }, onSearch = {}, onProgress = {}, onOpenWorkstream = {},
            onAddWorkstream = {}, onOpenTask = {}, onToggleTask = {}, onTaskMenu = {},
            onAddTask = {}, onOpenDocument = {}, onAddDocument = {}, onProjectSettings = {},
            onEditProject = {}, onManageWorkstreams = {}
        )
        setContent {
            VirlinTheme {
                VirlinProjectPage(
                    ui = ProjectPageUi(
                        id = "multi-run-showcase",
                        title = "Execution Showcase",
                        description = "Verified visual state",
                        workstreams = listOf(
                            WorkstreamUi(
                                id = "core-platform",
                                title = "Core Platform",
                                completedTasks = 3,
                                totalTasks = 12,
                                state = WorkstreamState.MULTI_RUN,
                                stateLabel = "Multiple active tasks",
                                activeRunCount = 3
                            )
                        ),
                        standaloneTasks = emptyList(),
                        documents = emptyList()
                    ),
                    actions = noOpActions
                )
            }
        }
    }
}
