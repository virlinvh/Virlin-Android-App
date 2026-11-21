package com.virlin.app.ui.map

import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.TaskStatus
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.progress.ProgressCalculator
import com.virlin.app.domain.model.ProgressResult

enum class MapKind { PROJECT, WORKSTREAM, TASK }
enum class MapStatus { NONE, TODO, CURRENT, DONE, BLOCKED }

enum class MapBoxFinishGroup(val label: String, val detail: String) {
    CLASSIC("Classic", "Clean surfaces."),
    HANDMADE("Hand-drawn & handmade", "Ink, pencil, paint and paper craft, drawn by hand."),
    MATERIAL("Materials & modern", "Real surfaces and modern stationery."),
}

/** Saved by name, so new finishes can be appended without touching stored maps. */
enum class MapBoxFinish(val label: String, val group: MapBoxFinishGroup = MapBoxFinishGroup.CLASSIC) {
    SMOOTH("Smooth"), FROSTED("Frosted"), PAPER("Fine paper"), LINEN("Linen"),
    GRAIN("Soft grain"), GLASS("Glass"), OUTLINE("Outline"), EMBOSSED("Embossed"),
    PENCIL_HATCH("Pencil hatch"), HAND_DRAWN("Hand drawn", MapBoxFinishGroup.HANDMADE),
    PENCIL_SKETCH("Pencil sketch", MapBoxFinishGroup.HANDMADE),
    DOUBLE_INK("Double ink", MapBoxFinishGroup.HANDMADE),
    MARKER_EDGE("Marker edge", MapBoxFinishGroup.HANDMADE),
    CRAYON("Crayon", MapBoxFinishGroup.HANDMADE),
    CHARCOAL("Charcoal", MapBoxFinishGroup.HANDMADE),
    WATERCOLOUR("Watercolour", MapBoxFinishGroup.HANDMADE),
    BRUSH_PEN("Brush pen", MapBoxFinishGroup.HANDMADE),
    FELT_TIP("Felt tip", MapBoxFinishGroup.HANDMADE),
    SCRIBBLE("Scribble fill", MapBoxFinishGroup.HANDMADE),
    CROSS_HATCH("Cross hatch", MapBoxFinishGroup.HANDMADE),
    STIPPLE("Stipple", MapBoxFinishGroup.HANDMADE),
    ROUGH_SKETCH("Rough sketch", MapBoxFinishGroup.HANDMADE),
    HIGHLIGHTER("Highlighter", MapBoxFinishGroup.HANDMADE),
    DRY_BRUSH("Dry brush", MapBoxFinishGroup.HANDMADE),
    STITCHED("Stitched", MapBoxFinishGroup.HANDMADE),
    TAPED_NOTE("Taped note", MapBoxFinishGroup.HANDMADE),
    STICKY_NOTE("Sticky note", MapBoxFinishGroup.HANDMADE),
    TORN_PAPER("Torn paper", MapBoxFinishGroup.HANDMADE),
    PAPER_CUTOUT("Paper cut-out", MapBoxFinishGroup.HANDMADE),
    KRAFT("Kraft card", MapBoxFinishGroup.MATERIAL),
    CANVAS("Canvas", MapBoxFinishGroup.MATERIAL),
    CORK("Cork board", MapBoxFinishGroup.MATERIAL),
    WOOD("Wood grain", MapBoxFinishGroup.MATERIAL),
    MARBLE("Marble", MapBoxFinishGroup.MATERIAL),
    CONCRETE("Concrete", MapBoxFinishGroup.MATERIAL),
    BRUSHED_METAL("Brushed metal", MapBoxFinishGroup.MATERIAL),
    SOFT_CLAY("Soft clay", MapBoxFinishGroup.MATERIAL),
    TERRAZZO("Terrazzo", MapBoxFinishGroup.MATERIAL),
    DOT_GRID("Dot grid", MapBoxFinishGroup.MATERIAL),
    GRAPH_PAPER("Graph paper", MapBoxFinishGroup.MATERIAL),
    NOTEBOOK("Notebook", MapBoxFinishGroup.MATERIAL),
    HALFTONE("Halftone", MapBoxFinishGroup.MATERIAL),
}

