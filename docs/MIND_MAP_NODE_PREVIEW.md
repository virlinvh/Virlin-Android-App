# Mind map node preview

Status: **implemented** on `feature/mind-map-node-preview`, based on `origin/main` @ `a30d676`
(the merged combined content-workspace baseline).

## Product behaviour

A mind-map node may carry one optional compact preview: a short formatted description, up to seven
images, or both. It is private map decoration — a way to see what a node is about without opening
it — and it is deliberately **not** the task's attached work.

### Resting node

An unselected node is **unchanged from before this feature**: status dot, title and detail subtitle,
exactly as the map already drew it. No caret, no plus, no pencil, no description and no image appear
on a resting node.

> **Deviation from the mockup, deliberate.** The approved image shows title-only nodes. The existing
> implementation draws a status dot and a subtitle (`0/1 tasks`, `To do`) on *every* node, and the
> brief says not to globally remove information from unrelated nodes unless the implementation
> already behaves that way. Applying the title-only treatment would change every node of every map,
> so it was **not** done. It remains available as a separate, explicitly approved change.

### Selected node

A single tap selects, exactly as before. The selected node additionally shows:

- a small **disclosure caret** on the left — right-facing when collapsed;
- a **very faint plus** on the right when it has no preview, or a **pencil** when it has one;
- its existing strengthened border and drag handle.

The existing lower action bar — **Open Task · Move · Page · Add** — is shown unchanged. The
node-level plus is a separate control that opens only the preview editor.

### Expanded node

Tapping the caret expands the node in place: title row, formatted description, then a swipeable
image carousel with pagination dots. There are no editing affordances and no thumbnails in the
read-only preview. Tapping the caret again collapses it to the original dimensions, still selected,
with content intact.

Deselecting the node collapses the preview, because a resting node shows its title only. The
expanded/collapsed preference is still persisted, so the state is restored when the node is selected
again.

## Relationship to the existing Add palette

**The lower Add action is untouched.** It still opens the canonical feature palette (To-do, Note,
Prompt, Link, PDF, Attachment, Image, Audio). The node-level plus/pencil opens only the preview
editor and nothing else. Nothing was renamed, rerouted, replaced or intercepted.

## Relationship to the Task Page

A preview **never** appears on the Task Page and never creates a Page block or a Capture. This was
verified on the emulator: after saving a preview with a description and two images, `captures`,
`task_page_blocks` and `attachment_documents` were all unchanged, and `map_node_previews` held
exactly one row.

Using the Page block model here was rejected on purpose. A Page block is a reference to content the
user attached to a task; a preview is decoration on a map node. Sharing the model would put private
map decoration in the Inbox and on the Page, where it would be indistinguishable from real work.

## Persistence model

Room **v18**, one new table, reserved in `DATABASE_MIGRATION_QUEUE.md`.

```
map_node_previews(ownerKey PK, documentJson, revision, createdAt, updatedAt)
```

`ownerKey` is the node's own stable entity id — the same id `MapTopic.id` carries for a Project,
WorkStream or Task. Nothing is keyed by title, because titles repeat.

The payload is a **versioned JSON document** rather than columns, mirroring `virlin_notes`. Adding a
mark or an image field changes the payload version and the reader, not the schema. JSON is written
and parsed by hand rather than through `org.json`, for the same reason `VirlinNoteCodec` does it:
the `android.jar` stub on the JVM unit-test classpath returns null from every `org.json` call, so a
codec built on it cannot be tested without Robolectric. Decoding is total — a malformed payload
degrades to an empty preview rather than throwing, so one bad row cannot stop a map from drawing.

### Migration

`MIGRATION_17_18` creates one table and touches nothing else. It is purely additive: no column is
added to an unrelated entity, no data is rewritten, and there is no destructive fallback. Proven by:

- `migrate17To18_addsMapNodePreviews_withoutChangingAnythingElse` — seeds a v17 database with a real
  project and a Task Page block and asserts both survive;
- `migrate1To18_longChain_preservesSeededData` — v1 through every migration to v18 with its data;
- `freshInstall_isV18_andNoMigrationNeeded`;
- a **live upgrade on the emulator**: a restored v17 database containing 3 captures, 3 Page blocks
  and 3 attachments was opened by the new build and came up at `user_version = 18` with all rows
  intact.

## Formatting model

Exactly two marks: **bold** and *italic*, which may overlap. No underline, strike, colour, heading,
list, link, quote, code or markdown.

`FormattedText` is plain text plus non-overlapping, ordered, half-open runs. All editing goes
through `FormattedTextEditor`, which rebuilds a per-character mark table and derives minimal runs
from it. With a 180-character cap this is trivially cheap, and it makes the cases that normally rot
— inserting inside a run, deleting across several runs, replacing a selection that starts bold and
ends plain, applying bold and italic in either order — fall out of the same three lines instead of
bespoke index arithmetic.

