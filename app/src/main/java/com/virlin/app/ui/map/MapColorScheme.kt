package com.virlin.app.ui.map

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

class MapPresetPalette(val name: String, val colors: List<Long>)

/**
 * Virlin's own presets. The first four keep their historical [MapAppearance.paletteIndex]
 * positions, so maps saved before more presets existed still open in the palette they chose.
 */
val mapPresetPalettes = listOf(
    MapPresetPalette("Virlin fresh", listOf(0xFF24A46B, 0xFF618EE1, 0xFFD97867, 0xFF9A70CD, 0xFFE0A73A, 0xFF48A9AD)),
    MapPresetPalette("Quiet earth", listOf(0xFF70A985, 0xFF698BBD, 0xFFB78FA9, 0xFFD9A57E, 0xFF8D9E76, 0xFF8B80B6)),
    MapPresetPalette("Deep focus", listOf(0xFF238B78, 0xFF6689A8, 0xFFD47882, 0xFFA885B8, 0xFFBB9957, 0xFF658F77)),
    MapPresetPalette("Bright paths", listOf(0xFF78A85D, 0xFF658FCE, 0xFFDF8774, 0xFF846FBD, 0xFFD9B45C, 0xFF52A7A5)),
    MapPresetPalette("Sunset", listOf(0xFFE76F51, 0xFFF4A261, 0xFFE9C46A, 0xFF2A9D8F, 0xFF264653, 0xFFF28482)),
    MapPresetPalette("Ocean", listOf(0xFF0077B6, 0xFF00B4D8, 0xFF023E8A, 0xFF48CAE4, 0xFF0096C7, 0xFF5E60CE)),
    MapPresetPalette("Candy", listOf(0xFFFF6B9A, 0xFFFFB84C, 0xFF5DBB7A, 0xFF5DADEC, 0xFFB388EB, 0xFFFF8C61)),
    MapPresetPalette("Forest", listOf(0xFF2D6A4F, 0xFF52B788, 0xFF40916C, 0xFF74A57F, 0xFF1B4332, 0xFF95B46A)),
    MapPresetPalette("Berry", listOf(0xFF7B2CBF, 0xFFC77DFF, 0xFFF72585, 0xFF9D4EDD, 0xFF5A189A, 0xFFE07BB5)),
    MapPresetPalette("Pastel", listOf(0xFFF4A6A6, 0xFFF7C59F, 0xFFE9D985, 0xFFA8D5BA, 0xFF9AD1D4, 0xFFB8A9E3)),
    MapPresetPalette("Bold primaries", listOf(0xFFE63946, 0xFF1D3557, 0xFF457B9D, 0xFFF1A208, 0xFF2A9D8F, 0xFF8338EC)),
    MapPresetPalette("Autumn", listOf(0xFFBC4749, 0xFFDDA15E, 0xFF606C38, 0xFFBC6C25, 0xFF283618, 0xFFA98467)),
    MapPresetPalette("Ink", listOf(0xFF212529, 0xFF495057, 0xFF6C757D, 0xFF343A40, 0xFF868E96, 0xFFADB5BD)),
)

const val MAP_PALETTE_MIN = 2
const val MAP_PALETTE_MAX = 12

fun MapAppearance.activePalette(): List<Long> =
    customPalette.takeIf { it.size >= 1 }
        ?: mapPresetPalettes[paletteIndex.mod(mapPresetPalettes.size)].colors

/** Named rows of the "By level" editor; the last one paints every level below it too. */
val mapLevelNames = listOf("Primary · workstreams", "Secondary · tasks", "Tertiary · subtasks", "Deeper levels")

/** Colour of the line INTO a node at [depth] (1 = workstreams) when branches are coloured by level. */
fun MapAppearance.levelColor(depth: Int): Long {
    val index = (depth - 1).coerceIn(0, mapLevelNames.lastIndex)
    val palette = activePalette()
    return levelColors.getOrNull(index) ?: palette[index % palette.size]
}

/** The appearance with every colour choice back to Virlin's defaults; layout, sizes and saved colours stay. */
fun MapAppearance.withDefaultColours(): MapAppearance = copy(
    paletteIndex = 0, customPalette = emptyList(), nodeColors = emptyMap(), branchColors = emptyMap(),
    branchMode = MapBranchMode.MULTI, singleBranchColor = null, levelColors = emptyList(),
    boxFill = MapFillMode.SOFT, boxFillColor = null, boxBorder = MapBorderMode.AUTO, boxBorderColor = null,
    boxTextColor = null, backgroundColor = null,
    boxPaletteMode = MapBoxPaletteMode.WORKSTREAM, boxStrength = MapBoxStrength.MEDIUM, boxLevelColors = emptyMap(),
    boxSingleColor = null, boxBranchColors = emptyMap(), branchPalette = emptyList(),
)

