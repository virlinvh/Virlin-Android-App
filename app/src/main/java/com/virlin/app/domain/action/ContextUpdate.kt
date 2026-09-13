package com.virlin.app.domain.action

/**
 * Explicit tri-state patch field so "leave the existing value" and "clear the value" are
 * never confused. Default everywhere is [Keep].
 */
sealed interface Field<out T> {
    data object Keep : Field<Nothing>
    data object Clear : Field<Nothing>
    data class Set<T>(val value: T) : Field<T>
}

fun <T> Field<T>.applyTo(current: T?): T? = when (this) {
    Field.Keep -> current
    Field.Clear -> null
    is Field.Set -> value
}

/** Partial update of a stream's external working memory. */
data class ContextUpdate(
    val lastHumanAction: Field<String> = Field.Keep,
    val waitingFor: Field<String> = Field.Keep,
    val nextHumanAction: Field<String> = Field.Keep,
    /** A note is additive history, not a field; it becomes a NOTE_ADDED event. */
    val note: String? = null
) {
    val isEmpty: Boolean
        get() = lastHumanAction == Field.Keep && waitingFor == Field.Keep &&
            nextHumanAction == Field.Keep && note.isNullOrBlank()
}
