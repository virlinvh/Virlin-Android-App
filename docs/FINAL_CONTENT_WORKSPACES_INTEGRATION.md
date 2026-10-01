# Final content-workspace integration

Date: 2026-10-01
Integration branch: `claude/final-content-workspaces`
PR base: `main` @ `0380d92` (verified current at integration time; no new remote commits to analyse)

## Source branches and commits

| Source | Head | Role |
|---|---|---|
| `feature/attachment-workspace` | `a1a5a44` | Non-Image combined baseline (To-do, Notes + legacy compatibility, Prompt, Link, Task Page foundation, PDF, Audio, Attachment, corrected documentation, non-Image readiness audit) |
| `codex/image-workspace` | `b96f998` | Image workspace implementation |
| `feature/image-emulator-fix` | `da60ce8` | Recycled-bitmap crash fix |

Verified before integrating: `b96f998` is an ancestor of `da60ce8`; neither is contained in
`feature/attachment-workspace`; `fe7eb90` is already an ancestor of both, so it was not re-applied;
both branches were strictly ahead of `origin/main` (0 commits main-only), so no rebase was needed;
all agent worktrees were clean apart from one untracked screenshot in Codex's tree, which was not
touched.

## Integration method

A fresh worktree at `C:\tmp\virlin-final-pr` was created from `a1a5a44`. The two Image commits were
applied as explicit cherry-picks in order, preserving them as separate authored commits:

```
d671239 fix(image): stop recycling bitmaps the Compose layer still draws
5b92601 feat(image): add non-destructive image workspace
a1a5a44 docs: align non-image PR readiness baseline
```

No branch was reset, rebased, squashed, force-pushed or deleted. No agent worktree was modified.

## Conflict resolutions

Only three files conflicted, all documentation or append-only logs. **No production code
conflicted.** Nothing was resolved with a wholesale `ours` or `theirs`.

| File | Resolution |
|---|---|
| `CODEX_CHANGES.md` | Both Codex entries kept verbatim in chronological order (the log appends newest last). No entry rewritten. |
| `CLAUDE_CHANGES.md` | Both Claude entries kept verbatim in chronological order. No entry rewritten. |
| `docs/FEATURE_REGISTRY.md` | `a1a5a44`'s newer, more precise non-Image rows kept exactly; only the Image row was taken from the Image commit, restyled into the baseline's own column convention. Image now reads **Implemented**. |

## Final architecture

- **Room stays at v17.** No schema JSON added or modified, no destructive migration fallback, no
  DAO access from Compose UI.
- **Task Page `capture.file` dispatch is kind-based and ID-based**, in `TaskPageScreen.kt`:
  `AttachmentKind.PDF` → PDF workspace, `AttachmentKind.IMAGE` → Image workspace, everything else →
  the universal File viewer. `capture.voice` still opens Voice. Unknown type keys still fall through
  to `onUnsupported`. Nothing routes by filename.
- **Image owns no new persistence.** One imported or saved image is one `CaptureItem(FILE)` plus one
  `AttachmentDocument(kind = IMAGE)`, with its `capture.file` Page block registered by
  `CaptureActions` in the same transaction — which is why Page blocks stay 1:1 with content.
- **No UI-layer `Bitmap.recycle()` remains after `asImageBitmap()`.** The two recycles left in
  `ImageRenderEngine` operate on internal intermediates that are never handed to Compose, and that
  separation is pinned by a test.

## Defects found and fixed during this integration

### 1. Recycled-bitmap crash (inherited fix, `da60ce8`)

Importing an image succeeded and the library briefly showed its count before the app vanished to the
launcher, with `RuntimeException: Canvas: trying to use a recycled bitmap` in
`BitmapPainter.onDraw`. `asImageBitmap()` wraps a Bitmap without copying, and a RenderNode display
list can replay after `onDispose`, so recycling in a `DisposableEffect` freed pixels Compose still
intended to draw. Full analysis in `IMAGE_EMULATOR_AUDIT.md`.

### 2. Back-navigation trap in the Image editor (found and fixed here)

**Found during this acceptance pass, on the integrated branch.** Opening an IMAGE block from the
Task Page routes to `image_workspace/capture/{captureId}`, which starts in `EDIT` because it
addresses one capture directly. The original handler read:

```kotlin
if (state.mode == LIBRARY) navController.popBackStack()
else vm.setMode(if (captureId != null) EDIT else LIBRARY)
```

In that route back set the mode to `EDIT` while already in `EDIT` — a self-loop, so `popBackStack()`
was never reached. Because the same handler served the top-bar arrow and the `BackHandler`, neither
the arrow, the system Back button nor the edge-swipe gesture could leave the editor. Four
consecutive back presses were observed with no change, from a fresh process, in verified portrait.

The fix extracts a pure `imageBackAction(mode, hasCaptureOwner)` into `ImageWorkspaceContract.kt`:
CROP and MARKUP step back to EDIT; EDIT returns to the library only when a library exists, and
otherwise exits the screen; LIBRARY exits. Verified on the emulator: Page → editor → Back now
returns to the Page.

Both guards were proven **in both directions** — each fails when its defect is reintroduced and
passes when it is not. A guard that cannot fail proves nothing.

## Tests and counts

