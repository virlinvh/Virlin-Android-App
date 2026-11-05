package com.virlin.app.ui.pdf

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class PdfAnnotationTool { VIEW, HIGHLIGHT, PEN, NOTE, ERASER }

data class PdfAnnotationPoint(val x: Float, val y: Float)

data class PdfAnnotation(
    val page: Int,
    val tool: PdfAnnotationTool,
    val points: List<PdfAnnotationPoint>,
    val text: String = "",
)

/** Annotations are deliberately separate from the source PDF bytes. */
internal object PdfAnnotationStore {
    private fun sidecar(pdf: File) = AtomicFile(File(pdf.parentFile, "${pdf.name}.annotations.json"))

    fun read(pdf: File): List<PdfAnnotation> = runCatching {
        val file = sidecar(pdf)
        if (!file.baseFile.exists()) return emptyList()
        val array = JSONArray(file.openRead().bufferedReader().use { it.readText() })
        buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val tool = PdfAnnotationTool.valueOf(item.getString("tool"))
                val coordinates = item.getJSONArray("points")
                val points = buildList {
                    for (j in 0 until coordinates.length()) {
                        val point = coordinates.getJSONArray(j)
                        add(PdfAnnotationPoint(point.getDouble(0).toFloat().coerceIn(0f, 1f),
                            point.getDouble(1).toFloat().coerceIn(0f, 1f)))
                    }
                }
                if (points.isNotEmpty()) add(PdfAnnotation(item.getInt("page"), tool, points,
                    item.optString("text")))
            }
        }
    }.getOrDefault(emptyList())

    fun write(pdf: File, annotations: List<PdfAnnotation>) {
        val file = sidecar(pdf)
        val array = JSONArray()
        annotations.forEach { annotation ->
            val points = JSONArray()
            annotation.points.forEach { point ->
                points.put(JSONArray().put(point.x.toDouble()).put(point.y.toDouble()))
            }
            array.put(JSONObject().put("page", annotation.page).put("tool", annotation.tool.name)
                .put("points", points).put("text", annotation.text))
        }
        val stream = file.startWrite()
        try {
            stream.write(array.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}