/** Saved by name, so new layouts can be appended without touching stored maps. */
enum class MapLayout(
    val label: String,
    val fork: String,
    val node: String,
    val endpoint: String,
    val handDrawn: Boolean = false,
) {
    RIGHT_TREE("Right tree", "Shared trunk", "Rounded cards", "Card tips"),
    FLOW_FORKS("Flow forks", "S-curves", "Pill cards", "Pill tips"),
    LOGIC_BRACKETS("Logic brackets", "Single bracket", "Square cards", "Capped tips"),
    CARD_CASCADE("Card cascade", "Stepped arms", "Offset cards", "Card tips"),
    STEP_RAIL("Step rail", "Shared rail", "Staggered cards", "Card tips"),
    SECTION_BANDS("Section bands", "Shared trunk", "Soft bands", "Card tips"),
    NESTED_BRACKETS("Nested brackets", "Double bracket", "Square cards", "Capped tips"),
    ROUNDED_FORKS("Capsule forks", "Early curves", "Capsule parents", "Compact leaves"),
    SCULPTED_BRANCHES("Sculpted branches", "Organic curves", "Sculpted cards", "Sculpted tips"),
    CLOUD_OUTCOMES("Cloud outcomes", "Diagonal links", "Square cards", "Cloud leaves"),
    FAN_FORKS("Beaded fan", "Wide fan", "Capsule cards", "Bead tips"),
    FACETED_PATHS("Faceted paths", "Angled links", "Faceted cards", "Pointed tips"),
    LOOP_FORKS("Loop & notch", "Looped curves", "Pill parents", "Notched leaves"),
    SKETCH_TREE("Pencil sketch", "Pencil trunk", "Sketched cards", "Ink dots", handDrawn = true),
    WHITEBOARD("Whiteboard", "Marker arrows", "Marker boxes", "Arrowheads", handDrawn = true),
    DOODLE_BUBBLES("Doodle bubbles", "Loose curves", "Doodle bubbles", "Tiny stars", handDrawn = true),
    STICKY_WALL("Sticky wall", "Pinned strings", "Sticky notes", "Push pins", handDrawn = true),
    BRUSH_INK("Brush & ink", "Brush strokes", "Ink-wash cards", "Brush flicks", handDrawn = true),
    DRAFTING("Drafting table", "Ruled elbows", "Drafted frames", "Dimension ticks", handDrawn = true),
    PAPER_CRAFT("Paper craft", "Paper ribbons", "Cut-paper cards", "Folded tabs", handDrawn = true),
    METRO_LINES("Metro lines", "Transit lines", "Station pills", "Station rings"),
}

/**
 * One node of the map. It is a PROJECTION of an existing record, never a record of its own:
 * [id] is the entity's own stable id (titles repeat, so nothing here is keyed by title) and
 * [parentId] is its real owner. Standalone project tasks carry the project's id as parent.
 */
data class MapTopic(
    val id: String,
    val parentId: String?,
    val title: String,
    val detail: String = "",
    val kind: MapKind,
    val status: MapStatus = MapStatus.NONE,
    val order: Int = 0,
)

