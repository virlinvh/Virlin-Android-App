package com.virlin.app.hierarchy

import androidx.test.platform.app.InstrumentationRegistry
import com.virlin.app.domain.VirlinGraph
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Takes the labelled Phase 08 test set back out. Run this class on its own when the test data
 * has served its purpose:
 *
 *   adb shell am instrument -w -e class \
 *     com.virlin.app.hierarchy.Phase08ActivityRemovalTest \
 *     com.virlin.app.test/androidx.test.runner.AndroidJUnitRunner
 *
 * It is not part of the end-to-end run, so a normal test pass never deletes the set it just made.
 */
class Phase08ActivityRemovalTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun removesEveryLabelledRecord() = runBlocking {
        VirlinGraph.init(context)
        VirlinGraph.ensureReady()
        Phase08ActivitySeed.remove(context)
        // The repository caches what it read at startup, so this asserts against storage.
        val db = com.virlin.app.data.db.VirlinDatabase.open(context)
        val remaining = db.captures().all().count { it.id.startsWith(Phase08ActivitySeed.PREFIX) }
        assertTrue("captures left behind: $remaining", remaining == 0)
        val tasks = db.tasks().all().count { it.id.startsWith(Phase08ActivitySeed.PREFIX) }
        assertTrue("tasks left behind: $tasks", tasks == 0)
    }
}
