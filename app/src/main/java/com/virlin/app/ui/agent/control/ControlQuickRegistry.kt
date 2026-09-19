package com.virlin.app.ui.agent.control

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.virlin.app.ui.theme.VirlinColors

/**
 * Presentation descriptors for the Control Quick Actions rail.
 *
 * Domain eligibility is NOT reimplemented here — [eligibleFor] delegates to
 * [AgentControlPresentation.actionsFor] (plus Block, which is a Quick-rail path via
 * [AttentionIntentController.block] but not a per-row [ControlAction] chip).
 *
 * Each action owns a unique semantic [QuickActionLook] so Focus / Resume / Complete / etc.
 * are distinguishable at a glance. Soft pastel containers + stronger icon colors;
 * selected state stays in the same family (never a universal green).
 */
enum class QuickAction(val label: String) {
    FOCUS("Focus"),
    RESUME("Resume"),
    LEAVE("Leave"),
    HAND_OFF("Hand Off"),
    COMPLETE("Complete"),
    CHECK("Check"),
    FOCUS_NOW("Focus Now"),
    DEFER("Defer"),
    BLOCK("Block"),
    TASKS("Tasks");

    /** Maps to a structured [ControlAction], or null for Block (intent-only). */
    val controlAction: ControlAction? get() = when (this) {
        FOCUS -> ControlAction.FOCUS
        RESUME -> ControlAction.RESUME
        LEAVE -> ControlAction.LEAVE
        HAND_OFF -> ControlAction.HAND_OFF
        COMPLETE -> ControlAction.COMPLETE
        CHECK -> ControlAction.CHECK
        FOCUS_NOW -> ControlAction.FOCUS_NOW
        DEFER -> ControlAction.DEFER
        TASKS -> ControlAction.TASKS
        BLOCK -> null
    }

    companion object {
        /** Stable rail order — discoverable left→right. */
        val rail: List<QuickAction> = entries
    }
}

/**
 * Visual style for one Quick Action tile.
 *
 * [containerTop]/[containerBottom] = soft pastel gradient body.
 * [iconColor] = stronger foreground (or badge glyph when [badgeFill] is set).
 * [border] / [selectedBorder] = idle vs selected emphasis in the same family.
 * [selectedTop]/[selectedBottom] = slightly richer container while selected.
 */
data class QuickActionLook(
    val icon: ImageVector,
    val iconColor: Color,
    val containerTop: Color,
    val containerBottom: Color,
    val border: Color,
    val selectedBorder: Color,
    val selectedTop: Color,
    val selectedBottom: Color,
    /** Optional filled badge behind the icon (Leave / Block style). */
    val badgeFill: Color? = null,
    val badgeIconColor: Color = Color.White,
    /** Short family label for docs/tests — not shown in UI. */
    val colorFamily: String
) {
    /** Backward-compatible aliases used by the tile renderer. */
    val top: Color get() = containerTop
    val bottom: Color get() = containerBottom
    val tint: Color get() = iconColor
    val filled: Color? get() = badgeFill
}

object ControlQuickRegistry {

