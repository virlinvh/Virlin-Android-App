package com.virlin.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Biotech
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.BusinessCenter
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DesignServices
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.virlin.app.domain.model.ProjectIconCatalog

/** Presentation of one built-in icon: a Material vector on a fixed, low-saturation surface. */
data class BuiltInIconLook(val id: String, val label: String, val icon: ImageVector, val surface: Color, val symbol: Color)

/**
 * Deterministic visual treatment for every `ProjectIconCatalog` id. Same id → same look on every
 * launch. Colours stay in Virlin's restrained family (pale surface, deep symbol); resource ids are
 * never persisted — only the semantic id is.
 */
object BuiltInProjectIcons {

    private val looks: Map<String, BuiltInIconLook> = listOf(
        look("code", Icons.Rounded.Code, 0xFFE3F5EC, 0xFF0E6B45),               // dark green / pale mint
        look("terminal", Icons.Rounded.Terminal, 0xFFE8EEF2, 0xFF1F3A4C),
        look("laptop", Icons.Rounded.Laptop, 0xFFE9EEF6, 0xFF2F4A7A),
        look("mobile", Icons.Rounded.PhoneAndroid, 0xFFE6F3F1, 0xFF1D6B62),
        look("web", Icons.Rounded.Language, 0xFFE6F0FA, 0xFF2456A6),
        look("ai", Icons.Rounded.AutoAwesome, 0xFFE2F5F0, 0xFF0F7A6A),           // emerald / teal on mint
        look("brain", Icons.Rounded.Psychology, 0xFFE7EDFB, 0xFF2D3F9E),         // deep blue / pale blue
        look("research", Icons.Rounded.Science, 0xFFEEE9FA, 0xFF5B3FA6),         // violet / lavender
        look("book", Icons.Rounded.MenuBook, 0xFFF6EFE4, 0xFF7A4F1D),
        look("education", Icons.Rounded.School, 0xFFFBF1DE, 0xFF9A6410),         // warm amber / cream
        look("writing", Icons.Rounded.Edit, 0xFFF3F1EA, 0xFF4E4A3A),
        look("design", Icons.Rounded.DesignServices, 0xFFFDEBE6, 0xFFB94A32),    // coral / pale coral
        look("palette", Icons.Rounded.Palette, 0xFFFBE9EF, 0xFFA23A66),
        look("analytics", Icons.Rounded.BarChart, 0xFFE7F1F7, 0xFF1F5F8A),
        look("database", Icons.Rounded.Storage, 0xFFECEEF4, 0xFF3D4A6B),
        look("cloud", Icons.Rounded.Cloud, 0xFFE8F2FA, 0xFF2E6A9E),
        look("automation", Icons.Rounded.Bolt, 0xFFFBF3DF, 0xFF9C6A0A),
        look("rocket", Icons.Rounded.RocketLaunch, 0xFFFDEEE6, 0xFFB5501F),
        look("business", Icons.Rounded.BusinessCenter, 0xFFEEF0EA, 0xFF3F4A2E),
        look("target", Icons.Rounded.TrackChanges, 0xFFFCEAEA, 0xFFA83A3A),
        look("lab", Icons.Rounded.Biotech, 0xFFE9F6F1, 0xFF1F6F55),
        look("folder", Icons.Rounded.Folder, 0xFFF7F0E1, 0xFF8A6A25),
        look("tools", Icons.Rounded.Build, 0xFFEDEFF1, 0xFF3E4A55),
        look("idea", Icons.Rounded.Lightbulb, 0xFFFDF6DC, 0xFFA27A0C)
    ).associateBy { it.id }

    private fun look(id: String, icon: ImageVector, surface: Long, symbol: Long) =
        BuiltInIconLook(id, ProjectIconCatalog.label(id) ?: id, icon, Color(surface), Color(symbol))

    /** Look for a known id, or null (callers fall through to initials). */
    fun lookOf(id: String?): BuiltInIconLook? = id?.let { looks[it] }

    /** All looks in catalog order (for the editor grid). */
    val all: List<BuiltInIconLook> get() = ProjectIconCatalog.ids.mapNotNull { looks[it] }
}
