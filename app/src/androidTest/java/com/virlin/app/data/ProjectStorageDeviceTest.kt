package com.virlin.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.virlin.app.data.db.RoomWorkStreamRepository
import com.virlin.app.data.db.VirlinDatabase
import com.virlin.app.data.project.*
import com.virlin.app.domain.model.*
import com.virlin.app.domain.project.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class ProjectStorageDeviceTest {
    @Test fun roomCopyAndVirlinExportKeepOriginalUnchanged() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, VirlinDatabase::class.java).build()
        try {
            val repo = RoomWorkStreamRepository.create(db)
            val at = Instant.EPOCH
            repo.transaction {
                saveProject(Project("source", "Project Ω", createdAt = at, updatedAt = at))
                saveStream(WorkStream("stream", "Workstream", projectId = "source", state = WorkStreamState.READY, createdAt = at, updatedAt = at))
                saveTask(Task("task", "Task 😀", projectId = "source", workStreamId = "stream", notes = "Line\n".repeat(160), createdAt = at, updatedAt = at))
            }
            val source = repo.projectSnapshot("source")
            val tools = ProjectStorageTools(context, repo)
            val copy = tools.duplicate(source, "Copied", ProjectCopyMode.FULL)
            assertNotEquals(source.project.id, copy.id)
            assertEquals(1, repo.projectSnapshot(copy.id).tasks.size)
            assertEquals(source, repo.projectSnapshot("source"))
            val export = tools.exportVirlin(source, VirlinTiming.KEEP)
            try {
                ZipFile(export.file).use { assertNotNull(it.getEntry(VirlinProjectFile.DATA_ENTRY)); assertNotNull(it.getEntry(VirlinProjectFile.HEADER_ENTRY)) }
                assertEquals("source", VirlinProjectFile.verify(export.file).projectId)
            } finally { export.file.delete() }
            assertEquals(source, repo.projectSnapshot("source"))
        } finally { db.close() }
    }
}
