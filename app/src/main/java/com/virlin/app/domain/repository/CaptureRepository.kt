package com.virlin.app.domain.repository

import com.virlin.app.domain.model.CaptureItem
import kotlinx.coroutines.flow.StateFlow

/**
 * Capture persistence contract (Pass 10). Kept as its own small abstraction, but implemented
 * by the SAME repository/transaction as WorkStreams so "convert capture to Task" can create
 * the Task and mark the capture ORGANIZED atomically. Publish-on-commit, like everything else.
 */
interface CaptureRepository {
    /** Every capture, newest first by persisted `createdAt` (ties broken by id). */
    val captures: StateFlow<List<CaptureItem>>
    suspend fun getCapture(id: String): CaptureItem?
}

/** Capture writes inside a repository transaction. */
interface CaptureWriter {
    suspend fun getCapture(id: String): CaptureItem?
    suspend fun saveCapture(capture: CaptureItem)
}

fun List<CaptureItem>.newestFirst(): List<CaptureItem> = sortedWith(compareByDescending<CaptureItem> { it.createdAt }.thenByDescending { it.id })
