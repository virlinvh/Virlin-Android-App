package com.virlin.app.ui.agent

import com.virlin.app.model.CaptureType
import com.virlin.app.ui.orb.AgentMode

/**
 * UI-only state of the Agent workspace shell. Owned by [com.virlin.app.ui.orb.VirlinAgentViewModel]
 * alongside the Orb interaction state so there is exactly one owner and no duplicated truth.
 *
 * Nothing here is persistent domain state. CONTROL's real content lives in
 * `ui/agent/control` (Pass 8) and reads the domain; the legacy `selectedControlContextId`
 * only serves the display-only context strip kept for the approved golden. CREATE's real
 * content lives in `ui/agent/create` (Pass 9); `createType`/`createDestinationId` only serve
 * the display-only create strip kept for the approved golden. CAPTURE's real content lives in
 * `ui/agent/capture` (Pass 10). CONTROL / CREATE composer text runs the deterministic command
 * language (`ui/agent/command`, Pass 12); the legacy demo receipt pipeline is no longer wired
 * from the composer. Attachments are still demo objects.
 */
data class AgentWorkspaceUiState(
    val mode: AgentMode = AgentMode.CONTROL,
    /**
     * Agent entry: false right after the Orb opens the Agent, until the user picks CONTROL /
     * CREATE / CAPTURE on the entry sheet ("How can I help?"). The workspace for [mode] renders only
     * once chosen. Defaults to true so an already-chosen workspace (tests, previews) renders directly.
     */
    val modeChosen: Boolean = true,
    /** CONTROL: the selected live-context item, if any. */
    val selectedControlContextId: String? = null,
    /** CREATE: the explicit creation type. Natural language remains primary. */
    val createType: CreateType = CreateType.TASK,
    /** CREATE: optional destination chip. Never required. */
    val createDestinationId: String? = null,
    /** Universal composer attachments. In CAPTURE these are the tray. */
    val attachments: List<InputObject> = emptyList(),
    /** Whether the compact "+" menu is open. */
    val attachmentMenuOpen: Boolean = false,
    /** Temporary confirmation. Cleared by Undo, the next submit, or a mode switch. */
    val receipt: Receipt? = null
)

enum class CreateType(val label: String, val verb: String) {
    TASK("Task", "Task added"),
    WORKSTREAM("WorkStream", "WorkStream created"),
    REMINDER("Reminder", "Reminder set")
}

/** A compact, selectable "Live Context" item for CONTROL. Display only. */
data class ControlContext(
    val id: String,
    val title: String,
    val subtitle: String,
    val stateLabel: String
)

/** A destination chip for CREATE. Display only. */
data class Destination(val id: String, val name: String)

/**
 * Universal input objects the composer can carry — the future contracts for prompt, link,
 * voice, file and image capture. Demo shells only: no storage, no recording, no fetching.
 * A Prompt is explicitly structured text, never a single-line chip.
 */
sealed interface InputObject {
    val id: String
    val type: CaptureType
    val accessibilityLabel: String

    data class Prompt(
        override val id: String,
        val title: String,
        val preview: String,
        val lineCount: Int
    ) : InputObject {
        override val type get() = CaptureType.PROMPT
        override val accessibilityLabel get() = "Prompt: $title, $lineCount lines"
    }

    data class Link(
        override val id: String,
        val url: String,
        val domain: String,
        val title: String
    ) : InputObject {
        override val type get() = CaptureType.LINK
        override val accessibilityLabel get() = "Link: $title, $domain"
    }

    data class File(
        override val id: String,
        val name: String,
        val kind: String,
        val sizeLabel: String
    ) : InputObject {
        override val type get() = CaptureType.FILE
        override val accessibilityLabel get() = "File: $name, $kind, $sizeLabel"
    }

    data class Image(
        override val id: String,
        val label: String,
        val fileName: String
    ) : InputObject {
        override val type get() = CaptureType.IMAGE
        override val accessibilityLabel get() = "Image: $label"
    }

    data class Voice(
        override val id: String,
        val durationSec: Int
    ) : InputObject {
        override val type get() = CaptureType.VOICE
        override val accessibilityLabel get() = "Voice recording, ${durationSec / 60}:${"%02d".format(durationSec % 60)}"
    }
}

/** Compact temporary confirmation. Not a chat bubble; not a toast. */
data class Receipt(
    val kind: Kind,
    val status: String,
    val primary: String,
    val secondary: String? = null,
    val actions: List<String> = emptyList()
) {
    enum class Kind { SUCCESS, CAPTURE_SUCCESS, ERROR }
}
