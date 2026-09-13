package com.virlin.app.ui.orb

/**
 * The ONE semantic interaction state of the Living Orb.
 *
 * The Orb never changes identity; this state only changes how the same liquid material
 * behaves. It is owned by [VirlinAgentViewModel] and flows down to the renderer as
 * [OrbInteractionParameters] via [toOrbParameters].
 */
sealed interface VirlinOrbInteractionState {
    /** Virlin is alive and available. Agent closed. */
    data object Idle : VirlinOrbInteractionState

    /** Pointer is down on the Orb. Agent still closed. */
    data object Pressed : VirlinOrbInteractionState

    /** Valid tap released; the Agent surface is rising. */
    data object Opening : VirlinOrbInteractionState

    /** Agent open, waiting for instruction. */
    data object Ready : VirlinOrbInteractionState

    /** Input is arriving (any modality — typing, paste, attachment, transcription). */
    data object Receiving : VirlinOrbInteractionState

    /** Input exists and the user has stopped editing. Waiting for submit. */
    data object ReadyWithInput : VirlinOrbInteractionState

    /** Interpreting a submitted instruction. Concentration, not loading. */
    data object Understanding : VirlinOrbInteractionState

    /** Understood in part; needs one more thing. Never an error. */
    data object Clarification : VirlinOrbInteractionState

    /** Executing. */
    data object Acting : VirlinOrbInteractionState

    /** One-shot: done. */
    data object Success : VirlinOrbInteractionState

    /** One-shot signature: "Virlin remembered it." */
    data object CaptureSuccess : VirlinOrbInteractionState

    /** Responding (future TTS). Visual hook only. */
    data object Speaking : VirlinOrbInteractionState

    /** Action failed. Warm, never red. */
    data object Error : VirlinOrbInteractionState

    /** Agent surface descending back to Now. */
    data object Closing : VirlinOrbInteractionState

    /** True while the Agent surface should be composed (rising, open, or descending). */
    val isAgentSurfaceVisible: Boolean
        get() = this !is Idle && this !is Pressed

    /** True for states the user can act from inside the open Agent. */
    val isAgentInteractive: Boolean
        get() = this is Ready || this is ReadyWithInput || this is Receiving || this is Clarification

    /** Short status text — the non-motion information channel (accessibility, Rule 8). */
    val statusText: String
        get() = when (this) {
            Idle -> "Virlin is available"
            Pressed -> "Virlin"
            Opening -> "Opening"
            Ready -> "Ready"
            Receiving -> "Receiving"
            ReadyWithInput -> "Send when ready"
            Understanding -> "Understanding"
            Clarification -> "Needs one more thing"
            Acting -> "Working on it"
            Success -> "Done"
            CaptureSuccess -> "Remembered"
            Speaking -> "Speaking"
            Error -> "Couldn't do that"
            Closing -> "Closing"
        }
}

/** The three Agent contexts. All share the same Orb and the same state machine. */
enum class AgentMode(val label: String) {
    CONTROL("CONTROL"),
    CREATE("CREATE"),
    CAPTURE("CAPTURE")
}

/** A one-shot visual event carried to the renderer. The nonce makes repeats distinct. */
sealed interface OrbOneShot {
    val nonce: Long

    data class Success(override val nonce: Long) : OrbOneShot
    data class CaptureSuccess(override val nonce: Long) : OrbOneShot
}

/**
 * Continuous rendering parameters derived from [VirlinOrbInteractionState].
 *
 * The renderer animates smoothly toward these targets; states change the TARGETS, never
 * the animation phase. Only parameters the existing renderer can honour without touching
 * the shader source are represented here.
 */
data class OrbInteractionParameters(
    /** Rate multiplier for the liquid's continuous phase. 1.0 = approved idle. */
    val motionMultiplier: Float = 1f,
    /** Halo attention, 0..1. 0 = approved idle halo. */
    val attentionIntensity: Float = 0f,
    /** Target scale; press/opening/acting compressions live here. */
    val orbScale: Float = 1f,
    /** Restrained lime/cream illumination over the liquid, 0..1. */
    val warmAccentIntensity: Float = 0f,
    /** Centre-weighted concentration, 0..1 (Understanding). */
    val convergenceInfluence: Float = 0f,
    /** Warm amber/peach shift, 0..1 (Error). Never red. */
    val errorWarmth: Float = 0f,
    /** Procedural irregular rhythm (Speaking). */
    val speakingPulse: Boolean = false,
    /** Future TTS hook, 0..1. Null = procedural. Only ever restrained modulation. */
    val speechEnergy: Float? = null,
    /** Idle breathing on/off. Off while the Agent is open ("no constant breathing pulse"). */
    val breathing: Boolean = true,
    /** Pending one-shot bloom, if any. */
    val oneShot: OrbOneShot? = null
)

