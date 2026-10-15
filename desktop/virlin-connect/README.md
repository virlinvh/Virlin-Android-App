# Virlin Connect

A local Windows companion that opens the full Android Virlin app in a controllable desktop mirror. The Android app and its on-device data remain authoritative. No server, account, sync, or desktop database is involved.

## User flow

1. Install the Virlin Connect Windows setup EXE.
2. Install Virlin on the Android phone and enable Developer options → USB debugging.
3. Connect the unlocked phone with USB and approve the Android debugging prompt.
4. Open Virlin Connect and choose **Open Virlin** for the phone. The app appears in a separate scrcpy window, controlled with mouse and keyboard. Clipboard paste works through scrcpy.

The phone is never overwritten or installed to by Connect. Emulators are hidden from the normal device picker. No remote network connection is required; USB is the supported connection path for this first version.

## Build on Windows

Requires Node.js, Rust (MSVC), Visual Studio C++ Build Tools, and WebView2. Install official [scrcpy](https://github.com/Genymobile/scrcpy) with `winget install Genymobile.scrcpy`. Then, from this folder:

```powershell
npm install
./Prepare-Tools.ps1
npm run tauri icon brand-icon.svg
npm run desktop:build
```

The NSIS setup EXE is written under `src-tauri/target/release/bundle/nsis`, or under `$env:CARGO_TARGET_DIR/release/bundle/nsis` when that variable is set. On machines where Application Control blocks build helpers inside the repository, set `$env:CARGO_TARGET_DIR` to an approved local build directory first. `Prepare-Tools.ps1` copies the official scrcpy runtime (including its Apache 2.0 license and bundled ADB) into a gitignored resource folder so the installer includes it. Source control does not contain those third-party binaries.

To test the UI without building an installer, run `npm run desktop:dev` after preparing tools. USB physical-phone testing is required before distribution; an emulator alone cannot validate Android authorization prompts, real-device clipboard behavior, or phone-specific USB drivers.
