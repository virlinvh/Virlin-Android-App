package com.virlin.app.ui.notes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.virlin.app.ui.hierarchy.ProjectPalette

internal val LocalNotesPalette = staticCompositionLocalOf<ProjectPalette?> { null }
internal val NotesPaper: Color @Composable get() = LocalNotesPalette.current?.let { Color(it.surface) } ?: Color.White
internal val NotesInk: Color @Composable get() = LocalNotesPalette.current?.let { Color(it.text) } ?: Color(0xFF17221D)
internal val NotesAccent: Color @Composable get() = LocalNotesPalette.current?.let { Color(it.secondary) } ?: Color(0xFF087848)
internal val NotesWash: Color @Composable get() = LocalNotesPalette.current?.let { Color(it.tertiary) } ?: Color(0xFFE2F5EA)
internal val NotesBorder: Color @Composable get() = LocalNotesPalette.current?.let { Color(it.primary).copy(alpha = .25f) } ?: Color(0xFFE3E9E5)
internal val NotesMuted: Color @Composable get() = LocalNotesPalette.current?.let { Color(it.muted) } ?: Color(0xFF66758A)
