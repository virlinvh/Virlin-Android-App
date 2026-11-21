package com.virlin.app.ui.notes

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Makes Android's PDFs light. [android.graphics.pdf.PdfDocument] keeps text as real, subset-font
 * text but stores every picture losslessly; this rewrites opaque RGB pictures as JPEG and rebuilds
 * the cross-reference table. Anything it does not fully understand (indirect lengths,
 * transparency masks, other colour spaces, an unexpected layout) is left exactly as it was, and
 * the original file is returned whenever the rewrite would not be smaller.
 */
internal object NotePdfSlimmer {

    fun slim(pdf: ByteArray, jpegQuality: Int): ByteArray =
        runCatching { rewrite(pdf, jpegQuality) }.getOrNull()?.takeIf { it.size < pdf.size } ?: pdf

    private class Obj(val num: Int, val start: Int, val end: Int)

    private val OBJ = Regex("(\\d+) 0 obj")
    private val ICC = Regex("/ColorSpace\\s*\\[\\s*/ICCBased\\s+(\\d+)\\s+0\\s+R\\s*\\]")
    private val LENGTH = Regex("/Length\\s+(\\d+)(\\s+\\d+\\s+R)?")

    private fun rewrite(pdf: ByteArray, quality: Int): ByteArray? {
        val text = String(pdf, Charsets.ISO_8859_1)
        val objects = ArrayList<Obj>()
        val streams = HashMap<Int, IntRange>()
        var pos = 0
        while (true) {
            val m = OBJ.find(text, pos) ?: break
            val bodyStart = m.range.last + 1
            val endObj = text.indexOf("endobj", bodyStart).takeIf { it >= 0 } ?: return null
            val streamAt = text.indexOf("stream", bodyStart)
            val end: Int
            if (streamAt in bodyStart until endObj) {
                val dict = text.substring(bodyStart, streamAt)
                val length = LENGTH.find(dict) ?: return null
                if (length.groupValues[2].isNotEmpty()) return null
                var dataStart = streamAt + "stream".length
                if (text.startsWith("\r\n", dataStart)) dataStart += 2 else if (text[dataStart] == '\n') dataStart++
                val dataEnd = dataStart + length.groupValues[1].toInt()
                val endStream = text.indexOf("endstream", dataEnd).takeIf { it >= 0 } ?: return null
                if (text.substring(dataEnd, endStream).isNotBlank()) return null
                val close = text.indexOf("endobj", endStream).takeIf { it >= 0 } ?: return null
                end = close + "endobj".length
                streams[objects.size] = dataStart until dataEnd
            } else {
                end = endObj + "endobj".length
            }
            objects += Obj(m.groupValues[1].toInt(), m.range.first, end)
            pos = end
        }
        if (objects.isEmpty()) return null
        val trailerAt = text.indexOf("trailer", objects.last().end).takeIf { it >= 0 } ?: return null
        val startXref = text.indexOf("startxref", trailerAt).takeIf { it >= 0 } ?: return null

        val out = ByteArrayOutputStream(pdf.size)
        out.write(pdf, 0, objects.first().start)
        val offsets = HashMap<Int, Int>()
        var changed = false
        val byNum = objects.associateBy { it.num }
        val rgbProfile = { num: Int ->
            byNum[num]?.let { Regex("/N\\s+3\\b").containsMatchIn(text.substring(it.start, minOf(it.end, it.start + 400))) } == true
        }
        objects.forEachIndexed { i, o ->
            offsets[o.num] = out.size()
            val replaced = streams[i]?.let { data -> jpegImage(o, text.substring(o.start, data.first), pdf, data, quality, rgbProfile) }
            if (replaced != null) { out.write(replaced); changed = true }
            else out.write(pdf, o.start, o.end - o.start)
            out.write('\n'.code)
        }
        if (!changed) return null
        val xrefAt = out.size()
        val max = offsets.keys.max()
        val xref = StringBuilder("xref\n0 ${max + 1}\n0000000000 65535 f \n")
        for (n in 1..max) {
            val at = offsets[n]
            xref.append(if (at == null) "0000000000 65535 f \n" else "%010d 00000 n \n".format(java.util.Locale.US, at))
        }
        val trailer = text.substring(trailerAt, startXref).replace(Regex("/Size\\s+\\d+"), "/Size ${max + 1}")
        out.write(xref.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(trailer.toByteArray(Charsets.ISO_8859_1))
        out.write("startxref\n$xrefAt\n%%EOF\n".toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    /** The object rewritten as a JPEG picture, or null to keep it as it is. */
    private fun jpegImage(o: Obj, head: String, pdf: ByteArray, data: IntRange, quality: Int,
                          rgbProfile: (Int) -> Boolean): ByteArray? {
        if (!Regex("/Subtype\\s*/Image").containsMatchIn(head)) return null
        if (!Regex("/Filter\\s*/FlateDecode").containsMatchIn(head)) return null
        val colorSpace = when {
            Regex("/ColorSpace\\s*/DeviceRGB").containsMatchIn(head) -> "/DeviceRGB"
            else -> ICC.find(head)?.takeIf { rgbProfile(it.groupValues[1].toInt()) }?.let { "[/ICCBased ${it.groupValues[1]} 0 R]" }
                ?: return null
        }
        if (!Regex("/BitsPerComponent\\s+8").containsMatchIn(head)) return null
        if ("/SMask" in head || "/Mask" in head || "/DecodeParms" in head || "/Decode " in head) return null
        val width = Regex("/Width\\s+(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: return null
        val height = Regex("/Height\\s+(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: return null
        if (width < 8 || height < 8 || width.toLong() * height > 40_000_000L) return null
        val rgb = ByteArray(width * height * 3)
        val inflater = Inflater()
        try {
            inflater.setInput(pdf, data.first, data.last - data.first + 1)
            var read = 0
            while (read < rgb.size) {
                val n = inflater.inflate(rgb, read, rgb.size - read)
                if (n == 0) { if (inflater.finished() || inflater.needsInput() || inflater.needsDictionary()) break }
                read += n
            }
            if (read != rgb.size) return null
        } finally {
            inflater.end()
        }
        val pixels = IntArray(width * height) { i ->
            val j = i * 3
            (0xFF shl 24) or ((rgb[j].toInt() and 0xFF) shl 16) or ((rgb[j + 1].toInt() and 0xFF) shl 8) or (rgb[j + 2].toInt() and 0xFF)
        }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val jpeg = try {
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
        if (jpeg.isEmpty() || jpeg.size >= data.last - data.first + 1) return null
        val dict = "${o.num} 0 obj\n<< /Type /XObject /Subtype /Image /Width $width /Height $height " +
            "/ColorSpace $colorSpace /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n"
        return ByteArrayOutputStream().apply {
            write(dict.toByteArray(Charsets.ISO_8859_1))
            write(jpeg)
            write("\nendstream\nendobj".toByteArray(Charsets.ISO_8859_1))
        }.toByteArray()
    }
}
