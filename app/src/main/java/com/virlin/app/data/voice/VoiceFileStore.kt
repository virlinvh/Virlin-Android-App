package com.virlin.app.data.voice

import android.content.Context
import java.io.File

/** Managed storage for voice clip audio under filesDir/voices/. */
object VoiceFileStore {

    fun voicesRoot(context: Context): File =
        File(context.filesDir, "voices").also { it.mkdirs() }

    fun clipFile(context: Context, captureId: String, clipId: String): File {
        val dir = File(voicesRoot(context), captureId).also { it.mkdirs() }
        return File(dir, "$clipId.m4a")
    }

    fun relativePath(captureId: String, clipId: String): String = "$captureId/$clipId.m4a"

    fun resolve(context: Context, relativePath: String): File =
        File(voicesRoot(context), relativePath)

    fun deleteClip(context: Context, relativePath: String) {
        resolve(context, relativePath).delete()
    }

    fun deleteCaptureTree(context: Context, captureId: String) {
        File(voicesRoot(context), captureId).deleteRecursively()
    }
}
