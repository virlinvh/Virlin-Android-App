package com.virlin.app.ui.map

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import com.virlin.app.domain.action.ActionResult
import com.virlin.app.domain.action.DomainError
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.ui.hierarchy.taskDetail
import com.virlin.app.ui.hierarchy.workStreamDetail

const val ProjectMapRoute = "project_map/{id}"
fun projectMap(id: String) = "project_map/$id"

/**
 * The full-screen map for one project.
 *
 * It is a PROJECTION: every node is read live from the repository, so a task edited elsewhere
 * shows here without any sync step, and nothing on this screen writes to a project, workstream
 * or task. Only the appearance is persisted, separately and per project.
 *
 * The project Overview stays underneath in the back stack, and a node opens the destination it
 * already had — a workstream opens WorkStream detail, a task opens Task detail.
 */
@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun ProjectMapScreen(projectId: String?, navController: NavController) {
    val context = LocalContext.current
    val repository = VirlinGraph.repository
    val projects by repository.projects.collectAsState()
    val streams by repository.streams.collectAsState()
    val tasks by repository.tasks.collectAsState()
    val project = projects.firstOrNull { it.id == projectId }
    val store = remember { MapAppearanceStore.get(context) }
    val saved by store.all.collectAsState()
    var preview by remember { mutableStateOf<VirlinMapSnapshot?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }

    if (project == null) {
        Box(Modifier.fillMaxSize()) { Text("This project is no longer available") }
        return
    }

    val topics = remember(project, streams, tasks) { projectMapTopics(project, streams, tasks) }
    val appearance = saved[project.id] ?: MapAppearance()

    val exportXmind = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { XmindWriter.write(topics, it) }
                ?: error("The file could not be opened for writing")
        }.onSuccess {
            Toast.makeText(context, "Exported .xmind", Toast.LENGTH_SHORT).show()
        }.onFailure { problem = it.message ?: "The export failed" }
    }

    val exportVirlin = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val bytes = encodeVirlinMap(
                VirlinMapSnapshot(project.id, project.title, topics, appearance)
            )
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("The file could not be opened for writing")
        }.onSuccess {
            Toast.makeText(context, "Exported Virlin map", Toast.LENGTH_SHORT).show()
        }.onFailure { problem = it.message ?: "The export failed" }
    }

    val openVirlin = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("The file could not be read")
            decodeVirlinMap(bytes)
        }.onSuccess {
            // A snapshot is shown, never merged: no live record is created or changed by it.
            preview = it
        }.onFailure { problem = it.message ?: "That file could not be read as a Virlin map" }
    }

    // ---- Edit structure -------------------------------------------------------------------
    // Every button below ends in VirlinActions.placeBranch; nothing here writes a parent id.
    val scope = rememberCoroutineScope()
    var structureMode by remember { mutableStateOf(false) }
    var selectedNode by remember { mutableStateOf<MapTopic?>(null) }
    var clipboard by remember { mutableStateOf<MapTopic?>(null) }
    var intent by remember { mutableStateOf<StructureIntent?>(null) }
    var refused by remember { mutableStateOf<String?>(null) }
    var lastCommit by remember {
        mutableStateOf<com.virlin.app.domain.structure.PlacementCommit?>(null)
    }
    val snackbar = remember { SnackbarHostState() }
    var renaming by remember { mutableStateOf<MapTopic?>(null) }
    // Messages that are not about a placement keep their own copy; reusing the placement
    // dialog's title hid the real reason an edit was refused.
    var notice by remember { mutableStateOf<Pair<String, String>?>(null) }
    /** The task a new child is being added under, while its title is being typed. */
    var addingChildTo by remember { mutableStateOf<MapTopic?>(null) }
    /** A choice made in the Add palette, waiting for its confirmation dialog. */
    var addChoice by remember { mutableStateOf<Pair<MapTopic, MapAddKind>?>(null) }

    val nodes = remember(streams, tasks, project) {
        com.virlin.app.domain.structure.HierarchyRules.nodesOf(project.id, streams, tasks)
    }
    val titles = remember(topics) { topics.associate { it.id to it.title } }
    fun branchSize(id: String): Int {
        val children = nodes.groupBy { it.parentId }
        var total = 0
        val stack = ArrayDeque<String>()
        stack.addLast(id)
        val seen = HashSet<String>()
        while (stack.isNotEmpty()) {
            val next = stack.removeLast()
            if (!seen.add(next)) continue
            total++
            children[next].orEmpty().forEach { stack.addLast(it.id) }
        }
        return total
    }

    /**
     * Would the command accept this drop? Asked with the SAME validator the transaction uses,
     * against the same node projection, so a target is highlighted only if it is really legal.
     * The authoritative check still happens inside the transaction at commit time.
     */
    fun canDrop(sourceId: String, targetId: String): Boolean {
        val request = com.virlin.app.domain.structure.Placement(
            sourceId = sourceId, targetParentId = targetId,
            operation = com.virlin.app.domain.structure.PlacementOperation.MOVE,
            expectedRevision = 0L
        )
        val check = com.virlin.app.domain.structure.HierarchyRules.validate(nodes, request, 0L)
        return check is com.virlin.app.domain.structure.PlacementCheck.Allowed
    }

    /**
     * May the branch be inserted NEXT TO this node? That is a question about the node's PARENT
     * accepting the branch, plus the node not being part of the branch itself - dropping beside
     * one of your own descendants is the same impossibility as dropping inside it.
     */
    fun canInsertBeside(sourceId: String, siblingId: String): Boolean {
        val sibling = nodes.firstOrNull { it.id == siblingId } ?: return false
        val parentId = sibling.parentId ?: return false
        if (siblingId == sourceId) return false
        return canDrop(sourceId, parentId) || parentId == nodes.firstOrNull { it.id == sourceId }?.parentId
    }

    /** The validator's own sentence for a refused drop. */
    fun refusalFor(sourceId: String, targetId: String): String {
        val request = com.virlin.app.domain.structure.Placement(
            sourceId = sourceId, targetParentId = targetId,
            operation = com.virlin.app.domain.structure.PlacementOperation.MOVE,
            expectedRevision = 0L
        )
        val check = com.virlin.app.domain.structure.HierarchyRules.validate(nodes, request, 0L)
        return (check as? com.virlin.app.domain.structure.PlacementCheck.Rejected)?.reason
            ?: "That branch cannot go there."
    }

    /**
     * Turns a screen slot into a stable sibling anchor.
     *
     * The planner counted positions among the boxes it could see; the command needs the id of
     * the sibling to land in front of, taken from the CANONICAL order. Going through the id
     * rather than the number means the placement still means the same thing if the hierarchy
     * moved under us - the revision check then refuses it rather than silently using a slot
     * that now points somewhere else.
     */
    fun anchorFor(sourceId: String, plan: MapDropPlan): String? {
        if (plan.kind == MapDropKind.INTO) return null
        val index = plan.insertionIndex ?: return null
        val parentId = plan.destinationParentId ?: return null
        val siblings = nodes
            .filter { it.parentId == parentId && it.id != sourceId }
            .sortedWith(compareBy({ it.order }, { it.id }))
        // Past the end means "append", which the command expresses as a null anchor.
        return siblings.getOrNull(index)?.id
    }

    suspend fun apply(
        sourceId: String,
        target: MapDestination,
        operation: com.virlin.app.domain.structure.PlacementOperation,
        insertBeforeId: String? = null,
        /**
         * What the drop said it would do, in the SAME words the lifted preview used. Without it
         * a precise insertion reported itself as "Moved into <parent>", which names a different
         * relationship from the "Before <sibling>" the user was reading when they let go.
         */
        placedDescription: String? = null,
    ) {
        val actions = VirlinGraph.actions
        val request = com.virlin.app.domain.structure.Placement(
            sourceId = sourceId,
            targetParentId = target.node.id,
            insertBeforeId = insertBeforeId,
            operation = operation,
            // Read afresh at the moment of committing: a stale value is refused by design.
            expectedRevision = actions.hierarchyRevision(project.id),
        )
        when (val result = actions.placeBranch(project.id, request)) {
            is ActionResult.Success -> {
                // The selection STAYS: the map keeps its own handle on this node, so clearing
                // only the host's copy left a handle floating with an empty action bar. Keeping
                // it also lets several moves chain on the same branch.
                val commit = result.value
                lastCommit = commit
                val undoable =
                    com.virlin.app.domain.structure.inverseOf(commit, project.id, 0L) != null
                // A queued snackbar would keep offering Undo for an EARLIER move while a later
                // one has already committed, so the visible one always belongs to the newest.
                snackbar.currentSnackbarData?.dismiss()
                val verb = if (operation == com.virlin.app.domain.structure.PlacementOperation.MOVE)
                    "Moved" else "Copied"
                val outcome = snackbar.showSnackbar(
                    message = placedDescription?.let { "$verb $it" } ?: "$verb into ${target.title}",
                    // Undo is offered only when a true inverse exists.
                    actionLabel = if (undoable) "Undo" else null,
                    withDismissAction = true,
                )
                if (outcome == SnackbarResult.ActionPerformed) {
                    val inverse = com.virlin.app.domain.structure.inverseOf(
                        commit, project.id, actions.hierarchyRevision(project.id)
                    )
                    if (inverse == null) {
                        refused = "This cannot be undone."
                    } else when (val undone = actions.placeBranch(project.id, inverse)) {
                        is ActionResult.Success -> Unit
                        is ActionResult.Rejected ->
                            refused = (undone.reason as? DomainError.PlacementRejected)?.reason
                                ?: "The undo was refused."
                        else -> refused = "The undo could not be completed."
                    }
                }
            }
            // A refusal wrote nothing, so any success message still on screen belongs to an
            // EARLIER placement. Left standing beside the refusal dialog it reads as though the
            // refused drop had succeeded, which is the opposite of what happened.
            is ActionResult.Rejected -> {
                snackbar.currentSnackbarData?.dismiss()
                val reason = (result.reason as? DomainError.PlacementRejected)?.reason
                // Dropping a branch back where it already sits is a harmless miss, not a broken
                // rule. Answering it with "That move is not allowed" makes an ordinary slip look
                // like a failure, so it gets a quiet line instead of a dialog.
                if (reason == com.virlin.app.domain.structure.HierarchyRules.ALREADY_IN_PLACE) {
                    snackbar.showSnackbar("Already in this position", withDismissAction = true)
                } else {
                    refused = reason ?: "That placement was refused."
                }
            }
            else -> {
                snackbar.currentSnackbarData?.dismiss()
                refused = "That placement could not be completed."
            }
        }
    }

    /**
     * THE route a node opens by. Both the map's own `onNodeOpen` and the bar's Open action call
     * this, so there is a single implementation of "what opening this node means".
     */
    fun openTopic(topic: MapTopic) {
        when (topic.kind) {
            // The project's own node is where we already are.
            MapKind.PROJECT -> Unit
            MapKind.WORKSTREAM -> navController.navigate(workStreamDetail(topic.id))
            MapKind.TASK -> navController.navigate(taskDetail(topic.id))
        }
    }

    VirlinProjectMindMap(
        projectTitle = project.title,
        topics = topics,
        appearance = appearance,
        onAppearanceChange = { store.save(project.id, it) },
        onNodeOpen = ::openTopic,
        onBack = { navController.popBackStack() },
        onExportXmind = { exportXmind.launch("${project.title.fileSafe()}.xmind") },
        onExportVirlin = { exportVirlin.launch("${project.title.fileSafe()}.virlinmap") },
        onOpenVirlinMap = { openVirlin.launch(arrayOf("*/*")) },
        structureMode = structureMode,
        onToggleStructureMode = {
            structureMode = !structureMode
            selectedNode = null
            if (!structureMode) clipboard = null
        },
        // One selection id: the map renders the host's value and reports every change here.
        selectedId = selectedNode?.id,
        onStructureSelect = { selectedNode = it },
        isValidTarget = ::canDrop,
        canInsertBeside = ::canInsertBeside,
        branchSizeOf = ::branchSize,
        onBranchDrop = { sourceId, plan ->
            // The drop runs the SAME command the destination sheet runs; the UI writes nothing.
            // INTO lands inside the node; BEFORE/AFTER lands inside that node's PARENT, at a
            // position named by a sibling id rather than by a screen coordinate.
            val parentId = plan.destinationParentId ?: plan.targetId
            val node = nodes.firstOrNull { it.id == parentId }
            if (node != null) {
                val destination = destinationsFor(nodes, titles, sourceId, project.title)
                    .firstOrNull { it.node.id == parentId }
                    ?: MapDestination(
                        node = node,
                        title = titles[parentId] ?: project.title,
                        breadcrumb = ""
                    )
                val anchor = anchorFor(sourceId, plan)
                // The same sentence the preview showed while the finger was down.
                val described = when (plan.kind) {
                    MapDropKind.INTO -> "into ${titles[plan.targetId] ?: project.title}"
                    MapDropKind.BEFORE -> "before ${titles[plan.targetId] ?: project.title}"
                    MapDropKind.AFTER -> "after ${titles[plan.targetId] ?: project.title}"
                }
                scope.launch {
                    apply(
                        sourceId, destination,
                        com.virlin.app.domain.structure.PlacementOperation.MOVE,
                        insertBeforeId = anchor,
                        placedDescription = described,
                    )
                }
            }
        },
        // Move: preselect the task and open the SAME destination sheet the structure bar uses.
        // Nothing is written until a destination is chosen in that sheet.
        onMoveTask = { topic ->
            selectedNode = topic
            intent = StructureIntent.MOVE
        },
        onOpenTaskPage = { topic ->
            navController.navigate(com.virlin.app.ui.page.taskPage(topic.id))
        },
        // Add: the palette itself writes nothing. Each choice lands here and runs exactly one
        // real flow, after its own confirmation step.
        onAddChoice = { topic, kind -> addChoice = topic to kind },
        onInvalidDrop = { sourceId, targetId ->
            scope.launch { snackbar.currentSnackbarData?.dismiss() }
            refused = refusalFor(sourceId, targetId)
        },
        structureBar = {
            MapStructureBar(
                selected = selectedNode,
                affectedCount = selectedNode?.let { branchSize(it.id) } ?: 0,
                clipboard = clipboard,
                organizing = structureMode,
                // The floating toolbar appears for a selected TASK and carries Open Task and
                // Move, so this bar must not repeat them.
                taskToolbarPresent = selectedNode?.kind == MapKind.TASK,
                onOpen = { selectedNode?.let(::openTopic) },
                onEdit = {
                    val topic = selectedNode
                    when (topic?.kind) {
                        // A task's title is edited here through the canonical updateTask; every
                        // other field lives on its own screen, which Open reaches.
                        MapKind.TASK -> renaming = topic
                        MapKind.PROJECT -> renaming = topic
                        // There is no workstream rename in VirlinActions, so editing one means
                        // its own screen rather than a map-only title.
                        MapKind.WORKSTREAM -> navController.navigate(workStreamDetail(topic.id))
                        null -> Unit
                    }
                },
                onMove = { intent = StructureIntent.MOVE },
                onCopy = { clipboard = selectedNode; selectedNode = null },
                onPaste = {
                    val source = clipboard
                    val into = selectedNode
                    if (source != null && into != null) {
                        val destination = destinationsFor(nodes, titles, source.id, project.title)
                            .firstOrNull { it.node.id == into.id }
                        if (destination == null) {
                            refused = "A copy cannot go there."
                        } else scope.launch {
                            apply(source.id, destination,
                                com.virlin.app.domain.structure.PlacementOperation.COPY)
                            clipboard = null
                        }
                    }
                },
                onClear = { selectedNode = null; clipboard = null },
            )
        },
    )

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        SnackbarHost(snackbar, Modifier.padding(bottom = 96.dp))
    }

    intent?.let { pending ->
        val source = selectedNode
        if (source == null) { intent = null; return@let }
        MapDestinationSheet(
            intent = pending,
            source = source,
            affectedCount = branchSize(source.id),
            destinations = destinationsFor(nodes, titles, source.id, project.title),
            onDismiss = { intent = null },
            onChoose = { destination ->
                intent = null
                scope.launch {
                    apply(
                        source.id, destination,
                        if (pending == StructureIntent.MOVE)
                            com.virlin.app.domain.structure.PlacementOperation.MOVE
                        else com.virlin.app.domain.structure.PlacementOperation.COPY
                    )
                }
            }
        )
    }

    renaming?.let { topic ->
        MapRenameDialog(
            current = topic.title,
            label = if (topic.kind == MapKind.PROJECT) "Project name" else "Task name",
            onDismiss = { renaming = null },
            onSave = { title ->
                renaming = null
                scope.launch {
                    val actions = VirlinGraph.actions
                    // The canonical paths: the same calls the project and task screens make.
                    val result = when (topic.kind) {
                        MapKind.PROJECT -> actions.updateProject(
                            topic.id,
                            com.virlin.app.domain.action.ProjectUpdate(
                                title = com.virlin.app.domain.action.Field.Set(title)
                            )
                        )
                        else -> actions.updateTask(
                            topic.id,
                            com.virlin.app.domain.action.TaskUpdate(
                                title = com.virlin.app.domain.action.Field.Set(title)
                            )
                        )
                    }
                    if (result is ActionResult.Success) selectedNode = null
                    else notice = "Can't rename this" to editRefusal(result)
                }
            }
        )
    }

    refused?.let { reason ->
        PlacementRefusedDialog(reason) { refused = null }
    }

    addChoice?.let { (topic, kind) ->
        when (kind) {
            // A To-do opens the task's own step list as a page; it never creates a task.
            MapAddKind.TODO -> {
                addChoice = null
                scope.launch { com.virlin.app.domain.action.TaskPageActions(VirlinGraph.repository, VirlinGraph.clock, VirlinGraph.ids).ensure(topic.id, com.virlin.app.domain.model.TaskPageTypeKeys.TODO, topic.id) }
                navController.navigate(com.virlin.app.ui.todo.taskTodo(topic.id))
            }
            // The third palette option is the task-attached Prompt workspace.
            MapAddKind.TASK -> {
                addChoice = null
                navController.navigate(com.virlin.app.ui.prompt.promptEditorForTask(topic.id))
            }
            // Link stays a full-page Capture workspace. It is attached only to the project;
            // nothing is rendered into or connected to this map canvas.
            MapAddKind.LINK -> {
                addChoice = null
                navController.navigate(com.virlin.app.ui.link.linkEditorForTask(topic.id))
            }
            // A Note opens the full-page Notes workspace. Its document is self-contained: the
            // task id only namespaces the key and titles the header, and no map node is created.
            MapAddKind.NOTE -> {
                addChoice = null
                scope.launch { com.virlin.app.domain.action.TaskPageActions(VirlinGraph.repository, VirlinGraph.clock, VirlinGraph.ids).ensure(topic.id, com.virlin.app.domain.model.TaskPageTypeKeys.NOTE, com.virlin.app.domain.model.TaskPageTypeKeys.noteOwner(topic.id)) }
                navController.navigate(com.virlin.app.ui.notes.notesForTask(topic.id, topic.title))
            }
            // Audio v1 = a recorded voice note owned by this task. It reuses the existing Capture
            // Voice workspace unchanged; only the task context is new. No map node is created.
            MapAddKind.AUDIO -> {
                addChoice = null
                navController.navigate(com.virlin.app.ui.voice.voiceEditorForTask(topic.id))
            }
            MapAddKind.PDF -> {
                addChoice = null
                navController.navigate(com.virlin.app.ui.pdf.pdfWorkspaceForTask(topic.id))
            }
            // Attachment is the task's universal file space. Each imported file becomes its own
            // CaptureItem(FILE) + AttachmentDocument, so no new container entity is introduced.
            MapAddKind.ATTACHMENT -> {
                addChoice = null
                navController.navigate(com.virlin.app.ui.attachment.attachmentWorkspaceForTask(topic.id))
            }
            MapAddKind.IMAGE -> {
                addChoice = null
                navController.navigate(com.virlin.app.ui.image.imageWorkspaceForTask(topic.id))
            }
            // The palette does not let the remaining kinds be chosen; belt-and-braces case.
            else -> {
                addChoice = null
                notice = kind.label + " is not available yet" to
                    "This build has nowhere to store a " + kind.label.lowercase() +
                    " against a task, so nothing was created."
            }
        }
    }

    addingChildTo?.let { parent ->
        MapAddSubtaskDialog(
            parentTitle = parent.title,
            onDismiss = { addingChildTo = null },
            onSave = { title ->
                addingChildTo = null
                scope.launch {
                    // addSubtask is THE canonical child creation: it inherits the parent's
                    // project and workstream and appends to the sibling group by the domain's
                    // own rules, so the map never writes an ownership field itself.
                    when (val result = VirlinGraph.actions.addSubtask(parent.id, title)) {
                        is ActionResult.Success -> {
                            // Stay on the parent so several children can be added in a row.
                            snackbar.currentSnackbarData?.dismiss()
                            snackbar.showSnackbar(
                                "Added \"" + title + "\" under " + parent.title,
                                withDismissAction = true,
                            )
                        }
                        else -> notice = "Can't add a subtask" to editRefusal(result)
                    }
                }
            }
        )
    }

    notice?.let { (title, message) ->
        MapNoticeDialog(title, message) { notice = null }
    }

    preview?.let { snapshot ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(snapshot.projectTitle) },
            text = {
                Text(
                    "This snapshot has ${snapshot.topics.size} nodes and uses the " +
                        "${snapshot.appearance.layout.label} layout. It is a file, not live work — " +
                        "Virlin will not change any project, workstream or task from it."
                )
            },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("Close") } }
        )
    }

    problem?.let { message ->
        AlertDialog(
            onDismissRequest = { problem = null },
            title = { Text("That did not work") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { problem = null }) { Text("Close") } }
        )
    }
}

private fun String.fileSafe() = filter { it.isLetterOrDigit() || it == ' ' || it == '-' }
    .trim().ifBlank { "Virlin project" }


/** The domain's reason an edit was refused, in the user's language. */
private fun editRefusal(result: ActionResult<*>): String = when (
    (result as? ActionResult.Rejected)?.reason
) {
    // The app's own rule: a finished task is closed, and closing is one-way here.
    DomainError.TaskAlreadyClosed ->
        "This task is already complete, and a completed task cannot be renamed."
    DomainError.EmptyTitle -> "A name cannot be empty."
    DomainError.ProjectAlreadyDone -> "This project is closed, so it cannot be renamed."
    else -> "That name could not be saved."
}
