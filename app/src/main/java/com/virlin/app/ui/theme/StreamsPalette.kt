package com.virlin.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp

/**
 * The supplied Virlin palette (light / dark pair per hue). Raw tokens live ONLY here; screens
 * use the semantic [StreamsSectionLook]s below, never these values directly.
 */
object VirlinPalette {
    val Grapefruit = Color(0xFFED5565); val GrapefruitDark = Color(0xFFDA4453)
    val Bittersweet = Color(0xFFFC6E51); val BittersweetDark = Color(0xFFE9573F)
    val Sunflower = Color(0xFFFFCE54); val SunflowerDark = Color(0xFFF6BB42)
    val Grass = Color(0xFFA0D468); val GrassDark = Color(0xFF8CC152)
    val Mint = Color(0xFF48CFAD); val MintDark = Color(0xFF37BC9B)
    val Aqua = Color(0xFF4FC1E9); val AquaDark = Color(0xFF3BAFDA)
    val BlueJeans = Color(0xFF5D9CEC); val BlueJeansDark = Color(0xFF4A89DC)
    val Lavender = Color(0xFFAC92EC); val LavenderDark = Color(0xFF967ADC)
    val PinkRose = Color(0xFFEC87C0); val PinkRoseDark = Color(0xFFD770AD)
    val LightGray = Color(0xFFF5F7FA); val LightGrayDark = Color(0xFFE6E9ED)
}

/**
 * Semantic colour family of ONE Streams section. Every value is derived deterministically from
 * the section's palette pair so all cards in a section share one visual family:
 *
 * - [accent]        the marker / leading edge / icon tint (palette light)
 * - [surface]       very pale tint for icon containers and active filter chips
 * - [border]        1dp hairline for cards and chips
 * - [badgeSurface]  count badge fill; [badgeForeground] its text (always dark → readable)
 * - [ink]           status text on white (palette dark pulled towards Charcoal for contrast)
 */
data class StreamsSectionLook(
    val accent: Color,
    val accentDark: Color,
    val surface: Color,
    val border: Color,
    val badgeSurface: Color,
    val badgeForeground: Color,
    val ink: Color
) {
    companion object {
        fun of(light: Color, dark: Color, neutral: Boolean = false): StreamsSectionLook = StreamsSectionLook(
            accent = if (neutral) dark else light,
            accentDark = dark,
            surface = if (neutral) light else light.copy(alpha = 0.16f).compositeOver(Color.White),
            border = if (neutral) dark else light.copy(alpha = 0.55f).compositeOver(Color.White),
            badgeSurface = if (neutral) dark else light.copy(alpha = 0.30f).compositeOver(Color.White),
            badgeForeground = Charcoal,
            ink = if (neutral) CharcoalMuted else lerp(dark, Charcoal, 0.38f)
        )
    }
}

/**
 * Streams section identity → palette mapping. Colour communicates the SECTION / STATE of work,
 * never an individual project. Projects are containers, so they stay neutral; project identity
 * comes from the project icon alone.
 */
object StreamsSectionColors {
    val Projects = StreamsSectionLook.of(VirlinPalette.LightGray, VirlinPalette.LightGrayDark, neutral = true)
    val Focus = StreamsSectionLook.of(VirlinPalette.Mint, VirlinPalette.MintDark)
    val NeedsYou = StreamsSectionLook.of(VirlinPalette.Sunflower, VirlinPalette.SunflowerDark)
    val Processing = StreamsSectionLook.of(VirlinPalette.Aqua, VirlinPalette.BlueJeansDark)
    val Ready = StreamsSectionLook.of(VirlinPalette.Grass, VirlinPalette.GrassDark)
    val Snoozed = StreamsSectionLook.of(VirlinPalette.Lavender, VirlinPalette.LavenderDark)
    val Blocked = StreamsSectionLook.of(VirlinPalette.Bittersweet, VirlinPalette.BittersweetDark)
    /** Restrained Mint: closure, not celebration. */
    val Completed = StreamsSectionLook.of(VirlinPalette.Mint, VirlinPalette.MintDark).let {
        it.copy(accent = it.accent.copy(alpha = 0.7f).compositeOver(Color.White), border = VirlinPalette.LightGrayDark)
    }
}