    fun look(a: QuickAction): QuickActionLook = when (a) {
        // Deep emerald — attention / Focus (distinct from Complete jade)
        QuickAction.FOCUS -> QuickActionLook(
            icon = Icons.Rounded.Description,
            iconColor = Color(0xFF065F46),
            containerTop = Color(0xFFECFDF5),
            containerBottom = Color(0xFFA7F3D0),
            border = Color(0xFF6EE7B7),
            selectedBorder = Color(0xFF047857),
            selectedTop = Color(0xFFD1FAE5),
            selectedBottom = Color(0xFF6EE7B7),
            colorFamily = "deep emerald"
        )
        // Teal — resume / continue
        QuickAction.RESUME -> QuickActionLook(
            icon = Icons.Rounded.PlayArrow,
            iconColor = Color(0xFF0F766E),
            containerTop = Color(0xFFF0FDFA),
            containerBottom = Color(0xFF99F6E4),
            border = Color(0xFF5EEAD4),
            selectedBorder = Color(0xFF0D9488),
            selectedTop = Color(0xFFCCFBF1),
            selectedBottom = Color(0xFF5EEAD4),
            colorFamily = "teal"
        )
        // Amber / warm — leave (reuse Virlin Amber for badge)
        QuickAction.LEAVE -> QuickActionLook(
            icon = Icons.Rounded.Bolt,
            iconColor = Color.White,
            containerTop = Color(0xFFFFFBEB),
            containerBottom = Color(0xFFFDE68A),
            border = Color(0xFFFCD34D),
            selectedBorder = Color(0xFFD97706),
            selectedTop = Color(0xFFFEF3C7),
            selectedBottom = Color(0xFFFBBF24),
            badgeFill = VirlinColors.Amber,
            badgeIconColor = Color.White,
            colorFamily = "amber"
        )
        // Turquoise / sea — hand off (cooler than Resume teal)
        QuickAction.HAND_OFF -> QuickActionLook(
            icon = Icons.Rounded.Groups,
            iconColor = Color(0xFF0E7490),
            containerTop = Color(0xFFECFEFF),
            containerBottom = Color(0xFFA5F3FC),
            border = Color(0xFF67E8F9),
            selectedBorder = Color(0xFF0891B2),
            selectedTop = Color(0xFFCFFAFE),
            selectedBottom = Color(0xFF67E8F9),
            colorFamily = "turquoise"
        )
        // Jade / lighter success — complete (not Focus emerald)
        QuickAction.COMPLETE -> QuickActionLook(
            icon = Icons.Rounded.CheckCircle,
            iconColor = Color(0xFF15803D),
            containerTop = Color(0xFFF0FDF4),
            containerBottom = Color(0xFFBBF7D0),
            border = Color(0xFF86EFAC),
            selectedBorder = Color(0xFF16A34A),
            selectedTop = Color(0xFFDCFCE7),
            selectedBottom = Color(0xFF86EFAC),
            colorFamily = "jade"
        )
        // Cyan / sky — inspect / check (cooler than Hand Off turquoise)
        QuickAction.CHECK -> QuickActionLook(
            icon = Icons.Rounded.Search,
            iconColor = Color(0xFF0369A1),
            containerTop = Color(0xFFF0F9FF),
            containerBottom = Color(0xFFBAE6FD),
            border = Color(0xFF7DD3FC),
            selectedBorder = Color(0xFF0284C7),
            selectedTop = Color(0xFFE0F2FE),
            selectedBottom = Color(0xFF38BDF8),
            colorFamily = "sky cyan"
        )
        // Blue — focus now / result ready
        QuickAction.FOCUS_NOW -> QuickActionLook(
            icon = Icons.Rounded.TaskAlt,
            iconColor = Color(0xFF1D4ED8),
            containerTop = Color(0xFFEFF6FF),
            containerBottom = Color(0xFFBFDBFE),
            border = Color(0xFF93C5FD),
            selectedBorder = Color(0xFF2563EB),
            selectedTop = Color(0xFFDBEAFE),
            selectedBottom = Color(0xFF93C5FD),
            colorFamily = "blue"
        )
        // Slate / indigo-gray — defer / remind later
        QuickAction.DEFER -> QuickActionLook(
            icon = Icons.Rounded.NotificationsActive,
            iconColor = Color(0xFF4338CA),
            containerTop = Color(0xFFF8FAFC),
            containerBottom = Color(0xFFE2E8F0),
            border = Color(0xFFCBD5E1),
            selectedBorder = Color(0xFF6366F1),
            selectedTop = Color(0xFFEEF2FF),
            selectedBottom = Color(0xFFC7D2FE),
            colorFamily = "indigo-slate"
        )
        // Coral / restrained rose — block
        QuickAction.BLOCK -> QuickActionLook(
            icon = Icons.Rounded.Block,
            iconColor = Color.White,
            containerTop = Color(0xFFFFF1F2),
            containerBottom = Color(0xFFFECDD3),
            border = Color(0xFFFDA4AF),
            selectedBorder = Color(0xFFE11D48),
            selectedTop = Color(0xFFFFE4E6),
            selectedBottom = Color(0xFFFB7185),
            badgeFill = Color(0xFFF43F5E),
            badgeIconColor = Color.White,
            colorFamily = "coral"
        )
        // Olive / moss — tasks / structure
        QuickAction.TASKS -> QuickActionLook(
            icon = Icons.Rounded.Layers,
            iconColor = Color(0xFF4D5C20),
            containerTop = Color(0xFFF7F8EF),
            containerBottom = Color(0xFFE4ECCC),
            border = Color(0xFFD4DCB0),
            selectedBorder = Color(0xFF67792B),
            selectedTop = Color(0xFFEEF2D8),
            selectedBottom = Color(0xFFC5D08E),
            colorFamily = "olive"
        )
    }

    /**
     * Whether [action] is valid for this WorkStream projection kind.
     * Uses the same table as row chips; Block is allowed wherever FOCUS / PROCESSING /
     * CHECK_DUE / READY can move to BLOCKED (matches [WorkStreamTransitions] + existing quick Block).
     */
    fun eligibleFor(action: QuickAction, kind: ControlKind): Boolean {
        val chip = action.controlAction
        if (chip != null) return chip in AgentControlPresentation.actionsFor(kind)
        // BLOCK
        return kind == ControlKind.FOCUS_HUMAN || kind == ControlKind.FOCUS_EXTERNAL ||
            kind == ControlKind.PROCESSING || kind == ControlKind.CHECK_DUE ||
            kind == ControlKind.READY
    }

    fun eligibleFor(action: QuickAction, item: ControlItem?): Boolean =
        item != null && eligibleFor(action, item.kind)
}