Typed text inherits the marks of the character to its **left**, so continuing at the end of a bold
word stays bold; at position 0 there is nothing to inherit and the text starts plain. With no
selection, pressing B or I arms the mark for the next characters.

The Notes block document was deliberately **not** reused: a 180-character map caption has a
different lifecycle from a rich document, and `NoteDoc` is compatibility-sensitive because Prompt
depends on it.

## Image lifecycle

Preview images are **not** Attachments. They live in their own managed root,
`filesDir/map_previews/{ownerId}/`, referenced only by **relative** path.

- Import decodes bounded (max 1280 px edge), rejects unreadable, zero-byte and undecodable sources,
  and deletes the partial file on every failure path.
- The seven-image cap is enforced in `MapPreviewActions`, not only by hiding the add tile.
- **Replace** keeps the old file until the new record commits, then deletes it. A failed save can
  never leave the record pointing at bytes that are already gone.
- **Delete** removes only that image's entry and bytes; siblings are untouched.
- **Cancel** deletes exactly the files imported during that editing session and leaves the saved
  preview alone.
- **Clear** empties the description and all images and removes the owner's whole directory.
- A missing or unreadable file renders an honest "no longer available" placeholder rather than an
  empty box.
- **No bitmap handed to Compose is ever recycled.** `asImageBitmap()` wraps without copying, and a
  RenderNode display list can replay after the composable leaves — that exact mistake crashed the
  Image workspace. The only `recycle()` is on an import-time bitmap that is never shown.

## Gesture priority

The map is drawn into a **single raw `android.graphics.Canvas`** with no per-node composables, by
design ("a map with hundreds of tasks costs no composables"). That shaped the whole implementation.

1. **Pan and zoom** are unchanged — `detectTransformGestures` is not touched.
2. **A first tap always selects.** The caret and plus/pencil belong to the node that is *already*
   selected, so they can never swallow a tap aimed at a different node, and selection stays the
   cheapest gesture on the map.
3. `selectedId` is a `pointerInput` **key**, not merely captured. A detector built before the
   selection existed compares against a stale `null` and never fires the controls — this was a real
   bug found on the emulator, and it is the same staleness the pan/zoom handler already guards
   against.
4. **No double-tap was added.** Adding `onDoubleTap` to the map's `detectTapGestures` would delay
   *every* node selection by the double-tap timeout. Title editing already exists through
   `MapRenameDialog`, wired to the canonical `actions.updateTask`, so nothing new was needed and
   nothing was destabilised.
5. Tapping blank canvas still deselects.
6. The drag handle and the lower action bar still own their own pointer sequences.

## Rendering and connector reflow

Only the **selected** node can be expanded, so at most one expanded node exists. It is rendered as
**one Compose overlay** positioned at the node's screen rect, while `paintMap` skips that node.
This keeps the Canvas fast while giving real bold/italic spans, real image loading, a real
`HorizontalPager` carousel, real 48 dp touch targets and real accessibility semantics — none of
which could be drawn into the Canvas without reimplementing text layout and paging by hand.

Connectors genuinely reflow because `arrange()` takes the expansion: the node's frame grows by the
preview's height and every node below it is pushed down, so an expanded node never overlaps its
neighbours and its connector meets the taller box. Layout is still computed once per
hierarchy/template/expansion change, never on a pan or zoom frame.

Because the Canvas no longer draws the expanded node, the caret and pencil are rendered **inside**
the expanded card. Controls painted on the Canvas would be invisible and unhittable there — found
and fixed during emulator acceptance.

## Popup editor

A compact Material 3 card over the map, scaled and faded in with a spring, anchored centrally with
the node's title shown so the context is never ambiguous. Android Back dismisses it, and a
full-surface scrim both dims and swallows touches, so the map, action bar, zoom controls, Orb and
bottom navigation are visibly and functionally behind it.

> **Blur approximation, documented.** `RenderEffect` blur needs API 31+ and costs a full-surface
> render pass every frame on a map that may be mid-animation. A strong scrim is used instead. This
> is a deliberate deviation from the mockup's frosted background, chosen to avoid frame drops.

Header: **Preview details**, an icon-only clear (a distinct `LayersClear` glyph — deliberately not a
second X, since clearing and closing must not share an icon) and a close X. Clearing requires
confirmation and the dialog states explicitly that the task, its Page and its attachments are not
affected.

Description: max 180 characters, about two lines growing to about five then scrolling, a character
counter, and compact B/I controls in the lower-left of the field — low-transparency until selected,
then pale mint with an emerald glyph.

Images: one large active image with frosted icon-only **Replace** (lower-left, muted) and **Delete**
(lower-right, muted red), a horizontal thumbnail strip, an add tile that disables at seven, and an
`n / 7` counter. Order follows addition order; there is no drag reordering in v1.

Footer: **Cancel** and **Save** only. Save commits description and image metadata atomically and
deletes a preview that has become empty rather than storing a meaningless row.

