# Image workspace — emulator audit and repair

Date: 2026-10-01
Branch audited: `codex/image-workspace` @ `b96f998`
Fix branch: `feature/image-emulator-fix` (isolated worktree, created from `b96f998`)
Device: emulator, Pixel-class AVD, `-gpu swiftshader_indirect`

## 1. Reported symptom

After tapping a file in the document picker, the emulator showed the launcher instead of the
imported image library. Six causes were considered: a real crash, process death, result
restoration failure, malformed URI handling, an automation tap error, or a DocumentsUI problem.

## 2. What the evidence actually showed

The symptom is a **real application crash**, but **not in the picker and not in import**.

Reproduced twice. Logcat captured from before the triggering tap:

```
java.lang.RuntimeException: Canvas: trying to use a recycled bitmap
    at androidx.compose.ui.graphics.painter.BitmapPainter.onDraw
```

Two facts rule out every picker-related hypothesis:

- The library momentarily rendered **"Saved images · 1"** before the app disappeared. The import
  had therefore already completed and the record had already been persisted.
- The crash frame is a Compose draw frame, not a picker callback or a URI read.

The import pipeline was audited line by line and **no defect was found** in it.
`ImageWorkspaceViewModel.importImages` returns early on an empty URI list (a cancelled picker),
wraps each file in `runCatching`, deletes the orphan directory on every failure path, validates
kind and size, and resets `importing` before refreshing. The process never died: the PID was
identical before and after, and `MainActivity` remained the resumed activity.

## 3. Root cause

`ImageWorkspaceScreen.kt` recycled bitmaps it had already handed to Compose:

```kotlin
DisposableEffect(bitmap) { onDispose { bitmap?.takeIf { !it.isRecycled }?.recycle() } }
```

`asImageBitmap()` wraps a `Bitmap` **without copying it**. A RenderNode display list can be
replayed after `onDispose` has run, so the recycle freed pixels Compose still intended to draw.
Since `minSdk` is 26, bitmap memory lives on the Java heap and is reclaimed by the garbage
collector, so an explicit recycle in the UI layer was never necessary.

## 4. The fix

Two `DisposableEffect { … recycle() }` blocks removed — one in `EditPreview`, one in
`ManagedBitmap` — each replaced by a comment recording why recycling there is unsafe. **No other
production code was changed.** Two recycles in `ImageRenderEngine` were examined and deliberately
left alone: they operate on internal intermediates that are never passed to Compose.

Post-fix on the emulator: `PID_before 9145`, `PID_after 9145`, activity
`com.virlin.app/.MainActivity`, **0 crashes**.

## 5. Automated coverage

`ImageBitmapLifecycleTest` (new, 2 tests) is a source-level guard in the idiom the repository
already uses for `DeterministicOnlyTest`. It fails if any non-comment `.recycle()` reappears in
the screen that renders bitmaps, and pins the render engine as the one place that may recycle by
asserting it never calls `asImageBitmap()`.

The guard was verified **in both directions**: reintroducing a single `bitmap?.recycle()` makes it
fail, and removing it makes it pass again. A guard that cannot fail would prove nothing.

Codex's existing `ImageWorkspaceContractTest` (7 tests) continues to pass unchanged.

## 6. Regression verification

| Check | Result |
|---|---|
| `:app:testDebugUnitTest` | pass |
| `:app:compileDebugAndroidTestKotlin` | pass |
| `:app:assembleDebug` | pass |
| `:app:verifyRoborazziDebug` | exactly **17** pre-existing diffs, unchanged; **no golden recorded** |
| Room schema | still **v17**, untouched |
| `git diff --check` | clean |

## 7. Emulator acceptance — verified

- Image entry in the palette opens the workspace
- Empty library state, then populated library
- Import via the document picker completes
- Thumbnail renders (this is the step that used to crash)
- Editor opens on a saved image
- Brightness and Contrast adjust the live preview; undo becomes enabled
- **Save copy** writes a new PNG and confirms with "Edited copy saved to Page"
- Library count goes 1 → 2; the **original is unchanged** (146.9 KB) beside the edited copy (84.1 KB)
- The Task Page shows exactly **two** `capture.file` IMAGE blocks — **no duplicate block** for one save

## 8. NOT TESTED — explicitly unverified

These acceptance items were **not** exercised and must not be read as working:

- Saturation and Warmth adjustments
- The Original / Vivid / Warm / Mono presets
- Crop, Rotate, Horizontal flip, Vertical flip, Straighten
- Markup: Pen, Highlighter translucency, Rectangle, Arrow, Text, and the colour / stroke /
  opacity controls
- Undo / Redo beyond the single undo-enabled observation, and Reset
- Back-out-of-editor source immutability
- Multi-select import (only single import was exercised)
- Back-and-reopen persistence, force-stop persistence, restart persistence
- Opening the Page IMAGE block to return to the Image workspace
- PDF / generic attachment / audio routing left unchanged (reasoned about, not re-exercised)
- Cancelled picker, corrupt or unsupported image, navigate-away-during-import
- Rotation / activity recreation, leaked spinner, stranded directory
- Connected (instrumented) Image tests

## 9. Scope

Nothing was pushed, merged, rebased or reset. No branch was deleted, no golden re-recorded, no
Room change made, and emulator application data was not cleared. Work was done in an isolated
worktree, never in another agent's dirty tree.