data class MapAppearance(
    val layout: MapLayout = MapLayout.RIGHT_TREE,
    val paletteIndex: Int = 0,
    /** ARGB keyed by stable entity id. Node override beats branch override beats palette. */
    val nodeColors: Map<String, Long> = emptyMap(),
    val branchColors: Map<String, Long> = emptyMap(),
    val customPalette: List<Long> = emptyList(),
    val savedColors: List<Long> = emptyList(),
    val boxFinish: MapBoxFinish = MapBoxFinish.SMOOTH,
    val nodeFinishes: Map<String, MapBoxFinish> = emptyMap(),
    /** Optional box widths in map units, keyed by stable node id. */
    val nodeWidths: Map<String, Float> = emptyMap(),
    /** Map-only visibility; hiding never removes the underlying project, workstream, or task. */
    val hiddenNodeIds: Set<String> = emptySet(),
    /** How connector colours are chosen. Per-branch overrides always win over the mode. */
    val branchMode: MapBranchMode = MapBranchMode.MULTI,
    /** The shared colour for [MapBranchMode.SINGLE]; null means the palette's first colour. */
    val singleBranchColor: Long? = null,
    /** [MapBranchMode.LEVELS] colours: [0] primary (workstreams), [1] secondary, ... Missing = palette. */
    val levelColors: List<Long> = emptyList(),
    val boxFill: MapFillMode = MapFillMode.SOFT,
    val boxFillColor: Long? = null,
    val boxBorder: MapBorderMode = MapBorderMode.AUTO,
    val boxBorderColor: Long? = null,
    /** Box title colour; null picks dark or light ink for the fill automatically. */
    val boxTextColor: Long? = null,
    /** Canvas colour behind the map; null keeps the project surface. */
    val backgroundColor: Long? = null,
    val savedPalettes: List<MapSavedPalette> = emptyList(),
    /** How the palette colours the boxes. Branch lines have their own [branchMode]. */
    val boxPaletteMode: MapBoxPaletteMode = MapBoxPaletteMode.WORKSTREAM,
    /** How much palette colour a Soft-tint box shows; Boxes ▸ Fill other than Soft tint decides on its own. */
    val boxStrength: MapBoxStrength = MapBoxStrength.MEDIUM,
    /** Chosen box colours by level: 0 project, 1 workstreams, 2 tasks, 3 subtasks, 4 deeper. Missing = palette. */
    val boxLevelColors: Map<Int, Long> = emptyMap(),
    /** The shared colour for [MapBoxPaletteMode.SINGLE]; null means the palette's first colour. */
    val boxSingleColor: Long? = null,
    /** Box colour for an item and everything under it. A one-box highlight ([nodeColors]) still wins. */
    val boxBranchColors: Map<String, Long> = emptyMap(),
    /** Multicolour branch lines; empty follows the palette. */
    val branchPalette: List<Long> = emptyList(),
)

enum class MapBranchMode(val label: String) { MULTI("Multicolour"), SINGLE("One colour"), LEVELS("By level") }

enum class MapBoxPaletteMode(val label: String) {
    WORKSTREAM("By workstream"), LEVEL("By level"), SINGLE("One colour"), EVERY("Every box"), BRANCHES("Match lines")
}

enum class MapBoxStrength(val label: String) { LIGHT("Light"), MEDIUM("Medium"), STRONG("Strong") }

enum class MapFillMode(val label: String) {
    SOFT("Soft tint"), PASTEL("Pastel"), BOLD("Bold"), WHITE("Plain white"), CUSTOM("Custom")
}

/** AUTO keeps each finish's own edge: the branch colour on smooth boxes, ink on hand-drawn ones. */
enum class MapBorderMode(val label: String) {
    AUTO("Finish default"), BRANCH("Branch colour"), CUSTOM("Custom"), NONE("No border")
}

data class MapSavedPalette(val name: String, val colors: List<Long>)

/**
 * Builds the map from the real records. Every WorkStream of the project appears, including one
 * with no tasks; subtasks nest to whatever depth they actually have; nothing is created,
 * duplicated or reordered here.
 *
 * Counts and progress are shown only where the domain actually has them —
 * `ProgressCalculator` returns `Unstructured` for a stream with no executable work, and an empty
 * branch therefore reads as "no tasks yet", never as a finished one.
 */
