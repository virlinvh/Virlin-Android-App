# Link feature contract

Verified against the live workspace: 2026-09-30

Link is a Capture-backed full-page workspace. It validates normalized HTTP(S) URLs and stores one
canonical `CaptureItem(LINK)` whose encoded `LinkDocument` contains presentation choices, optional
preview data and video timing. Task/project attachment is capture context.

Supported presentation categories are YouTube, Instagram, generic video and website. Preview display
is user-controlled. Preview retrieval/display must never alter the original destination URL. Opening
the link uses an external intent. YouTube may play inline through the restricted official iframe
boundary and honors the stored start/end segment; unsupported providers fall back to external open.

The WebView boundary is confined to YouTube embed URLs, blocks unrelated navigation and is destroyed
with the composable lifecycle. Internet permission exists for previews and this player only; Link is
not a general browser.

Task-scoped Link captures register as `capture.link` Page blocks and reopen the same editor by capture
ID. No link thumbnail is rendered as a mind-map node.
