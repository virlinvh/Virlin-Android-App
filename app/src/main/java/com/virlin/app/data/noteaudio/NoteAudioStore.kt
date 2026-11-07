package com.virlin.app.data.noteaudio

import android.content.Context
import com.virlin.app.domain.notedoc.NoteDocAudio
import java.io.File
import java.util.UUID

/** Private recordings made inside notes. Only names matching [NoteDocAudio.FILE] are ever opened. */
object NoteAudioStore {
    private const val DIR = "note_audio"

    fun resolve(context: Context, name: String): File? =
        name.takeIf { NoteDocAudio.FILE.matches(it) }?.let { File(context.filesDir, "$DIR/$it") }

    /** A fresh, unused file name and its file, ready for a recorder to write. */
    fun newRecording(context: Context): Pair<String, File> {
        val root = File(context.filesDir, DIR).also { it.mkdirs() }
        val name = "${UUID.randomUUID()}.m4a"
        return name to File(root, name)
    }
}
