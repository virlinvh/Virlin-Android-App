package com.virlin.app.notes

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.virlin.app.data.flashcard.FlashcardColorMode
import com.virlin.app.data.flashcard.FlashcardCorners
import com.virlin.app.data.flashcard.FlashcardDeckStyle
import com.virlin.app.data.flashcard.FlashcardDeckStyleStore
import com.virlin.app.data.flashcard.FlashcardDesign
import com.virlin.app.data.flashcard.FlashcardTypeface
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FlashcardDeckStyleStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun eachBranchKeepsItsOwnDesign() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val storage = folder.newFolder("styles")
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = storage
        }
        val biology = FlashcardDeckStyleStore.deckKey("task", "bio")
        val physics = FlashcardDeckStyleStore.deckKey("task", "phys")
        assertEquals(FlashcardDeckStyle(), FlashcardDeckStyleStore.get(context, biology))
        val chalk = FlashcardDeckStyle(design = FlashcardDesign.CHALKBOARD, colorMode = FlashcardColorMode.GRADIENT,
            typeface = FlashcardTypeface.HANDWRITTEN, corners = FlashcardCorners.SQUARE, scattered = true,
            showDetails = false, centered = true)
        FlashcardDeckStyleStore.save(context, biology, chalk)
        FlashcardDeckStyleStore.save(context, physics, FlashcardDeckStyle(design = FlashcardDesign.INDEX))
        assertEquals(chalk, FlashcardDeckStyleStore.get(context, biology))
        assertEquals(FlashcardDesign.INDEX, FlashcardDeckStyleStore.get(context, physics).design)
        assertEquals(FlashcardDeckStyle(), FlashcardDeckStyleStore.get(context, "project:other"))

        FlashcardDeckStyleStore.save(context, biology, FlashcardDeckStyle())
        val decks = JSONObject(File(storage, "flashcard-deck-styles-v1.json").readText()).getJSONObject("decks")
        assertFalse(decks.has(biology))
        assertEquals(FlashcardDesign.INDEX, FlashcardDeckStyleStore.get(context, physics).design)
    }

    @Test fun unknownSettingsFallBackOneByOne() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val storage = folder.newFolder("future")
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = storage
        }
        File(storage, "flashcard-deck-styles-v1.json").writeText(
            """{"v":1,"decks":{"task:a":{"design":"HOLOGRAM","typeface":"MARKER","scattered":true}}}""")
        val style = FlashcardDeckStyleStore.get(context, "task:a")
        assertEquals(FlashcardDesign.CLASSIC, style.design)
        assertEquals(FlashcardTypeface.MARKER, style.typeface)
        assertEquals(true, style.scattered)
    }
}
