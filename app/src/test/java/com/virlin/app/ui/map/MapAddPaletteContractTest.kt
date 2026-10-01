package com.virlin.app.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapAddPaletteContractTest {
    @Test
    fun paletteContainsOnlyCurrentProductFeatures() {
        assertEquals(
            listOf("TODO", "NOTE", "TASK", "LINK", "PDF", "ATTACHMENT", "IMAGE", "AUDIO"),
            MapAddKind.entries.map { it.name },
        )
        assertTrue(MapAddKind.entries.all { it.isSupported })
    }
}
