package com.virlin.app.domain

import com.virlin.app.domain.model.WorkStream
import com.virlin.app.domain.model.WorkStreamState
import com.virlin.app.domain.repository.BootstrappingWorkStreamRepository
import com.virlin.app.domain.repository.InMemoryWorkStreamRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/**
 * Pre-hydration contract: collectors may subscribe before Room bind; bind is once.
 */
class BootstrappingRepositoryTest {

    private val now = Instant.parse("2026-09-18T10:00:00Z")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private fun seededInner(): InMemoryWorkStreamRepository {
        val ws = WorkStream(
            id = "s1",
            title = "Persisted",
            projectId = null,
            state = WorkStreamState.READY,
            createdAt = now,
            updatedAt = now
        )
        return InMemoryWorkStreamRepository(seed = listOf(ws))
    }

    @Test
    fun observables_safe_before_bind_are_empty() = runTest {
        val boot = BootstrappingWorkStreamRepository()
        assertFalse(boot.isBound)
        assertTrue(boot.streams.value.isEmpty())
        assertTrue(boot.projects.value.isEmpty())
        assertTrue(boot.tasks.value.isEmpty())
        assertTrue(boot.captures.value.isEmpty())
        assertTrue(boot.getFocusSessions("s1").isEmpty())
        assertEquals(null, boot.getStream("s1"))
    }

    @Test
    fun bind_publishes_persisted_into_same_flows() = runTest {
        val boot = BootstrappingWorkStreamRepository()
        val inner = seededInner()
        boot.bind(inner, scope)
        assertTrue(boot.isBound)
        assertEquals(1, boot.streams.value.size)
        assertEquals("Persisted", boot.streams.value.single().title)
    }

    @Test
    fun concurrent_bind_attaches_once() = runTest {
        val boot = BootstrappingWorkStreamRepository()
        val a = seededInner()
        val b = InMemoryWorkStreamRepository(
            seed = listOf(
                WorkStream(
                    id = "s2",
                    title = "Other",
                    projectId = null,
                    state = WorkStreamState.READY,
                    createdAt = now,
                    updatedAt = now
                )
            )
        )
        listOf(
            async { boot.bind(a, scope) },
            async { boot.bind(b, scope) },
            async { boot.bind(a, scope) },
        ).awaitAll()
        assertEquals(1, boot.streams.value.size)
        assertEquals("s1", boot.streams.value.single().id)
    }

    @Test
    fun writes_before_bind_fail_loudly() = runTest {
        val boot = BootstrappingWorkStreamRepository()
        try {
            boot.transaction { }
            fail("expected not-ready error")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("ensureReady"))
        }
    }
}
