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

    companion object {
        @Volatile private var instance: MapAppearanceStore? = null
        fun get(context: Context): MapAppearanceStore = instance ?: synchronized(this) {
            instance ?: MapAppearanceStore(context.applicationContext).also { instance = it }
        }
    }
}
