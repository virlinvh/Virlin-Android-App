package com.virlin.app.domain.attachment

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** Limits actual bytes, not the untrusted size reported by a document provider or ZIP entry. */
class ImportLimitException : IOException("This file exceeds the safe preview limit. Try a smaller file.")

internal class ImportBudget(private val maxBytes: Long, private val seconds: Long = 10) {
    private val deadline = System.nanoTime() + seconds * 1_000_000_000
    private var used = 0L
    fun charge(bytes: Int = 0) {
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Preview cancelled")
        used += bytes
        if (used > maxBytes || System.nanoTime() > deadline) throw ImportLimitException()
    }
}

internal class BoundedImportStream(input: InputStream, maxBytes: Long) : FilterInputStream(input) {
    private val budget = ImportBudget(maxBytes)
    override fun read(): Int {
        budget.charge()
        return `in`.read().also { if (it >= 0) budget.charge(1) }
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        budget.charge()
        return `in`.read(b, off, len.coerceAtMost(8192)).also { if (it > 0) budget.charge(it) }
    }
    override fun skip(n: Long): Long {
        var remaining = n.coerceAtLeast(0)
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val count = read(buffer, 0, remaining.coerceAtMost(buffer.size.toLong()).toInt())
            if (count < 0) break
            remaining -= count
        }
        return n.coerceAtLeast(0) - remaining
    }
}

internal class BudgetZipStream(input: InputStream) : ZipInputStream(BoundedImportStream(input, 12L * 1024 * 1024)) {
    private val budget = ImportBudget(24L * 1024 * 1024)
    private var entryBytes = 0L
    private var entries = 0
    override fun getNextEntry(): ZipEntry? {
        // closeEntry drains through the overridden read, including ignored entries.
        closeEntry()
        entryBytes = 0
        budget.charge()
        val entry = super.getNextEntry()
        if (entry != null && ++entries > 512) throw ImportLimitException()
        return entry
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        budget.charge()
        val count = super.read(b, off, len.coerceAtMost(8192))
        if (count > 0) {
            budget.charge(count)
            entryBytes += count
            if (entryBytes > 4L * 1024 * 1024) throw ImportLimitException()
        }
        return count
    }
}

fun readBoundedImport(input: InputStream, maxBytes: Long = 12L * 1024 * 1024): ByteArray {
    val bounded = BoundedImportStream(input, maxBytes)
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = bounded.read(buffer)
        if (count < 0) return out.toByteArray()
        out.write(buffer, 0, count)
    }
}
