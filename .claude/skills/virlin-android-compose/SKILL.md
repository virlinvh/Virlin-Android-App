---
name: virlin-android-compose
description: Virlin-specific Jetpack Compose implementation rules — preserving approved UI, unidirectional state and StateFlow, performance-sensitive animation, semantics/testTag practice, surgical modification discipline, and when to reach for an Android skill or Context7. Load ONLY when writing or modifying Virlin Compose code. Do not load for pure motion design, QA, or product questions.
---

# Virlin — Compose Implementation Rules

Load on demand. Product rules live in `virlin-project`; motion in
`virlin-motion-interaction`; verification in `virlin-mobile-qa`.

## Before writing code

1. **Graphify first** — `graphify query "..."` / `explain "<symbol>"` to locate symbols and
   dependencies. Read only the exact files you need. Grep only when the graph is insufficient.
2. **Android skill** — load the ONE relevant official skill (`testing-setup`, `adaptive`,
   `edge-to-edge`, `android-cli`). Never load several; never load them speculatively.
3. **Context7** — for external/current API docs (Compose, Material, Navigation, Room, Hilt,
   WorkManager, testing libs) via the `find-docs` skill or `npx ctx7 docs`. **Never** use
   Context7 for Virlin's own code, symbols or product decisions.

## Surgical modification discipline

- Change only what the request requires. No opportunistic refactors, no cleanup runs.
- Prefer the smallest safe change. Do not rewrite working components to make them tidier.
- Approved surfaces are frozen: Now layout, Current Focus, split-flap timer, Needs You,
  Working For You, When You're Free, the Orb, bottom navigation. Touch them only when the
  request explicitly targets them.
- If shared code must serve a new surface, make the new behaviour **opt-in via defaulted
  parameters** so existing call sites render identically. This is how the fullscreen Focus
  Clock reuses the timer without changing Now.

## State

- One source of truth. Never duplicate Focus timer, WorkStream state, reminders,
  processing timestamps or FocusSession.
- Unidirectional: state flows down, events flow up through the domain/repository layer.
  UI must not mutate persistence directly.
- Surfaces **observe** shared state — `MockData.streams.collectAsState()` today, Room/Flow →
  Repository → ViewModel → StateFlow later.
- Persist timestamps as the source of truth. A UI counter is never authoritative time.
- A second presentation of existing state must own **no** timing state of its own.

## Animation performance

- Keep animation local. Never let an animated leaf trigger recomposition of a whole screen.
- Read animated values at **draw time**, not composition time — lambda providers into
  `drawWithContent` / `graphicsLayer`, not `by state` read in the composable body.
- No per-frame allocation. Hoist `Path`, `Brush`, `Shape` into `remember` / `drawWithCache`.
- `rememberInfiniteTransition` never goes idle — that has real test consequences, see
  `virlin-mobile-qa`.
- **Stitch Orb (2026-09-13) is the approved Orb**: `ui/components/VirlinOrb.kt` renders the Stitch
  design's liquid shader ported to AGSL (`RuntimeShader`, API 33+; construction/draw
  `IllegalArgumentException` → Canvas fallback, keep that boundary narrow). The Canvas fallback is the previous
  52dp calm liquid-glass sphere — glass body, three independently drifting
  internal liquid bodies (mint ~11 s · natural green ~14 s · pale mint ~17 s) + a tiny pale-yellow
  accent clipped inside the sphere, thin rim, soft reflection, faint green glow (alpha ≤ 0.3). No
  shader, no bitmap, no library, no per-frame allocation beyond brushes. The whole sphere never
  rotates. **Do not modify its shader, palette, geometry or motion unless a pass asks.**

## Semantics & testTags

- Expose a composite control as ONE meaningful node. Use `clearAndSetSemantics` (split-flap
  timer) or `semantics(mergeDescendants = true)` (Orb) — never leak child layers.
- Give interactive custom-drawn elements a real `contentDescription` and, where a gesture is
  handled by `pointerInput`, an explicit `onClick` semantics action so tests can drive it.
- Stable identities belong in constants next to the component, e.g. `VirlinOrbTestTag`,
  `VirlinOrbContentDescription`.
- Semantics are non-visual and always acceptable to add to a frozen surface.
- Minimum 48dp touch targets. Visual size may be smaller than the touch target.

## Design tokens

`ui/theme/VirlinTokens.kt` is the canonical home for approved values (`VirlinColors`,
`VirlinSpacing`, `VirlinShapes`, `VirlinMotion`). **Nothing is migrated yet** — the approved
colours still render from package-level vals in `NowScreen.kt`.

