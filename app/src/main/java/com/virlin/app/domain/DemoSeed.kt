package com.virlin.app.domain

import androidx.room.withTransaction
import com.virlin.app.data.db.VirlinDatabase
import com.virlin.app.data.db.MetaEntity
import com.virlin.app.data.db.VirlinMappers.toEntity
import com.virlin.app.domain.model.Project
import com.virlin.app.domain.model.Task
import com.virlin.app.domain.model.WorkStream
import com.virlin.app.mock.DomainDisplayBridge
import com.virlin.app.mock.MockData
import java.time.Instant

/**
 * Demo fixtures (display titles from MockData + DemoHierarchySeed's task trees), kept apart
 * from real user data. Applied to the durable database ONCE: only when the database holds
 * no WorkStreams and carries no seed marker. Relaunching never duplicates anything.
 */
object DemoSeed {
    const val MARKER_KEY = "demo_seed"

    class Fixture(val projects: List<Project>, val streams: List<WorkStream>, val tasks: List<Task>)

    fun build(now: Instant): Fixture {
        val streams = DomainDisplayBridge.seedFromDisplay(MockData.streams.value, now)
            .map { ws -> DemoHierarchySeed.activeTasks[ws.id]?.let { ws.copy(activeTaskId = it) } ?: ws }
            .map { ws -> if (ws.id in DemoHierarchySeed.projectless) ws.copy(projectId = null) else ws }
        val projects = MockData.projects.map { Project(id = it.id, title = it.name, createdAt = now, updatedAt = now) }
        return Fixture(projects, streams, DemoHierarchySeed.tasks(streams, now))
    }

    /** @return true if the seed was written in this call. */
    suspend fun applyIfEmpty(db: VirlinDatabase, now: Instant): Boolean {
        if (db.meta().get(MARKER_KEY) != null || db.workStreams().count() > 0) return false
        val f = build(now)
        db.withTransaction {
            f.projects.forEach { db.projects().upsert(it.toEntity()) }
            f.streams.forEach { db.workStreams().upsert(it.toEntity()) }
            f.tasks.forEach { db.tasks().upsert(it.toEntity()) }
            db.meta().put(MetaEntity(MARKER_KEY, now.toString()))
        }
        return true
    }
}