/**
 * State → parameter mapping. Energy figures follow the approved motion hierarchy
 * (see the `virlin-motion-interaction` skill). [reducedMotion] keeps every state
 * legible while suppressing amplitude.
 */
fun VirlinOrbInteractionState.toOrbParameters(
    reducedMotion: Boolean,
    oneShotNonce: Long
): OrbInteractionParameters {
    val rm = reducedMotion
    fun m(full: Float, reduced: Float) = if (rm) reduced else full

    return when (this) {
        VirlinOrbInteractionState.Idle -> OrbInteractionParameters(
            motionMultiplier = 1f,
            breathing = !rm
        )
        VirlinOrbInteractionState.Pressed -> OrbInteractionParameters(
            motionMultiplier = m(1.6f, 1.0f),
            orbScale = 0.96f,
            warmAccentIntensity = m(0.12f, 0f),
            attentionIntensity = m(0.2f, 0.1f),
            breathing = false
        )
        VirlinOrbInteractionState.Opening -> OrbInteractionParameters(
            motionMultiplier = m(1.3f, 1.0f),
            orbScale = 1f,
            warmAccentIntensity = m(0.08f, 0f),
            attentionIntensity = 0.35f,
            breathing = false
        )
        VirlinOrbInteractionState.Ready -> OrbInteractionParameters(
            motionMultiplier = 1.05f,
            attentionIntensity = 0.3f,
            breathing = false
        )
        VirlinOrbInteractionState.Receiving -> OrbInteractionParameters(
            motionMultiplier = m(1.4f, 1.15f),
            attentionIntensity = m(0.55f, 0.4f),
            convergenceInfluence = m(0.15f, 0.08f),
            breathing = false
        )
        VirlinOrbInteractionState.ReadyWithInput -> OrbInteractionParameters(
            motionMultiplier = 1.1f,
            attentionIntensity = 0.4f,
            breathing = false
        )
        VirlinOrbInteractionState.Understanding -> OrbInteractionParameters(
            motionMultiplier = m(1.7f, 1.25f),
            attentionIntensity = m(0.65f, 0.45f),
            convergenceInfluence = m(0.8f, 0.45f),
            warmAccentIntensity = m(0.10f, 0.05f),
            breathing = false
        )
        VirlinOrbInteractionState.Clarification -> OrbInteractionParameters(
            motionMultiplier = 1.15f,
            attentionIntensity = 0.45f,
            convergenceInfluence = 0.2f,
            warmAccentIntensity = m(0.22f, 0.15f),
            breathing = false
        )
        VirlinOrbInteractionState.Acting -> OrbInteractionParameters(
            motionMultiplier = m(2.0f, 1.35f),
            attentionIntensity = m(0.75f, 0.5f),
            convergenceInfluence = 0.5f,
            warmAccentIntensity = m(0.28f, 0.15f),
            orbScale = m(0.985f, 1f),
            breathing = false
        )
        VirlinOrbInteractionState.Success -> OrbInteractionParameters(
            motionMultiplier = 1.2f,
            attentionIntensity = 0.5f,
            orbScale = m(1.025f, 1f),
            breathing = false,
            oneShot = OrbOneShot.Success(oneShotNonce)
        )
        VirlinOrbInteractionState.CaptureSuccess -> OrbInteractionParameters(
            motionMultiplier = 1.2f,
            attentionIntensity = 0.5f,
            breathing = false,
            oneShot = OrbOneShot.CaptureSuccess(oneShotNonce)
        )
        VirlinOrbInteractionState.Speaking -> OrbInteractionParameters(
            motionMultiplier = m(1.45f, 1.15f),
            attentionIntensity = 0.5f,
            speakingPulse = true,
            breathing = false
        )
        VirlinOrbInteractionState.Error -> OrbInteractionParameters(
            motionMultiplier = m(0.6f, 0.85f),
            attentionIntensity = 0.3f,
            orbScale = m(0.975f, 1f),
            errorWarmth = m(0.6f, 0.4f),
            breathing = false
        )
        VirlinOrbInteractionState.Closing -> OrbInteractionParameters(
            motionMultiplier = 1f,
            attentionIntensity = 0.1f,
            breathing = false
        )
    }
}