| Check | Result |
|---|---|
| `:app:testDebugUnitTest` | **1,105 tests, 0 failures, 0 errors** |
| `:app:compileDebugAndroidTestKotlin` | pass |
| `:app:assembleDebug` | pass |
| `:app:connectedDebugAndroidTest` (`ImageRenderEngineTest`) | **2/2 pass on the emulator** |
| `:app:verifyRoborazziDebug` | exactly **17** documented differences; **0 golden PNGs changed** |

The count reconciles exactly: 1,091 (non-Image baseline) + 7 `ImageWorkspaceContractTest`
+ 2 `ImageBitmapLifecycleTest` + 5 `ImageBackNavigationTest` = **1,105**. Nothing was lost in the
merge.

Focused suites all green: `ImageWorkspaceContractTest` (7), `ImageBitmapLifecycleTest` (2),
`ImageBackNavigationTest` (5), `AttachmentWorkspaceTest` (22), `AttachmentCaptureTest` (5),
`AudioWorkspaceTest` (12), `PdfWorkspaceContractTest` (4), `DeterministicOnlyTest` (8).

Hygiene: `git diff --check` clean, no schema JSON changed, no secrets, no machine-local paths, no
generated or emulator artefacts staged.

## Emulator acceptance matrix

Pixel-class AVD, API 35, `-gpu swiftshader_indirect`. All work was done in a **disposable
`ImageAudit` project** created for this pass; no real project was navigated into or modified, and
emulator application data was never cleared. A database baseline was captured first
(`preimageaudit-20261001-205121`).

| Area | Item | Result |
|---|---|---|
| Entry | Mind map task → Add → Image | **Pass** — Image enabled in the palette |
| Entry | Correct task ownership label | **Pass** — "Attached to · ImageTask" |
| Entry | Empty library | **Pass** |
| Import | Single import | **Pass** |
| Import | Multi-select import | **Pass** — both thumbnails render, same PID, 0 crashes |
| Import | Cancel picker | **Pass** — returns cleanly, no leaked overlay |
| Import | Imported thumbnails | **Pass** |
| Import | Back and reopen persistence | **Pass** |
| Import | Force-stop / relaunch persistence | **Pass** — all blocks intact under a new PID |
| Import | No crash, ANR, black screen or leaked overlay | **Pass** |
| Adjust | Brightness, Contrast | **Pass** |
| Adjust | Saturation (94), Warmth (−76) | **Pass** — visibly applied |
| Adjust | Original / Vivid / Warm / Mono | **Pass** — all four visibly distinct |
| Adjust | Undo, Redo, Reset | **Pass** — redo restores exactly one step |
| Transform | Crop edges | **Pass** |
| Transform | Rotate, Flip H, Flip V, Straighten | **Pass** — verified by image orientation |
| Transform | Preview valid, no recycled-bitmap error | **Pass** |
| Markup | Pen | **Pass** |
| Markup | Translucent highlighter | **Pass** — gradient visible through the stroke, unlike the opaque pen |
| Markup | Rectangle, Arrow, Text | **Pass** |
| Markup | Colour, stroke width, opacity | **Pass** — 40% highlighter default vs 100% for other tools |
| Markup | Undo / redo markup | **Pass** |
| Markup | Clear | **Pass** |
| Save | Save copy | **Pass** |
| Save | Original remains unchanged | **Pass** — source still byte-exact at 203,945 B |
| Save | Saved output is a valid PNG | **Pass** — `89504e47` magic on disk |
| Save | Saved copy appears in library | **Pass** |
| Save | Exactly one Page block per saved output | **Pass** — 13 blocks / 13 distinct contentIds |
| Save | Opening an IMAGE Page block returns to the Image workspace | **Pass** |
| Save | Restart persistence | **Pass** |
| Errors | Zero-byte image fails safely | **Pass** — named honestly, creates no row |
| Errors | Corrupt image fails safely | **Pass with a caveat** — see limitations |
| Errors | Partial multi-import preserves valid siblings | **Pass** — "Added 2 images" plus a named failure |
| Errors | Partial managed directories cleaned | **Pass** — every managed directory holds exactly one file |
| Errors | Navigating away during import does not crash | **Pass** — blocks still 1:1 afterwards |
| Errors | Activity recreation does not reproduce the bitmap crash | **Pass** — rotation both ways, same PID, 0 recycled-bitmap errors |
| Routing | PDF Page block still opens PDF | **Pass** |
| Routing | Generic file still opens the universal viewer | **Pass** — text rendered |
| Routing | Audio still opens Voice | **Pass** |
| Routing | Prompt, Link, Note, To-do open their canonical interfaces | **Pass** |

Emulator screenshots were captured as working evidence and deliberately **not** added to Git.

## Known limitations

1. **A corrupt image is accepted rather than refused.** A file with a valid image MIME type and a
   non-zero size passes the import guard even when its bytes are undecodable; it is stored and shown
   as a placeholder tile. It fails safely — no crash — but it is not rejected at import. Whether to
   decode-validate on import is a product decision and was left alone rather than changed under a
   merge.
2. PDF durable annotation and OCR remain honest capability gates.
3. Attachment office previews are read-only bounded extracts, not faithful layout.
4. Archives are stored and described, never extracted.
5. Audio v1 is recorded Voice; imported audio stays an Attachment with `AttachmentKind.AUDIO`.
6. Multi-select import is sequential, not parallelised.
7. The 17 Roborazzi differences are the pre-existing documented baseline and remain under review.
   No golden was recorded in this work.

## Final branch and commits

Branch: `claude/final-content-workspaces`

See the PR for the final commit list, hashes and URL.
