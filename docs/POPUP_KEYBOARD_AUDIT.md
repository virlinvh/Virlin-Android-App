# Popup keyboard audit — 2026-10-06

## Findings and correction

Custom window popups use `SafeDialog` (including aliases named `Dialog`). The window previously requested ADJUST_RESIZE while also applying IME insets to its content. On devices that resize the window, this subtracts keyboard space twice. Project/workstream creation also lacked a scrolling fallback, so a constrained Column compressed/clipped its fields and actions.

The shared window requests full height with ADJUST_NOTHING. Compose reserves system bars, cutouts and the remaining IME inset, with native owner/dialog fallbacks. Android 15/OEMs can still constrain the parent frame by IME; the difference between display and actual root height is deducted from the requested IME padding. A native layout listener reasserts the keyboard policy because Compose 1.6 restores ADJUST_RESIZE on Android below 12 after updating dialog properties. Ordinary custom dialogs scroll by default; full-screen settings retains its own scrollable list and fixed footer.

This shared correction covers creation/import/help, hierarchy controls, deletion confirmation, delegation, attention choosers, and note picker/link/preview windows without changing domain actions or saved data. Task/subtask creation is an in-activity overlay: it already uses IME padding and a scrollable TaskCreationCard, and is not changed to a second window. Standard Material AlertDialog and ModalBottomSheet surfaces own their native sizing and are not mechanically replaced.

## Regression coverage

PopupViewportUiTest runs on Android 28 and 34: native keyboard policy, 360×300dp single/bulk form typing and scroll-to-actions, portrait/landscape settings, large text and project-color behavior. Task creation, bulk UI and workstream control regressions run with these checks. These tests do not claim coverage of every OEM keyboard or keyboard animation; the reporting physical device still requires a retest with the updated APK.
