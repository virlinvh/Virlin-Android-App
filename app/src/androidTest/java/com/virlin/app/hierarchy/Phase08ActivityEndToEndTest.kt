package com.virlin.app.hierarchy

import androidx.test.platform.app.InstrumentationRegistry
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.domain.activity.ActivityEntryKind
import com.virlin.app.domain.activity.ProjectActivity
import com.virlin.app.domain.activity.ProjectActivityEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * End-to-end over the REAL database: seed Phase 08 Project through the app's own actions, then
 * read the Activity record back the way the screen reads it. This proves the data path, not the
 * pixels — the UI is checked separately by [ProjectActivityUiTest] and on the device by hand.
 */
class Phase08ActivityEndToEndTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var feed: List<ProjectActivityEntry>
    private lateinit var seeded: Phase08ActivitySeed.Seeded

    @Before fun setUp() = runBlocking {
        VirlinGraph.init(context)
        // The repository hydrates off the main thread; its flows are empty until it is bound,
        // so await the app's own readiness rather than seeding into an empty view of it.
        VirlinGraph.ensureReady()
        withTimeout(20_000) {
            while (VirlinGraph.repository.projects.value.isEmpty()) delay(100)
        }
        seeded = Phase08ActivitySeed.seed(context)
        feed = read()
    }

    private fun read(): List<ProjectActivityEntry> = runBlocking {
        val repository = VirlinGraph.repository
        val project = seeded.project
        val streams = repository.streams.value
        val captures = repository.captures.value
        val ids = streams.filter { it.projectId == project.id }.map { it.id }
        val captureIds = captures.filter { it.projectId == project.id }.map { it.id }
        ProjectActivity.build(
            project, streams, repository.tasks.value,
            repository.getEventsForStreams(ids, 500), captures,
            ProjectActivity.Documents(
                notes = repository.getNotesByCaptureIds(captureIds),
                prompts = repository.getPromptsByCaptureIds(captureIds),
                attachments = repository.getAttachmentsByCaptureIds(captureIds),
                voices = repository.getVoicesByCaptureIds(captureIds)
            )
        )
    }

    private fun of(kind: ActivityEntryKind) = feed.filter { it.kind == kind }

    @Test fun everySupportedTypeIsPresent() {
        // 1 prompt, 2 note + file, 3 image, 4 audio, 5 task, 6 checklist, 7 event, 8 link.
        listOf(
            ActivityEntryKind.PROMPT, ActivityEntryKind.NOTE, ActivityEntryKind.FILE,
            ActivityEntryKind.IMAGE, ActivityEntryKind.AUDIO, ActivityEntryKind.TASK,
            ActivityEntryKind.CHECKLIST, ActivityEntryKind.EVENT, ActivityEntryKind.LINK
        ).forEach { kind ->
            assertTrue("missing $kind in the Phase 08 record", of(kind).isNotEmpty())
        }
    }

    @Test fun promptKeepsItsExactFormatting() {
        val prompt = of(ActivityEntryKind.PROMPT).first { it.captureId == "${Phase08ActivitySeed.PREFIX}-prompt" }
        val body = requireNotNull(prompt.content)
        assertTrue("heading lost", body.contains("Activity timeline prompt"))
        assertTrue("numbered step lost", body.contains("Group it by the viewer's own local day."))
        assertTrue("code fence lost", body.contains("events.sortedByDescending { it.at }"))
        // Line breaks are preserved, not reflowed into one paragraph.
        assertTrue("line breaks lost", body.lines().size >= 5)
    }

    @Test fun imageAndAudioPointAtRealBytes() {
        val image = of(ActivityEntryKind.IMAGE).first()
        val imageFile = File(com.virlin.app.data.attachment.AttachmentFileStore.attachmentsRoot(context), image.mediaPath!!)
        assertTrue("image bytes missing", imageFile.exists() && imageFile.length() > 0)

        val audio = of(ActivityEntryKind.AUDIO).first()
        val audioFile = File(com.virlin.app.data.voice.VoiceFileStore.voicesRoot(context), audio.mediaPath!!)
        assertTrue("audio bytes missing", audioFile.exists() && audioFile.length() > 0)
        assertEquals(3_000L, audio.audioDurationMillis)
        // The duration the row shows is the one MediaPlayer reports for the real file.
        val player = android.media.MediaPlayer().apply { setDataSource(audioFile.absolutePath); prepare() }
        assertTrue("player duration ${player.duration}ms", player.duration in 2_800..3_200)
        player.release()
    }

    @Test fun aTaskShowsItsRealTransition_andKeepsItAfterTheTaskMovesOn() {
        val completed = feed.first { it.taskId == seeded.completedTask.id && it.title.startsWith("Completed") }
        assertEquals("In progress", completed.fromStatus)
        assertEquals("Done", completed.toStatus)
        assertTrue(completed.hasSnapshot)

        // Rename the task now; the past event must keep the title it was recorded with.
        runBlocking {
            VirlinGraph.actions.updateTask(
                seeded.completedTask.id,
                com.virlin.app.domain.action.TaskUpdate(
                    title = com.virlin.app.domain.action.Field.Set("[TEST] renamed after the fact")
                )
            )
        }
        val after = read().first { it.id == completed.id }
        assertEquals(completed.title, after.title)
        assertEquals(seeded.completedTask.title, after.taskName)
    }

    @Test fun checklistCountsTheSavedState() {
        val checklist = of(ActivityEntryKind.CHECKLIST).first()
        assertEquals(3, checklist.totalSteps)
        assertEquals(3, checklist.completedSteps)
        // Honest about itself: a checklist row is current state, not history.
        assertTrue(!checklist.hasSnapshot)
    }

    @Test fun aProjectLevelRecordNeedsNoTaskOrWorkStream() {
        val ai = feed.first { it.captureId == "${Phase08ActivitySeed.PREFIX}-ai" }
        assertEquals(null, ai.taskId)
        assertEquals(null, ai.workStreamId)
        assertEquals(seeded.project.id, ai.projectId)
    }

    @Test fun nothingFromAnotherProjectLeaksIn() {
        val foreignStreams = VirlinGraph.repository.streams.value
            .filter { it.projectId != null && it.projectId != seeded.project.id }.map { it.id }.toSet()
        assertTrue(feed.none { it.workStreamId in foreignStreams })
        assertTrue(feed.all { it.projectId == seeded.project.id })
    }

    @Test fun theRecordIsNewestFirstAndEveryRowIsIdentifiable() {
        assertEquals(feed.sortedByDescending { it.occurredAt }.map { it.id }, feed.map { it.id })
        assertEquals(feed.size, feed.map { it.id }.toSet().size)
        feed.forEach { assertNotNull(it.occurredAt) }
    }

    @Test fun seedingTwiceDoesNotDuplicate() {
        val before = feed.size
        runBlocking { Phase08ActivitySeed.seed(context) }
        val after = read()
        // Re-running the seed writes nothing new at all: every step is guarded.
        assertEquals(before, after.size)
        assertEquals(
            after.filter { it.captureId != null }.map { it.captureId }.toSet().size,
            after.count { it.captureId != null }
        )
    }
}
