# Virlin on the laptop, with the phone as the source of truth

This is a local control workflow, not a second Virlin installation or a sync service.
It opens the existing app on your physical Android phone and mirrors it with scrcpy.
All imports, notes and edits are saved by Virlin on the phone and remain there after disconnecting.

## First connection (USB)

1. Install Virlin on the phone. Enable Developer options and USB debugging, connect it by USB,
   and accept the phone's debugging authorization prompt.
2. Install the official [scrcpy Windows package](https://github.com/Genymobile/scrcpy/blob/master/doc/windows.md):
   `winget install --id Genymobile.scrcpy --exact`.
3. From this repository in PowerShell, run:

   ```powershell
   .\tools\phone-desktop\Start-VirlinPhone.ps1 -List
   .\tools\phone-desktop\Start-VirlinPhone.ps1
   ```

The launcher selects a physical USB device, verifies that Virlin is installed, opens it,
and starts scrcpy. It refuses to choose an emulator by default and never installs an APK,
modifies phone data, or sends anything to a remote server.

## Local Wi-Fi (optional)

Pair and connect the phone using Android's Wireless debugging on the same trusted Wi-Fi network,
following the [official scrcpy connection guide](https://github.com/Genymobile/scrcpy/blob/master/doc/connection.md#tcpip-wireless).
Once `adb devices` lists the phone as an `IP:port` device, run:

```powershell
.\tools\phone-desktop\Start-VirlinPhone.ps1 -Mode Wifi
```

If multiple phones are connected, pass `-Serial <device-serial>` to select the intended one.
Turn off Wireless debugging when finished. Wi-Fi control works on a local network even when
that network has no internet connection.

## Working with generated content

- Copy the generated Virlin JSON on the laptop, focus Virlin's Bulk import input in the
  scrcpy window, and paste with Ctrl+V. Check Virlin's preview before creating items.
- Copy prose on the laptop and paste it into the desired Virlin note field.
- Dragging a non-APK file onto scrcpy copies it to the phone's Downloads folder; then use
  Virlin's own file picker to import it. Drag-and-drop alone does not attach it to a note.

The bulk importer currently accepts at most 500 items, 250,000 characters and six
hierarchy levels per import. Split larger generated outlines into separate imports.
