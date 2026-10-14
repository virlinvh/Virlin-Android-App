# Project knowledge black-screen investigation

- The running app had no app crash in Android's crash buffer. Its accessibility hierarchy contained the centered Project knowledge Add menu and all eight enabled choices.
- ADB screenshots were frozen/corrupted, containing black space and an older Task screen. Dismissing the menu and sending Home did not update the screenshot. This isolates the observed failure to emulator display rendering, not missing menu state.
- Cold-started the same Pixel_8 AVD with `-gpu swiftshader_indirect -no-snapshot-load`; no wipe or application-data clearing.
- Hardened centered MapAddPalette rendering by avoiding its unnecessary offscreen DstIn fade layer. Mind-map sheet fades and all callbacks remain unchanged. This is preventative; the investigation does not prove that the fade caused the emulator failure.
- APK build and focused palette/project-knowledge tests pass. Centered palette regression checks visibility and callbacks for all eight choices.