fun MapAppearance.singleColor(): Long = singleBranchColor ?: activePalette().first()

/** Colours of multicolour branch lines: their own list, or the palette. */
fun MapAppearance.branchLinePalette(): List<Long> = branchPalette.takeIf { it.isNotEmpty() } ?: activePalette()

/** Named rows of the box "By level" editor; the last one paints every level below it too. */
val mapBoxLevelNames = listOf("Project", "Workstreams", "Tasks", "Subtasks", "Deeper levels")

/** Box colour at [depth] (0 = the project) when boxes are coloured by level. */
fun MapAppearance.boxLevelColor(depth: Int): Long {
    val index = depth.coerceIn(0, mapBoxLevelNames.lastIndex)
    val palette = activePalette()
    return boxLevelColors[index] ?: palette[index % palette.size]
}

fun MapAppearance.boxSingle(): Long = boxSingleColor ?: activePalette().first()

/**
 * Palette colour of every box, before a one-box highlight. A box-branch override paints that box
 * and everything under it; otherwise [MapAppearance.boxPaletteMode] decides. Branch lines are
 * coloured separately by [mapBranchTints].
 */
internal fun mapBoxTints(g: MapGeometry, appearance: MapAppearance,
    branchTints: Map<String, Long> = mapBranchTints(g, appearance)): Map<String, Long> {
    val palette = appearance.activePalette()
    val colors = HashMap<String, Long>(g.topics.size * 2)
    val overridden = HashMap<String, Long>()
    g.topics.forEachIndexed { index, topic ->
        val inheritedOverride = g.parent[topic.id]?.let(overridden::get)
        val override = appearance.boxBranchColors[topic.id] ?: inheritedOverride
        if (override != null) overridden[topic.id] = override
        val depth = g.depth[topic.id] ?: 0
        colors[topic.id] = override ?: when (appearance.boxPaletteMode) {
            MapBoxPaletteMode.WORKSTREAM -> if (depth == 0) appearance.boxLevelColor(0)
                else palette[(g.branch[topic.id] ?: 0).mod(palette.size)]
            MapBoxPaletteMode.LEVEL -> appearance.boxLevelColor(depth)
            MapBoxPaletteMode.SINGLE -> appearance.boxSingle()
            MapBoxPaletteMode.EVERY -> palette[index.mod(palette.size)]
            MapBoxPaletteMode.BRANCHES -> branchTints.getValue(topic.id)
        }
    }
    return colors
}

/**
 * Connector colour per node. A branch override paints that node and everything under it; without
 * one the [MapAppearance.branchMode] decides: each top-level branch its own palette colour, one
 * shared colour, or one colour per depth.
 */
internal fun mapBranchTints(g: MapGeometry, appearance: MapAppearance): Map<String, Long> {
    val palette = appearance.branchLinePalette()
    val colors = HashMap<String, Long>(g.topics.size * 2)
    val overridden = HashMap<String, Long>()
    for (topic in g.topics) {
        val inheritedOverride = g.parent[topic.id]?.let(overridden::get)
        val override = appearance.branchColors[topic.id] ?: inheritedOverride
        if (override != null) overridden[topic.id] = override
        val depth = g.depth[topic.id] ?: 0
        colors[topic.id] = override ?: when (appearance.branchMode) {
            MapBranchMode.MULTI -> palette[(g.branch[topic.id] ?: 0).mod(palette.size)]
            MapBranchMode.SINGLE -> appearance.singleColor()
            MapBranchMode.LEVELS -> appearance.levelColor(max(depth, 1))
        }
    }
    return colors
}

/** Everything one box needs to paint itself, derived once from the appearance. */
internal class MapBoxColors(
    /** Three gradient stops, top-left to bottom-right. */
    val fill: IntArray,
    /** Hand-drawn paper; null keeps the finish's own paper (tinted by a box override). */
    val handFill: Int?,
    /** Null means no edge is drawn. */
    val stroke: Int?,
    val strokeAlpha: Int,
    /** Ink for hand-drawn edges; null keeps the finish's own ink. */
    val handStroke: Int?,
    val title: Int,
    /** Null keeps the status colours; a dark fill swaps them for a readable light ink. */
    val detail: Int?,
)

private const val WHITE = 0xFFFFFFFF.toInt()
private const val INK = 0xFF192229.toInt()

