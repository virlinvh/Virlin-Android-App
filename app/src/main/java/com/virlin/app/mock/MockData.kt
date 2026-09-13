package com.virlin.app.mock

import com.virlin.app.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

object MockData {
    val projects = listOf(
        Project("p1", "Virlin Development"),
        Project("p2", "MBA Project"),
        Project("p3", "Psychology"),
        Project("p4", "App Fix"),
        Project("p5", "IFET Skills Lab")
    )

    private val _streams = MutableStateFlow<List<WorkStream>>(emptyList())
    val streams: StateFlow<List<WorkStream>> = _streams
    
    private val _captures = MutableStateFlow<List<Capture>>(emptyList())
    val captures: StateFlow<List<Capture>> = _captures

    init {
        generateMockStreams()
        generateMockCaptures()
    }

    private fun generateMockStreams() {
        val initialStreams = mutableListOf(
            WorkStream("s1", "Psychology", "Unit 23 · Question 17", "p3", StreamState.FOCUS, 120, 1955, 0, null, false, "Complete Q17 answer"),
            WorkStream("s2", "Antigravity", "App Fix · Authentication redirect", "p4", StreamState.NEEDS_YOU, null, 0, 0, 0, false, null),
            WorkStream("s3", "Claude · Virlin", "Navigation · Route structure decision", "p1", StreamState.NEEDS_YOU, null, 0, 0, -60, true, null),
            WorkStream("s4", "Claude · Virlin", "Navigation implementation", "p1", StreamState.PROCESSING, null, 258, 0, 102, false, null),
            WorkStream("s5", "Codex · MBA Research", "Methodology research", "p2", StreamState.PROCESSING, null, 461, 0, 199, false, null),
            WorkStream("s6", "Build · Release APK", "Android build pipeline", "p1", StreamState.PROCESSING, null, 128, 0, null, false, null),
            WorkStream("s7", "Notion Transfer", "Move Skills Lab notes", "p5", StreamState.READY, 300, 0, 0, null, false, null),
            WorkStream("s8", "Psychology Notes", "Review Unit 22", "p3", StreamState.READY, 600, 0, 0, null, false, null),
            WorkStream("s9", "Cloud Sync", "Database migration", "p1", StreamState.BLOCKED, null, 0, 0, null, false, null, "Waiting for authentication credentials"),
            WorkStream("s10", "Design Review", "Typography updates", "p1", StreamState.SNOOZED, null, 0, 0, null, false, null)
        )
        for (i in 11..45) {
            initialStreams.add(
                WorkStream("s", "Task ", "Description ", "p1", StreamState.PAUSED, null, 0, 0, null, false, null)
            )
        }
        _streams.value = initialStreams
    }

    private fun generateMockCaptures() {
        _captures.value = listOf(
            Capture("c1", CaptureType.VOICE, "Voice note", "00:42", "Today"),
            Capture("c2", CaptureType.PROMPT, "Android Architecture Review", null, "Yesterday"),
            Capture("c3", CaptureType.LINK, "Android background execution article", "developer.android.com", "2 days ago"),
            Capture("c4", CaptureType.IMAGE, "Agent reference", "agent-reference.png", "2 days ago"),
            Capture("c5", CaptureType.TEXT, "Notification idea", "Maybe use bubbles?", "Last week")
        )
    }
    
    fun internalUpdateList(newList: List<WorkStream>) {
        _streams.value = newList
    }

    /** Atomic read-modify-write for DISPLAY data (counters, flags). Never used for state by UI. */
    fun mutate(transform: (List<WorkStream>) -> List<WorkStream>) {
        _streams.update(transform)
    }

    /**
     * DEMO-ONLY legacy mutator. The domain Action Layer (`VirlinGraph.actions`) is the single
     * product path for state changes; state written here would be overwritten by the
     * DomainDisplayBridge projection. Kept only so old call sites fail loudly if reintroduced.
     */
    @Deprecated("State changes must go through VirlinActions", level = DeprecationLevel.ERROR)
    fun updateStreamState(streamId: String, newState: StreamState, checkInSec: Int? = null) {
        val list = _streams.value.toMutableList()
        val idx = list.indexOfFirst { it.id == streamId }
        if (idx != -1) {
            val old = list[idx]
            if (newState == StreamState.FOCUS) {
                for (i in list.indices) {
                    if (list[i].state == StreamState.FOCUS) {
                        list[i] = list[i].copy(state = StreamState.READY)
                    }
                }
            }
            list[idx] = old.copy(
                state = newState, 
                checkInRemainingSec = checkInSec,
                processingElapsedSec = if (newState == StreamState.PROCESSING) 0 else old.processingElapsedSec,
                isOverdue = if (newState == StreamState.PROCESSING) false else old.isOverdue
            )
            _streams.value = list
        }
    }
    
    fun addCapture(capture: Capture) {
        _captures.value = listOf(capture) + _captures.value
    }
}
