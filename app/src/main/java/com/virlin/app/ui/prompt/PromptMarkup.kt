package com.virlin.app.ui.prompt

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.sp

/** Styled projection only: character offsets, copy, and persisted source remain untouched. */
internal fun styledPromptMarkup(source: String, accent: Color): AnnotatedString = buildAnnotatedString {
    append(source)
    Regex("(?m)^#{1,6}[ \\t]+[^\\r\\n]+").findAll(source).forEach { m ->
        addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = accent,
            fontSize = if (m.value.startsWith("# ")) 19.sp else 16.sp), m.range.first, m.range.last + 1)
    }
    Regex("\\*\\*(.+?)\\*\\*|__(.+?)__").findAll(source).forEach { m ->
        addStyle(SpanStyle(fontWeight = FontWeight.Bold), m.range.first, m.range.last + 1)
    }
    Regex("(?m)^[ \\t]*(?:[-*+] |[0-9]+[.)] )").findAll(source).forEach { m ->
        addStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = accent), m.range.first, m.range.last + 1)
    }
}

internal class PromptMarkupTransformation(private val accent: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(styledPromptMarkup(text.text, accent), OffsetMapping.Identity)
}
