package com.virlin.app.ui.map

import org.json.JSONArray
import org.json.JSONObject

/**
 * The colour settings added with the rebuilt colour studio, shared by the appearance store and
 * the `.vmap` codec. Every field is optional on read, so files and stores written before these
 * settings existed decode to the defaults, which paint exactly as before.
 */
internal fun JSONObject.putColourSettings(a: MapAppearance): JSONObject = apply {
    put("branchMode", a.branchMode.name)
    a.singleBranchColor?.let { put("singleBranchColor", it.toString(16)) }
    put("levelColors", JSONArray().also { array -> a.levelColors.forEach { array.put(it.toString(16)) } })
    put("boxFill", a.boxFill.name)
    a.boxFillColor?.let { put("boxFillColor", it.toString(16)) }
    put("boxBorder", a.boxBorder.name)
    a.boxBorderColor?.let { put("boxBorderColor", it.toString(16)) }
    a.boxTextColor?.let { put("boxTextColor", it.toString(16)) }
    a.backgroundColor?.let { put("backgroundColor", it.toString(16)) }
    put("boxPaletteMode", a.boxPaletteMode.name)
    put("boxStrength", a.boxStrength.name)
    put("boxLevelColors", JSONObject().also { map -> a.boxLevelColors.forEach { (level, c) -> map.put(level.toString(), c.toString(16)) } })
    a.boxSingleColor?.let { put("boxSingleColor", it.toString(16)) }
    put("boxBranchColors", JSONObject().also { map -> a.boxBranchColors.forEach { (id, c) -> map.put(id, c.toString(16)) } })
    put("branchPalette", JSONArray().also { array -> a.branchPalette.forEach { array.put(it.toString(16)) } })
    put("savedPalettes", JSONArray().also { array ->
        a.savedPalettes.forEach { palette ->
            array.put(JSONObject().put("name", palette.name).put("colors",
                JSONArray().also { c -> palette.colors.forEach { c.put(it.toString(16)) } }))
        }
    })
}

internal fun MapAppearance.withColourSettings(o: JSONObject): MapAppearance {
    fun colour(field: String): Long? = o.optString(field).takeIf { it.isNotEmpty() }
        ?.toULongOrNull(16)?.toLong()?.let { it or 0xFF000000L }
    fun colours(array: JSONArray?): List<Long> = array?.let { a ->
        (0 until a.length()).mapNotNull { a.optString(it).toULongOrNull(16)?.toLong()?.let { c -> c or 0xFF000000L } }
    }.orEmpty().take(MAP_PALETTE_MAX)
    return copy(
        branchMode = MapBranchMode.entries.firstOrNull { it.name == o.optString("branchMode") } ?: MapBranchMode.MULTI,
        singleBranchColor = colour("singleBranchColor"),
        levelColors = colours(o.optJSONArray("levelColors")),
        boxFill = MapFillMode.entries.firstOrNull { it.name == o.optString("boxFill") } ?: MapFillMode.SOFT,
        boxFillColor = colour("boxFillColor"),
        boxBorder = MapBorderMode.entries.firstOrNull { it.name == o.optString("boxBorder") } ?: MapBorderMode.AUTO,
        boxBorderColor = colour("boxBorderColor"),
        boxTextColor = colour("boxTextColor"),
        backgroundColor = colour("backgroundColor"),
        boxPaletteMode = MapBoxPaletteMode.entries.firstOrNull { it.name == o.optString("boxPaletteMode") }
            ?: MapBoxPaletteMode.WORKSTREAM,
        boxStrength = MapBoxStrength.entries.firstOrNull { it.name == o.optString("boxStrength") } ?: MapBoxStrength.MEDIUM,
        boxLevelColors = o.optJSONObject("boxLevelColors")?.let { map ->
            buildMap {
                map.keys().forEach { key ->
                    val level = key.toIntOrNull()?.takeIf { it in mapBoxLevelNames.indices } ?: return@forEach
                    map.optString(key).toULongOrNull(16)?.toLong()?.let { put(level, it or 0xFF000000L) }
                }
            }
        }.orEmpty(),
        boxSingleColor = colour("boxSingleColor"),
        boxBranchColors = o.optJSONObject("boxBranchColors")?.let { map ->
            buildMap {
                map.keys().forEach { id ->
                    map.optString(id).toULongOrNull(16)?.toLong()?.let { put(id, it or 0xFF000000L) }
                }
            }
        }.orEmpty(),
        branchPalette = colours(o.optJSONArray("branchPalette")),
        savedPalettes = o.optJSONArray("savedPalettes")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val p = a.optJSONObject(i) ?: return@mapNotNull null
                val cs = colours(p.optJSONArray("colors"))
                if (cs.size < MAP_PALETTE_MIN) null
                else MapSavedPalette(p.optString("name").ifBlank { "My palette" }.take(40), cs)
            }.take(24)
        }.orEmpty(),
    )
}