internal fun boxColors(
    appearance: MapAppearance,
    tint: Int,
    customBox: Boolean,
    selected: Boolean,
    dimmed: Boolean,
    handDrawn: Boolean,
    paper: Int = 0xFFEFEFED.toInt(),
): MapBoxColors {
    // Light is the original faint tint; a one-box highlight always reads stronger than the palette.
    val (soft1, soft2) = when (appearance.boxStrength) {
        MapBoxStrength.LIGHT -> .05f to .14f
        MapBoxStrength.MEDIUM -> .15f to .27f
        MapBoxStrength.STRONG -> .30f to .46f
    }
    val softFill = intArrayOf(
        if (appearance.boxStrength == MapBoxStrength.LIGHT) 0xFFFDFFFC.toInt() else blendArgb(WHITE, tint, soft1 * .5f),
        blendArgb(WHITE, tint, if (customBox) maxOf(.32f, soft1 + .14f) else if (selected) soft1 + .07f else soft1),
        blendArgb(WHITE, tint, if (customBox) maxOf(.48f, soft2 + .14f) else if (selected) soft2 + .11f else soft2),
    )
    // A box with its own colour always shows it, whatever the map-wide fill says.
    val mode = if (customBox && (appearance.boxFill == MapFillMode.WHITE || appearance.boxFill == MapFillMode.CUSTOM))
        MapFillMode.SOFT else appearance.boxFill
    val fill = when (mode) {
        MapFillMode.SOFT -> softFill
        MapFillMode.PASTEL -> intArrayOf(blendArgb(WHITE, tint, .16f),
            blendArgb(WHITE, tint, if (selected) .34f else .26f), blendArgb(WHITE, tint, .36f))
        MapFillMode.BOLD -> intArrayOf(blendArgb(tint, WHITE, .12f), tint, blendArgb(tint, 0xFF000000.toInt(), .12f))
        MapFillMode.WHITE -> intArrayOf(WHITE, WHITE, if (selected) blendArgb(WHITE, tint, .10f) else WHITE)
        MapFillMode.CUSTOM -> (appearance.boxFillColor?.toInt() ?: WHITE).let { c ->
            intArrayOf(blendArgb(c, WHITE, .14f), c, blendArgb(c, 0xFF000000.toInt(), .06f))
        }
    }
    // Soft hand-drawn boxes keep their paper, tinted by the palette unless strength is Light.
    val handFill = when {
        mode != MapFillMode.SOFT -> fill[1]
        customBox || appearance.boxStrength == MapBoxStrength.LIGHT -> null
        else -> blendArgb(paper, tint, if (appearance.boxStrength == MapBoxStrength.STRONG) .36f else .2f)
    }
    val strokeColor: Int? = when (appearance.boxBorder) {
        MapBorderMode.AUTO, MapBorderMode.BRANCH -> tint
        MapBorderMode.CUSTOM -> appearance.boxBorderColor?.toInt() ?: tint
        MapBorderMode.NONE -> if (selected) tint else null
    }
    val strokeAlpha = if (appearance.boxBorder == MapBorderMode.AUTO) {
        if (selected || customBox) 255 else if (dimmed) 55 else 130
    } else if (dimmed && !selected) 55 else 255
    val handStroke = when (appearance.boxBorder) {
        MapBorderMode.AUTO -> if (customBox) tint else null
        else -> strokeColor
    }
    val surface = if (handDrawn) handFill ?: (if (customBox) blendArgb(paper, tint, .42f) else paper) else fill[1]
    val dark = luminance(surface) < .42
    val title = appearance.boxTextColor?.toInt() ?: if (dark) 0xFFFAFAFA.toInt() else INK
    val detail = when {
        appearance.boxTextColor != null -> blendArgb(appearance.boxTextColor.toInt(), surface, .25f)
        dark -> 0xE6FFFFFF.toInt()
        else -> null
    }
    return MapBoxColors(fill, handFill, strokeColor, strokeAlpha, handStroke, title, detail)
}

// ---------------------------------------------------------------- colour maths (no Android types)

internal fun blendArgb(a: Int, b: Int, ratio: Float): Int {
    val t = ratio.coerceIn(0f, 1f)
    fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).roundToInt()
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/** WCAG relative luminance, 0 (black) .. 1 (white). */
fun luminance(argb: Int): Double {
    fun lin(c: Int): Double = (c / 255.0).let { if (it <= .03928) it / 12.92 else ((it + .055) / 1.055).pow(2.4) }
    return .2126 * lin((argb shr 16) and 0xFF) + .7152 * lin((argb shr 8) and 0xFF) + .0722 * lin(argb and 0xFF)
}

