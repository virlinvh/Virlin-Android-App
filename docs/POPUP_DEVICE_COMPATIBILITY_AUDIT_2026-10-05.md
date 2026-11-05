# Popup device compatibility audit — 2026-10-05

## Reported issue and verified cause

The Galaxy S21 screenshot shows Project settings extending into the device navigation area. The screenshot alone cannot establish the phone's Android version, font/display scaling, or navigation configuration; no physical Samsung device was connected during this audit.

Source inspection found the custom settings dialog opted out of platform default width, imposed a 420dp minimum height, and used fixed 42dp vertical spacing without a dialog-window-specific safe viewport. Activity Scaffold/IME padding does not protect a separate dialog window. In this Compose version, the non-default-width dialog can measure against configuration screen dimensions instead of the actual available window frame. Three-button navigation, cutouts, keyboard and reduced display height expose the mismatch.

Live reproduction: after changing only the test emulator to 720×1280 at 320dpi (360×640dp) and three-button navigation, the old non-default-width measurement still placed popup actions under the navigation controls, even with decor fitting or Compose safe padding alone. Switching to platform-constrained measurement and native inset fallback removed the overlap. This is why passing unit tests and a tall gesture-navigation emulator was insufficient.

## Fixes

- `SafeDialog.kt` centralizes custom popup hosting: platform-constrained width, bounded height, centered placement, safe system/cutout/keyboard padding, and 16dp outer breathing room. It unions Compose safe-drawing insets with native dialog/owner window insets instead of assuming a particular navigation-bar height. Native inset state refreshes on layout, with listeners removed on disposal.
- Keyboard handling requests resize rather than pan. Existing Back, outside-tap and secure-window policies are preserved; the custom full-window layout explicitly handles outside-card taps. Longer plain forms opt into vertical scrolling; internally scrolling/lazy forms retain their own bounded scrolling.
- Project settings removes its rigid minimum height. Category content remains an independently scrollable lazy list between header and footer. Footer buttons reserve at least 48dp height and use compact horizontal padding.
- All direct custom Dialog call sites found in app UI now route through the shared host: settings, work/workstream controls, name entry, focus switching, delegation, external detail, Now choice/completion, note pickers/preview and mind-map child creation.
- Task execution and move sheets now scroll their contents; an unbounded move destination list could previously leave lower choices unreachable. Activity date selection also scrolls on short/landscape windows.
- Task-page block move/duplicate destination lists are bounded and scrollable inside their existing Material alert.

## Coverage matrix

| Popup family | Audit result / action |
| --- | --- |
| Project settings and category subpages | Shared native-inset host; minimum height removed; list and fixed actions preserved |
| Work/workstream controls, embedded task-add step | Shared host; body remains independently scrollable and height-bounded; no outer nested scroll around animated steps |
| Project/workstream names, delegation, focus switching | Shared host; plain forms scroll to actions |
| Task/subtask creation in main pages | Existing in-content centered overlay, shell navigation and IME padding retained; no separate dialog-window sizing |
| Map add-child form | Shared host; existing text-first card scrolls within bounded viewport |
| Now/external choice and completion dialogs | Shared host with scrolling; intent callbacks unchanged |
| Notes pickers and preview | Shared host; existing bounded internal scrolling retained |
| Project icon, tags, activity filters, knowledge move, inbox/attention and map sheets | Material bottom-sheet hosting retained; existing platform/system inset behavior not disabled; long content uses existing lazy/vertical scrolling, with execution/move/date exceptions fixed above |
| Attachment options | Material bottom sheet retained; short action list, no manual negative offsets or navigation inset suppression found |
| Delete/rename/confirmation/message alerts throughout apps | Standard Material alert hosting retained; no custom fullscreen/inset override found; block destination alert explicitly hardened |
| Dropdowns (placement, execution, dates and menus) | Existing anchored Material dropdowns retained; no manual fullscreen window or system-nav suppression found |
| Fullscreen focus clock | Intentionally immersive, not a popup; existing exit/Back restores system bars and orientation. Unchanged |

## Verification and limits

- Automated cases cover settings actions at 360×640dp, 1.5× font scale, landscape 640×360dp and long custom dialog scroll-to-action behavior, on Robolectric API 28 and 34.
- The live Android emulator was checked with three-button navigation at native resolution and compact resolution. The final compact settings panel leaves its action row and lower card edge above navigation controls; Back dismissed it. No user task was created, completed, cancelled or deleted for testing.
- Final full debug unit/Compose suite passed: 1,258 tests, zero failures/errors. `assembleDebug` and `lintDebug` succeeded. Lint retains 127 warnings and 12 informational issues, no errors; this compatibility audit does not claim those pre-existing warnings were removed. Whitespace check passed.
- The emulator's display overrides and navigation mode are restored after testing. Installation uses `adb install -r`, not uninstall or data clearing.
- The work-controls animation regression initially stalled around the execution disclosure with the new window host. The extra outer scroll container was removed in favor of the existing independently scrolling body and stable 360dp preferred viewport (which still respects the parent's smaller constraints). Execution disclosure now appears without a size animation to avoid animated dialog-window measurement loops; its options and callbacks are unchanged. Insets use value-equal native records to avoid unnecessary layout-triggered state updates.
- This is source coverage plus selected regression/live checks, not certification of every OEM, Android release, keyboard, accessibility magnification or every dialog state. Samsung One UI still requires physical retesting.

## S21 acceptance checklist

Install the new debug APK over the same-signed build without uninstalling. Check Project settings (including a long category), task/subtask name entry with Samsung keyboard visible, work controls, project colors, tag and move sheets, and a rename/confirmation alert. Try both Samsung navigation buttons and gestures, landscape, and larger font/display settings. Confirm Back dismisses the popup or keyboard as appropriate, Home/Recents remain available, and all popup actions can be reached by scrolling. Do not uninstall to resolve a signing conflict without first preserving app data.

No database schema, timing engine, execution state, project icons/colors, persistence callback or remote Git state was changed by this compatibility fix.
