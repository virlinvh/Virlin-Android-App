package com.virlin.app.ui.map

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where the map's APPEARANCE lives — layout, palette and colour overrides, per project.
 *
 * This is deliberately not in Room: none of it is work, and the map itself is only a
 * projection of the live records, which stay the single source of truth. It is a small JSON
 * file in the app's own storage, so no schema migration and no new dependency are involved.
 * Overrides are keyed by stable entity ids.
 */
class MapAppearanceStore(context: Context) {

    private val file = File(context.filesDir, "map_appearance.json")
    private val scope = CoroutineScope(Dispatchers.IO)
    private val mutex = Mutex()
    private val removedProjects = mutableSetOf<String>()
    private var loaded = false
    private val cache = MutableStateFlow<Map<String, MapAppearance>>(emptyMap())
    val all: StateFlow<Map<String, MapAppearance>> = cache

    init {
        scope.launch { mutex.withLock { if (!loaded) { cache.value = read(); loaded = true } } }
    }

    fun appearanceOf(projectId: String): MapAppearance =
        cache.value[projectId] ?: MapAppearance()

    fun save(projectId: String, appearance: MapAppearance) {
        scope.launch { mutex.withLock {
            if (!loaded) { cache.value = read(); loaded = true }
            if (projectId !in removedProjects) {
                cache.value = cache.value + (projectId to prune(appearance))
                write(cache.value)
            }
        } }
    }

    suspend fun remove(projectId: String) = mutex.withLock {
        // Do not erase another project's configuration if the shared file is unreadable.
        if (file.exists() || File(file.path + ".bak").exists()) JSONObject(android.util.AtomicFile(file).openRead().bufferedReader().use { it.readText() })
        if (!loaded) { cache.value = read(); loaded = true }
        removedProjects += projectId
        val remaining = cache.value - projectId
        write(remaining).getOrThrow()
        cache.value = remaining
    }

    private val removedOwners = mutableSetOf<String>()
    private fun prune(appearance: MapAppearance) = appearance.copy(
        nodeColors = appearance.nodeColors - removedOwners,
        branchColors = appearance.branchColors - removedOwners,
        nodeFinishes = appearance.nodeFinishes - removedOwners,
        nodeWidths = appearance.nodeWidths - removedOwners,
    )
    suspend fun removeOwners(ids: Set<String>) = mutex.withLock {
        if (file.exists() || File(file.path + ".bak").exists()) JSONObject(android.util.AtomicFile(file).openRead().bufferedReader().use { it.readText() })
        if (!loaded) { cache.value = read(); loaded = true }
        removedOwners += ids
        val remaining = cache.value.mapValues { prune(it.value) }
        write(remaining).getOrThrow()
        cache.value = remaining
    }

    private suspend fun read(): Map<String, MapAppearance> = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.exists() && !File(file.path + ".bak").exists()) return@runCatching emptyMap<String, MapAppearance>()
            val root = JSONObject(android.util.AtomicFile(file).openRead().bufferedReader().use { it.readText() })
            buildMap {
                root.keys().forEach { projectId ->
                    val o = root.getJSONObject(projectId)
                    put(
                        projectId,
                        MapAppearance(
                            layout = MapLayout.entries.firstOrNull { it.name == o.optString("layout") }
                                ?: MapLayout.RIGHT_TREE,
                            paletteIndex = o.optInt("paletteIndex", 0),
                            nodeColors = o.longMap("nodeColors"),
                            branchColors = o.longMap("branchColors"),
                            customPalette = o.longList("customPalette"),
                            savedColors = o.longList("savedColors"),
                            boxFinish = enumValueOrDefault(o.optString("boxFinish")),
                            nodeFinishes = o.finishMap("nodeFinishes"),
                            nodeWidths = o.widthMap("nodeWidths"),
                        )
                    )
                }
            }
            // A corrupt or partly written file must not stop the map opening; defaults are safe.
        }.getOrDefault(emptyMap())
    }

    private suspend fun write(all: Map<String, MapAppearance>) = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject()
            all.forEach { (projectId, appearance) ->
                root.put(
                    projectId,
                    JSONObject()
                        .put("layout", appearance.layout.name)
                        .put("paletteIndex", appearance.paletteIndex)
                        .put("nodeColors", appearance.nodeColors.toJson())
                        .put("branchColors", appearance.branchColors.toJson())
                        .put("customPalette", org.json.JSONArray().also { a -> appearance.customPalette.forEach { a.put(it.toString(16)) } })
                        .put("savedColors", org.json.JSONArray().also { a -> appearance.savedColors.forEach { a.put(it.toString(16)) } })
                        .put("boxFinish", appearance.boxFinish.name)
                        .put("nodeFinishes", JSONObject().also { o -> appearance.nodeFinishes.forEach { (id, finish) -> o.put(id, finish.name) } })
                        .put("nodeWidths", JSONObject().also { o -> appearance.nodeWidths.forEach { (id, width) ->
                            if (width.isFinite() && width in 110f..560f) o.put(id, width.toDouble())
                        } })
                )
            }
            val atomic = android.util.AtomicFile(file)
            val output = atomic.startWrite()
            try { output.write(root.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
            catch (e: Exception) { atomic.failWrite(output); throw e }
        }
    }

    private fun Map<String, Long>.toJson() = JSONObject().also { o ->
        forEach { (id, argb) -> o.put(id, argb.toString(16)) }
    }

    private fun JSONObject.longMap(field: String): Map<String, Long> {
        val source = optJSONObject(field) ?: return emptyMap()
        val out = mutableMapOf<String, Long>()
        source.keys().forEach { id ->
            source.getString(id).toULongOrNull(16)?.let { out[id] = it.toLong() }
        }
        return out
    }

    private fun JSONObject.longList(field: String): List<Long> {
        val source = optJSONArray(field) ?: return emptyList()
        return (0 until source.length()).mapNotNull { source.optString(it).toULongOrNull(16)?.toLong() }.take(32)
    }

    private fun enumValueOrDefault(value: String): MapBoxFinish =
        MapBoxFinish.entries.firstOrNull { it.name == value } ?: MapBoxFinish.SMOOTH

    private fun JSONObject.finishMap(field: String): Map<String, MapBoxFinish> {
        val source = optJSONObject(field) ?: return emptyMap()
        return buildMap { source.keys().forEach { id ->
            MapBoxFinish.entries.firstOrNull { it.name == source.optString(id) }?.let { put(id, it) }
        } }
    }

    private fun JSONObject.widthMap(field: String): Map<String, Float> {
        val source = optJSONObject(field) ?: return emptyMap()
        return buildMap { source.keys().forEach { id ->
            val width = source.optDouble(id).toFloat()
            if (width.isFinite() && width in 110f..560f) put(id, width)
        } }
    }

    companion object {
        @Volatile private var instance: MapAppearanceStore? = null
        fun get(context: Context): MapAppearanceStore = instance ?: synchronized(this) {
            instance ?: MapAppearanceStore(context.applicationContext).also { instance = it }
        }
    }
}
