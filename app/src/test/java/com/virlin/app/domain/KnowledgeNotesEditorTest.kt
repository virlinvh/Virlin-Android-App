package com.virlin.app.domain

import com.virlin.app.domain.action.*
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.notedoc.*
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import com.virlin.app.ui.notes.NotesViewModel
import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class KnowledgeNotesEditorTest {
    @Test fun closingDuringAnEarlierSaveCommitsLatestTextBeforeNavigation() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val repo = InMemoryWorkStreamRepository(seedProjects = listOf(Project("p", "Project", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH)))
            val ids = SequentialIdProvider()
            val real = DefaultVirlinActions(repo, FakeClock(Instant.EPOCH), ids)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val delayed = object : VirlinActions by real {
                override suspend fun saveNoteDoc(ownerKey: String, title: String?, blocks: List<NoteDocBlock>): ActionResult<VirlinNoteDoc> {
                    if (++calls == 1) { started.complete(Unit); release.await() }
                    return real.saveNoteDoc(ownerKey, title, blocks)
                }
            }
            val vm = NotesViewModel(CaptureNoteBridge.draftOwner("p", "race"), "Note", delayed, ids)
            val block = vm.state.value.blocks.single().id
            vm.onTextChanged(block, "Earlier", TextRange(7))
            vm.flush()
            started.await()
            vm.onTextChanged(block, "Latest", TextRange(6))
            // Finish the OLD snapshot before closing, but before the next debounce fires.
            release.complete(Unit)
            var closed = false
            vm.saveAndClose { closed = true }
            advanceUntilIdle()
            assertTrue(closed)
            assertEquals("Latest", real.loadNoteDoc("capture-race")!!.blocks.single().text)
            assertEquals(1, repo.captures.value.size)
        } finally { Dispatchers.resetMain() }
    }
}