/** HSV with hue 0..360 and saturation/value 0..1, as an opaque ARGB long. */
fun hsvToArgb(hue: Float, saturation: Float, value: Float): Long {
    val h = ((hue % 360f) + 360f) % 360f
    val s = saturation.coerceIn(0f, 1f)
    val v = value.coerceIn(0f, 1f)
    val c = v * s
    val x = c * (1 - abs((h / 60f) % 2 - 1))
    val m = v - c
    val (r, g, b) = when {
        h < 60 -> Triple(c, x, 0f)
        h < 120 -> Triple(x, c, 0f)
        h < 180 -> Triple(0f, c, x)
        h < 240 -> Triple(0f, x, c)
        h < 300 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    fun ch(f: Float) = ((f + m) * 255f).roundToInt().coerceIn(0, 255).toLong()
    return 0xFF000000L or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
}

fun argbToHsv(argb: Long): FloatArray {
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val d = maxC - minC
    val h = when {
        d == 0f -> 0f
        maxC == r -> 60f * (((g - b) / d) % 6f)
        maxC == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }.let { (it + 360f) % 360f }
    return floatArrayOf(h, if (maxC == 0f) 0f else d / maxC, maxC)
}

fun hexOf(argb: Long): String = "%06X".format(argb and 0xFFFFFF)

/** "#2a9d8f", "2A9D8F" or "#abc" → opaque ARGB; anything else → null. */
fun parseHexColor(text: String): Long? {
    val raw = text.trim().removePrefix("#")
    val full = when {
        raw.length == 3 && raw.all { it.isHex() } -> raw.map { "$it$it" }.joinToString("")
        raw.length == 6 && raw.all { it.isHex() } -> raw
        else -> return null
    }
    return 0xFF000000L or full.toLong(16)
}

private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/**
 * Colours pasted from anywhere: a list of hex codes, a Coolors link
 * (`coolors.co/264653-2a9d8f-...`), a Color Hunt link (`colorhunt.co/palette/f9ed69f08a5d...`)
 * or CSS. Runs of hex digits whose length is a multiple of six are split into colours. Nothing
 * is fetched from the network; only the text itself is read.
 */
fun parsePaletteText(text: String): List<Long> {
    val out = LinkedHashSet<Long>()
    Regex("(?<![0-9A-Za-z])#?([0-9A-Fa-f]{6,72})(?![0-9A-Za-z])").findAll(text).forEach { match ->
        val run = match.groupValues[1]
        if (run.length % 6 == 0) run.chunked(6).forEach { out += 0xFF000000L or it.toLong(16) }
    }
    Regex("#([0-9A-Fa-f]{3})(?![0-9A-Za-z])").findAll(text).forEach { match ->
        parseHexColor(match.groupValues[1])?.let(out::add)
    }
    return out.take(MAP_PALETTE_MAX)
}

enum class MapHarmony(val label: String) {
    ANALOGOUS("Analogous"), COMPLEMENTARY("Complementary"), TRIADIC("Triadic"),
    MONOCHROME("Monochrome"), PASTEL("Pastel"), RAINBOW("Rainbow")
}

/** Six colours built around [base]. Deterministic, so the same base always gives the same palette. */
fun harmonyPalette(base: Long, harmony: MapHarmony): List<Long> {
    val (h, s, v) = argbToHsv(base).let { Triple(it[0], it[1].coerceAtLeast(.35f), it[2].coerceIn(.45f, .95f)) }
    return when (harmony) {
        MapHarmony.ANALOGOUS -> listOf(-40f, -20f, 0f, 20f, 40f, 60f).map { hsvToArgb(h + it, s, v) }
        MapHarmony.COMPLEMENTARY -> listOf(
            hsvToArgb(h, s, v), hsvToArgb(h + 180f, s, v), hsvToArgb(h, s * .55f, min(1f, v + .1f)),
            hsvToArgb(h + 180f, s * .55f, min(1f, v + .1f)), hsvToArgb(h, s, v * .7f), hsvToArgb(h + 180f, s, v * .7f))
        MapHarmony.TRIADIC -> listOf(0f, 120f, 240f).flatMap {
            listOf(hsvToArgb(h + it, s, v), hsvToArgb(h + it, s * .6f, min(1f, v + .08f)))
        }
        MapHarmony.MONOCHROME -> listOf(1f, .85f, .7f, .55f, .4f, .28f).mapIndexed { i, f ->
            hsvToArgb(h, (s * (0.55f + i * .09f)).coerceAtMost(1f), (v * f + .25f * (1 - f)).coerceIn(.2f, 1f))
        }
        MapHarmony.PASTEL -> (0 until 6).map { hsvToArgb(h + it * 60f, .32f, .95f) }
        MapHarmony.RAINBOW -> (0 until 6).map { hsvToArgb(h + it * 60f, s, v) }
    }
}