- New code: reference the tokens.
- Existing frozen code: do **not** bulk-migrate. Migrate one surface at a time, and only with
  explicit approval, verifying the Roborazzi golden still passes unchanged after each step.

## Living Orb interaction — how it is wired (durable)

- ONE owner: `VirlinAgentViewModel` (`StateFlow<VirlinOrbInteractionState>`). UI sends
  events; never hold a `mutableStateOf` copy of interaction state. All timed transitions go
  through its single `transientJob`; `dismiss()` cancels it. Add new transients there, never
  as `LaunchedEffect { delay(); state = … }` in a composable.
- Renderer is parameter-driven: state → `toOrbParameters()` → `VirlinOrb(params)`. Add a
  new behaviour by adding a parameter the renderer can honour, not a new Orb composable.
- The liquid's rate is **integrated into three continuous phases** (`withFrameNanos`, eased
  `motionMultiplier × VirlinOrbState.speed`); never reset or re-seed a phase on a state change —
  it jumps the liquid. States only change speed and glow, never the palette.
- Agent entry: the sheet opens on `AgentEntryPicker` ("How can I help?" · Control / Create /
  Capture rows) until `AgentWorkspaceUiState.modeChosen`; rows reuse `agentModeTag(mode)` and
  `onModeSelected`. Never add a second Agent surface for this step.
- One Orb only. `OrbTravelLayout` in `VirlinApp` places it from the Now slot toward the Agent
  slot; the Agent is an in-window sheet for that reason. Do not reintroduce
  `ModalBottomSheet` for the Agent — it breaks continuity.
- One-shot visuals (Success/CaptureSuccess) are keyed on `oneShot` + nonce so repeats are
  distinct and a state change cancels them mid-flight.

## Agent workspace shell — how it is wired (durable)

- Workspace UI state (`AgentWorkspaceUiState`) lives in the same `VirlinAgentViewModel` as
  the Orb state. Add workspace behaviour there; never a `mutableStateOf` in the shell.
- ONE `UniversalComposer` for CONTROL/CREATE/CAPTURE. New input kinds are `InputObject`
  variants + a `CaptureType`, not new composers. A Prompt is structured text, never a chip.
- Clarification and receipts are pinned **above the composer, outside the scroll** so they
  stay visible with the keyboard open. A receipt the user cannot see is not a confirmation.
- The shell is scroll-content + pinned composer; the sheet is `max(60%, 440dp)` so the
  primary action survives `adjustResize`. Do not put the composer inside the scroll.
- No transcript, no chat bubbles. Receipts are temporary and clear on the next interaction.
- Selection chips must set `semantics { selected = … }`; every attachment must have a
  readable `contentDescription`.

## Compose specifics that have bitten this project

- `Modifier.offset` moves an element visually but Compose does **not** deliver pointer events
  outside the parent's bounds — an offset child can look right and be untappable. Fix by
  giving the parent bounds that include the child (e.g. a sized slot zone), not by hacks.
- To position an overlay from another node's coordinates with **no frame lag**, read the
  anchor via `onPlaced` into a plain holder and place the overlay in the same `Layout`
  placement pass. `onGloballyPositioned` → state → recompose costs a frame, which also breaks
  frozen-clock screenshot tests.
- Default parameter values can reference earlier parameters; use that to derive scale-dependent
  metrics rather than duplicating a component.
- Navigation destination transitions are per-composable; setting NavHost defaults affects
  every route.

## Hierarchy screens (Pass 2)

- `HierarchyViewModel` reads the repository only; the ONLY UI-owned state is the set of expanded
  parent task ids. Never store progress, ancestry or the current task in UI state.
- `HierarchyPresentation.rows()` flattens the visible tree (O(n)); indent caps at depth 3 so deep
  trees stay usable on a phone — depth itself is unbounded.
- Instrumented tests: `performScrollTo()` animates the LazyColumn — pump the clock before the
  click; lazy items not yet composed can't be found by tag, so narrow with a Streams filter first.

## Tap lambdas in reordering lists (Passes 4–8 lesson)

- Never `pointerInput(Unit)` (or keyed only on a mode) inside a card that can be re-bound to a
  different stream: `detectTapGestures` captures the lambda at first composition. Key it on
  the entity id (`pointerInput(stream.id, kind)`), or prefer `clickable`, which re-reads its
  lambda on every recomposition.
