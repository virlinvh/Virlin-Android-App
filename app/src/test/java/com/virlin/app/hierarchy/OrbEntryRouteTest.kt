package com.virlin.app.hierarchy

import com.virlin.app.ui.navigation.isOrbEntryRoute
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbEntryRouteTest {
    @Test fun onlyApprovedPagesExposeOrb() {
        listOf("now", "streams", "project_detail/p", "project_task_index/p",
            "workstream_detail/w", "task_detail/t").forEach { assertTrue(it, isOrbEntryRoute(it)) }
        listOf("pulse", "inbox", "project_settings/p", "project_map/p",
            "knowledge_todo/c", "capture_note/c", "apps_mind_maps", "pulse_project/p",
            "future_page", "project_detail_search/p").forEach { assertFalse(it, isOrbEntryRoute(it)) }
    }
}
