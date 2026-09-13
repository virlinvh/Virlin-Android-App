---
name: virlin-motion-interaction
description: Virlin's motion and interaction language — Living Orb interaction states, split-flap physical behaviour, attention-state transitions, motion hierarchy, reduced motion, and haptics. Load ONLY when designing or implementing animation, transitions, gestures, or interaction feedback in Virlin. Do not load for layout, data, testing or build work.
---

# Virlin — Motion & Interaction

Load on demand. This is the motion language only; product architecture lives in
`virlin-project`, implementation rules in `virlin-android-compose`.

## First principle

**Motion communicates state.** If an animation does not tell the user something true about
what Virlin is doing, it does not belong. No decorative movement.

Corollary: state must never be conveyed by motion or colour *alone* — always pair with text
or a semantic label.

## Motion hierarchy

Energy is relative to idle. Transitions matter more than exact numbers.

| State | Energy | Meaning |
|---|---|---|
| Idle | 1.0x | Virlin is available |
| Ready | 1.05x | Agent open, awaiting instruction |
| Receiving | 1.4x | Taking input (any modality) |
| Ready-with-input | 1.1x | Instruction held, not sent |
| Understanding | 1.7x | Interpreting — concentration, NOT loading |
| Clarification | 1.15x | Needs one more thing. Never an error, never red |
| Acting | 2.0x | Executing; match real duration, never pad |
| Success | one controlled release | Done |
| Speaking | 1.3–1.6x irregular | Responding |
| Error | brief interrupted contraction | Warm amber/peach, never red |

## The Orb is one continuous substance

**Do not write `idleAnimation()` then switch to `thinkingAnimation()`.** Model continuous
properties and let states change their *targets*, then interpolate:

`flowVelocity`, `turbulence`, `convergence`, `directionalBias`, `yellowIntensity`,
`whiteIntensity`, `surfaceIllumination`, `breathingAmplitude`, `scale`, `internalDeformation`

Discrete canned animations make the Orb feel artificial. Continuity is the whole point.

## Orb rules

- The liquid itself communicates state. **Never** put a microphone, waveform, V, brain,
  sparkle, chat bubble, robot, checkmark or spinner inside it.
- Never a loading ring, bouncing dots or ellipsis. Understanding looks like *concentration*.
- Never rotate the whole sphere continuously. The sphere is spatially stable; the liquid moves.
- Opening the Agent must read as the **same entity expanding**, not the Orb vanishing and an
  unrelated sheet appearing. See `docs/REFERENCES.md` on `SharedTransitionLayout` — and
  verify it works across a `ModalBottomSheet` boundary before designing around it.
- Capture success is a signature moment: particle approaches → crosses the boundary → liquid
  curls around it → dissolves → warm light propagates → settle. "Virlin remembered it."

## Split-flap (signature interaction)

Physical, not digital. Never replace with a plain digital timer.

- **Now screen:** per-digit flip — only the digit that changed flips.
- **Fullscreen Focus Clock:** GROUPED — each two-character card flips as ONE surface, even
  when only one character differs. Render the full string once, clip into halves so both
  share one baseline and one horizontal centre. Never collapse a card into a bitmap.
- Sequence: old upper half rotates down about the hinge → new value revealed behind → new
  lower half unfolds → settle. ~500ms, natural easing, **no cartoon bounce**.

## Attention states (Needs You)

Slow arrive → hold → leave. No rolling/travelling border, no diagonal animation, no flashing
text, no bouncing cards. Warm surfaces signal attention; overdue is coral, never red.

## Timing reference

Use `VirlinMotion` in `ui/theme/VirlinTokens.kt` — `FastInteractionMs` 120, state transition
in/out 220/180, `SplitFlapFlipMs` 500, `BreathingMs` 3000, press scales 0.98 (large) / 0.96
(small). Idle breathing is ~1.00→1.012→1.00; it must never read as a pulse.

## Reduced motion & haptics

- Honour the system reduced-motion setting: keep state legible with opacity/colour/text when
  movement is suppressed. Never let reduced motion hide a state change.
- Haptics are punctuation, not decoration: one subtle tick on success. Never on every frame,
  never on idle.
