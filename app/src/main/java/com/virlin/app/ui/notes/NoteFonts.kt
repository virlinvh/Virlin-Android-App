package com.virlin.app.ui.notes

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import com.virlin.app.R

internal enum class NoteFontCategory(val label: String) {
    BASIC("Basic"),
    HANDWRITING("Handwriting"),
    DISPLAY("Creative"),
    SERIF("Serif"),
    SANS("Sans serif"),
    MONO("Monospace"),
}

internal data class NoteFont(val name: String, val category: NoteFontCategory, val caption: String = category.label)

/**
 * The note font list. Basic entries are the three names notes have always stored and keep their
 * original rendering. Every other entry is a Google Fonts family, downloaded and cached by Google
 * Play services on first use; until it arrives (or offline, or without Play services) the text
 * draws in the default font, and the stored name is never changed.
 */
internal object NoteFonts {
    val basic = listOf(
        NoteFont("Inter", NoteFontCategory.BASIC, "Default"),
        NoteFont("Serif", NoteFontCategory.BASIC, "System serif"),
        NoteFont("Mono", NoteFontCategory.BASIC, "System mono"),
    )

    val catalog: List<NoteFont> by lazy {
        listOf(
            NoteFontCatalog.HANDWRITING to NoteFontCategory.HANDWRITING,
            NoteFontCatalog.DISPLAY to NoteFontCategory.DISPLAY,
            NoteFontCatalog.SERIF to NoteFontCategory.SERIF,
            NoteFontCatalog.SANS to NoteFontCategory.SANS,
            NoteFontCatalog.MONO to NoteFontCategory.MONO,
        ).flatMap { (names, category) -> names.split('|').map { NoteFont(it, category) } }
    }

    private val byName: Map<String, NoteFont> by lazy { catalog.associateBy { it.name } }

    val featured: List<Pair<String, List<NoteFont>>> by lazy {
        listOf("Basic" to basic) + listOf(
            "Handwritten" to NoteFontCatalog.FEATURED_HANDWRITTEN,
            "Script & calligraphy" to NoteFontCatalog.FEATURED_SCRIPT_CALLIGRAPHY,
            "Creative & display" to NoteFontCatalog.FEATURED_CREATIVE_DISPLAY,
            "Formal serif" to NoteFontCatalog.FEATURED_FORMAL_SERIF,
            "Clean sans" to NoteFontCatalog.FEATURED_CLEAN_SANS,
            "Monospace" to NoteFontCatalog.FEATURED_MONOSPACE,
        ).map { (title, names) -> title to names.split('|').mapNotNull { byName[it] } }
    }

    val totalCount: Int get() = basic.size + catalog.size

    fun inCategory(category: NoteFontCategory): List<NoteFont> =
        if (category == NoteFontCategory.BASIC) basic else catalog.filter { it.category == category }

    fun search(query: String): List<NoteFont> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val all = basic + catalog
        val starts = all.filter { it.name.startsWith(q, ignoreCase = true) }
        val contains = all.filter { !it.name.startsWith(q, ignoreCase = true) && it.name.contains(q, ignoreCase = true) }
        return starts + contains
    }

    private val provider by lazy {
        GoogleFont.Provider(
            providerAuthority = "com.google.android.gms.fonts",
            providerPackage = "com.google.android.gms",
            certificates = R.array.com_google_android_gms_fonts_certs,
        )
    }
    private val families = HashMap<String, FontFamily>()

    /** Null means "no override": the surrounding text style decides. */
    fun familyFor(name: String?): FontFamily? = when (name) {
        null -> null
        "Inter" -> FontFamily.SansSerif
        "Serif" -> FontFamily.Serif
        "Mono" -> FontFamily.Monospace
        else -> if (name !in byName) null else synchronized(families) {
            families.getOrPut(name) { FontFamily(Font(googleFont = GoogleFont(name), fontProvider = provider)) }
        }
    }
}
