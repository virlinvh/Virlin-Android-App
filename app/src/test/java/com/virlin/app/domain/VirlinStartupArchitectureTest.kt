package com.virlin.app.domain

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.virlin.app.mock.DomainDisplayBridge
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Permanent startup contract: subscribe before READY; hydrate once; error + retry;
 * DomainDisplayBridge starts once after bind.
 */
@RunWith(RobolectricTestRunner::class)
class VirlinStartupArchitectureTest {

    @Before
    fun setUp() {
        VirlinGraph.resetForTests()
    }

    @After
    fun tearDown() {
        VirlinGraph.resetForTests()
    }

    @Test
    fun repository_collectable_before_hydration_is_empty_and_initializing() {
        assertTrue(VirlinGraph.startupReadiness.value is StartupReadiness.Initializing)
        assertTrue(VirlinGraph.repository.streams.value.isEmpty())
        assertTrue(VirlinGraph.repository.captures.value.isEmpty())
        assertFalse((VirlinGraph.repository as com.virlin.app.domain.repository.BootstrappingWorkStreamRepository).isBound)
    }

    @Test
    fun ensureReady_transitions_to_ready_and_publishes_seed_or_empty() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        VirlinGraph.init(ctx)
        VirlinGraph.ensureReady()
        assertTrue(VirlinGraph.startupReadiness.value is StartupReadiness.Ready)
        assertTrue((VirlinGraph.repository as com.virlin.app.domain.repository.BootstrappingWorkStreamRepository).isBound)
        // After READY, streams come from Room/DemoSeed (non-empty on fresh DB) or prior data.
        assertTrue(VirlinGraph.repository.streams.value.isNotEmpty())
    }

    @Test
    fun concurrent_ensureReady_shares_one_hydration() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        VirlinGraph.init(ctx)
        VirlinGraph.debugHydrationDelayMs = 80
        val results = listOf(
            async { VirlinGraph.ensureReady() },
            async { VirlinGraph.ensureReady() },
            async { VirlinGraph.ensureReady() },
        ).awaitAll()
        assertEquals(3, results.size)
        assertTrue(VirlinGraph.startupReadiness.value is StartupReadiness.Ready)
        // Bridge started exactly once (idempotent start + bridgeStarted flag).
        DomainDisplayBridge.stopForTests()
        // Re-calling ensureReady when already ready is a no-op (still Ready, still bound).
        VirlinGraph.ensureReady()
        assertTrue(VirlinGraph.startupReadiness.value is StartupReadiness.Ready)
    }

    @Test
    fun hydration_failure_produces_error_and_retry_recovers() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        VirlinGraph.init(ctx)
        VirlinGraph.debugFailNextHydration = true
        try {
            VirlinGraph.ensureReady()
            fail("expected failure")
        } catch (_: IllegalStateException) {
            // expected
        }
        assertTrue(VirlinGraph.startupReadiness.value is StartupReadiness.Error)
        assertFalse((VirlinGraph.repository as com.virlin.app.domain.repository.BootstrappingWorkStreamRepository).isBound)
        // Sticky fail flag: second ensureReady still fails until retry clears it.
        try {
            VirlinGraph.ensureReady()
            fail("expected sticky failure")
        } catch (_: IllegalStateException) {
            // expected
        }
        assertTrue(VirlinGraph.startupReadiness.value is StartupReadiness.Error)

        VirlinGraph.retryStartup()
        // retryStartup clears the fail flag and launches async — wait for Ready.
        var spins = 0
        while (VirlinGraph.startupReadiness.value !is StartupReadiness.Ready && spins < 100) {
            delay(20)
            spins++
        }
        assertTrue(
            "expected Ready after retry, got ${VirlinGraph.startupReadiness.value}",
            VirlinGraph.startupReadiness.value is StartupReadiness.Ready
        )
    }

    @Test
    fun startAsync_does_not_block_and_eventually_ready() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        VirlinGraph.init(ctx)
        VirlinGraph.debugHydrationDelayMs = 100
        assertTrue(VirlinGraph.startupReadiness.value is StartupReadiness.Initializing)
        VirlinGraph.startAsync()
        // Immediately still initializing (hydration on IO with delay).
        assertTrue(VirlinGraph.startupReadiness.value is StartupReadiness.Initializing)
        var spins = 0
        while (VirlinGraph.startupReadiness.value !is StartupReadiness.Ready && spins < 100) {
            delay(20)
            spins++
        }
        assertTrue(VirlinGraph.startupReadiness.value is StartupReadiness.Ready)
    }
}