fun projectMapTopics(
    project: Project,
    streams: List<WorkStream>,
    tasks: List<Task>,
): List<MapTopic> {
    val projectStreams = streams.filter { it.projectId == project.id }
        // The persisted sibling order (v14). Before that column existed this sorted by title,
        // which meant the map showed an order the user could not change.
        .sortedWith(compareBy({ it.sortOrder }, { it.id }))
    val streamIds = projectStreams.map { it.id }.toSet()
    // A task belongs to this map when its stream does, or when it is standalone in the project.
    val own = tasks.filter { task ->
        task.workStreamId?.let { it in streamIds } ?: (task.projectId == project.id)
    }
    val childrenOf = own.groupBy { it.parentTaskId }

    val topics = ArrayList<MapTopic>(own.size + projectStreams.size + 1)
    val projectProgress = ProgressCalculator.ofProject(tasks, streams, project.id)
    topics += MapTopic(
        id = project.id, parentId = null, title = project.title,
        detail = progressDetail(projectProgress), kind = MapKind.PROJECT,
    )

    projectStreams.forEachIndexed { index, stream ->
        topics += MapTopic(
            id = stream.id, parentId = project.id, title = stream.title,
            detail = progressDetail(ProgressCalculator.ofWorkStream(tasks, stream.id)),
            kind = MapKind.WORKSTREAM, status = stream.mapStatus(), order = index,
        )
    }

    // Iterative descent: a deep subtask chain must not depend on the JVM stack.
    data class Pending(val task: Task, val parentId: String, val order: Int)

    val stack = ArrayDeque<Pending>()
    // Roots: standalone tasks hang off the project, stream tasks off their stream.
    // Root tasks belong to DIFFERENT parents - one per workstream, plus the project itself for
    // standalone tasks - so they must be numbered per parent. Numbering them in one project-wide
    // sweep gave a stream's first task an index of 1 or 2 simply because another stream's tasks
    // sorted ahead of it, which is not that group's sibling order.
    childrenOf[null].orEmpty()
        .groupBy { it.workStreamId ?: project.id }
        .forEach { (parentId, group) ->
            group.sortedWith(compareBy({ it.order }, { it.id }))
                .forEachIndexed { index, task -> stack.addLast(Pending(task, parentId, index)) }
        }
    val seen = HashSet<String>()
    while (stack.isNotEmpty()) {
        val (task, parentId, order) = stack.removeLast()
        if (!seen.add(task.id)) continue        // guards a malformed parent cycle
        topics += MapTopic(
            id = task.id, parentId = parentId, title = task.title,
            detail = subtaskDetail(childrenOf[task.id].orEmpty()),
            kind = MapKind.TASK, status = task.status.mapStatus(), order = order,
        )
        childrenOf[task.id].orEmpty()
            .sortedWith(compareBy({ it.order }, { it.id }))
            .forEachIndexed { index, child -> stack.addLast(Pending(child, task.id, index)) }
    }
    return topics
}

private fun progressDetail(progress: ProgressResult): String = when (progress) {
    is ProgressResult.Structured ->
        "${progress.completedLeaves}/${progress.totalLeaves} tasks"
    // The domain says there is nothing countable here; saying "0%" would be a claim it never made.
    ProgressResult.Unstructured -> "No tasks yet"
    ProgressResult.NoActiveWork -> "No active work"
}

private fun subtaskDetail(children: List<Task>): String {
    val active = children.filterNot { it.status == TaskStatus.CANCELLED }
    if (active.isEmpty()) return ""
    return "${active.count { it.status.isCompleted }}/${active.size} subtasks"
}

private fun WorkStream.mapStatus(): MapStatus = when (state) {
    WorkStreamState.FOCUS -> MapStatus.CURRENT
    WorkStreamState.BLOCKED -> MapStatus.BLOCKED
    WorkStreamState.DONE -> MapStatus.DONE
    else -> MapStatus.TODO
}

private fun TaskStatus.mapStatus(): MapStatus = when (this) {
    TaskStatus.DONE -> MapStatus.DONE
    TaskStatus.IN_PROGRESS -> MapStatus.CURRENT
    // Cancelled is terminal but NOT completed, so it must never read as done.
    TaskStatus.CANCELLED -> MapStatus.BLOCKED
    TaskStatus.TODO -> MapStatus.TODO
}
