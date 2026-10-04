package com.virlin.app.now

import com.virlin.app.ui.screens.compactFocusHierarchyLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class FocusHierarchyLabelTest {
    @Test fun multipleWords_useOnlyFirstWord() {
        assertEquals("Claude", compactFocusHierarchyLabel(" Claude · Virlin "))
        assertEquals("Create", compactFocusHierarchyLabel("Create\nMode"))
        assertEquals("51", compactFocusHierarchyLabel("51"))
        assertEquals("", compactFocusHierarchyLabel("  "))
    }

    @Test fun longWords_areBoundedWithoutChangingOriginalName() {
        val name = "Extraordinarily long task"
        assertEquals("Extraordi…", compactFocusHierarchyLabel(name))
        assertEquals("Extraordinarily long task", name)
    }

    @Test fun tenLevels_preserveOrderAndCount() {
        val names = (1..10).map { "Level$it full name" }
        assertEquals((1..10).map { "Level$it" }, names.map(::compactFocusHierarchyLabel))
    }
}