## Accessibility

- Every icon-only action has a content description, and they distinguish *delete this image*,
  *clear this preview* and the task's own destructive actions.
- Touch targets are at least 32–44 dp around small glyphs; the lower action bar keeps its 48 dp.
- B and I expose `ToggleableState` and a state description, with `Role.Switch`.
- Carousel pages announce "Image n of m"; the caret announces its expanded state.
- The popup traps touches and supports Back.

## Performance

- Collapsed previews decode **nothing**; only the expanded node and the open editor load images.
- Decoding is bounded at 1280 px and happens on `Dispatchers.IO` through `produceState`.
- The editor draft lives in the host, not the map, so typing never recomposes the graph.
- At most one overlay composable exists, so node count still does not create composables.
- Layout is recomputed only when the hierarchy, template or expansion changes.

## Tests

**47 new JVM tests**, all passing:

- `MapNodePreviewTest` (31): stable-id ownership and cross-node isolation, blank owner rejection,
  empty previews not stored and existing ones removed, the 180-character cap, bold/italic survival
  across insert, delete and replace, deterministic bold+italic overlap, mixed-selection toggling,
  run normalisation and merging, the seven-image cap and eighth-image rejection, duplicate ids and
  path-less images rejected, stable order, replace-in-place, delete-one, clear, save atomicity,
  codec round-trip, malformed payloads, path-less entries dropped, cap re-applied on read, and the
  durable expanded flag.
- `MapPreviewDraftTest` (16): add/append order, the cap, replace keeping position and queuing the
  old file, delete selecting the nearest remaining, deleting a session import removing it from
  cleanup, clear queuing every file, cancel cleanup containing exactly the session's imports,
  inheritance rules for typed text, toolbar state for mixed selections, and the counters.

**Migration (instrumented, 17/17 pass on the emulator):** the three migration tests above plus the
whole existing suite.

## Emulator acceptance

Pixel 8 AVD, API 35, upgrade install over existing data in a disposable `PreviewTest` project.

| Item | Result |
|---|---|
| Upgrade install, existing projects/maps intact | **Pass** — v17 data upgraded in place to v18 |
| Resting node shows no new controls | **Pass** |
| Selecting shows caret + faint plus | **Pass** |
| Existing lower action bar unchanged | **Pass** — Open Task · Move · Page · Add |
| Node-level plus opens the popup | **Pass** (after fixing the stale `selectedId` key) |
| Background dimmed, popup sharp | **Pass** (scrim approximation) |
| Description typing, auto-grow, counter | **Pass** — 64 / 180, grew to two lines |
| Bold and italic apply and render | **Pass** |
| Add image, second image, counters | **Pass** — 1 / 7 then 2 / 7 |
| Replace and delete overlays present | **Pass** — frosted, lower-left and lower-right |
| Save persists | **Pass** — one row, correct runs, relative paths |
| No Capture / Page block / Attachment created | **Pass** — all three counts unchanged |
| Expand shows description + carousel + dots | **Pass** |
| Carousel swipe advances the dot | **Pass** |
| Collapse returns to original size | **Pass** (after moving controls into the card) |
| Restart persistence | **Pass** — pencil and content restored after force-stop |
| No crash, ANR, black frame, recycled-bitmap error | **Pass** — 0 FATAL, 0 recycled-bitmap |

## Known limitations

1. **Resting nodes keep their status dot and subtitle**, unlike the mockup. Deliberate; see above.
2. **Background is dimmed, not blurred.** Deliberate; see above.
3. **The existing green drag handle overlaps the expanded card's text**, because it is positioned at
   the node's centre and the expanded node is taller. Cosmetic, not functional.
4. **The B/I controls move down as the description field grows**, since they sit inside the field as
   the mockup shows. Correct per the design, but worth knowing.
5. **Reflow is tuned for the tree layouts.** `RIGHT_TREE`, `BALANCED` and `BRANCH_LANES` reflow
   cleanly; `TOP_DOWN`, `RADIAL` and `COMPACT_OUTLINE` use the row value differently and were not
   exercised with an expanded node.
6. **No drag reordering of images** in v1; order is addition order.
7. **Title editing was not changed.** It already exists through `MapRenameDialog`; no double-tap was
   added, by design.
8. **No instrumentation UI tests were added for the preview itself.** Acceptance was manual on the
   emulator and is reported as such. The migration tests are instrumented and do pass.
9. **A preview is not deleted when its node is deleted.** The row is keyed by the node id and simply
   stops being read. A future pass should clean orphans.

## Extending this

1. Keep the payload versioned — add a field and bump the payload version, do not add a column.
2. Keep caps and ownership checks in `MapPreviewActions`; the UI is not a safety boundary.
3. Never give preview images a Capture or a Page block.
4. Never recycle a bitmap that has been handed to Compose.
5. If a control must appear on an expanded node, put it in the overlay card — the Canvas does not
   draw that node.
