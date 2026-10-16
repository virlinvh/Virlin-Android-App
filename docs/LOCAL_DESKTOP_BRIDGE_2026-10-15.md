# Local desktop bridge (no debugging)

Status: first QR pairing flow implemented on `codex/local-qr-desktop-bridge`. Desktop editing is not yet implemented.

## Product decision

Replace the ADB/scrcpy requirement with a **local Virlin desktop client**. The Android installation remains the sole data owner. The laptop displays a desktop-sized client and sends explicit operations to the phone; it does not copy or open the Room database, inject touches, enable accessibility, or capture the screen. The earlier scrcpy installer remains an optional development prototype only and is not the intended user onboarding flow.

The phone and laptop must share a local network (Wi-Fi or the phone's hotspot). No account, VPS, Internet connection, or USB debugging is required. Lack of LAN reachability must be shown as a connection error, never silently routed through a cloud relay.

## Pairing and transport

1. The desktop starts a listener on a private LAN address, creates an ephemeral TLS identity and a 256-bit random invitation secret, and shows a QR code. The invitation expires after at most five minutes.
2. The user opens **Connect desktop** inside Virlin and scans the QR. A manual code/address flow may be added later, but must not weaken the certificate-pinning contract.
3. The phone validates the invitation strictly (version, private IP, port, expiry, fingerprint, secret), then opens an outbound TLS connection and pins the desktop certificate fingerprint from the QR **before** sending the secret or any app data.
4. The desktop accepts the secret once after confirmation on the phone and shows connected status. No device name or application messages are exchanged in this first slice. Disconnect on either device ends the in-memory session. Reconnect requires a new invitation.
5. Secrets, notes, task titles and file bytes must never be logged or placed in analytics. The listener is active only while the desktop connection window is open and is not exposed through Internet port forwarding.

The QR is a pairing credential, not a magic Android permission. The phone app must present the scanner and explicit approval. No app screen capture or system-level input-control permission is involved.

## Data and action boundary

The phone exposes a versioned, allowlisted message API. Read projections are produced from the canonical `WorkStreamRepository`; writes go through `VirlinActions` or existing domain action classes. No raw SQL, generic reflection or arbitrary Android intents may be sent from the laptop. All IDs are stable entity IDs, not titles or list positions. Domain errors are returned as typed failures. Every operation has bounded payload size and an idempotency key where retry could duplicate a write.

Initial parity order:

1. Read-only project/workstream/task tree and connection status.
2. Create/edit projects, workstreams and arbitrarily nested tasks, using existing actions and validation.
3. Bulk import through the existing import parsers; show validation preview before applying.
4. Notes and knowledge blocks, then files/images/PDFs with streamed, bounded transfer into Android-managed storage.
5. Remaining execution, review and content workspaces, with parity tests against the Android action contract.

The desktop UI is not called "full Virlin" until the route/action matrix is complete. A missing operation is disabled or clearly labeled, never represented by a nonfunctional control.

## Compatibility and safety

- No Room migration is reserved or required for the pairing foundation; ephemeral credentials are never stored in Room.
- Do not start a background listener on Android. The phone initiates the network connection, avoiding a permanently exposed phone port and background-service restrictions.
- The existing phone UI and data remain usable when no desktop is connected. Disconnect must not roll back committed work or leave a partial import.
- The first connected tests must use a disposable emulator and local simulated desktop. The user's populated emulator and physical phone are not data-reset targets.
- Physical-phone/Wi-Fi/hotspot tests, authentication abuse tests, interrupted-transfer tests and app lifecycle tests are required before distribution.

## Current implementation slice

`DesktopPairingInvitation` validates the one-time QR and `PinnedDesktopTls` pins the desktop certificate. The Apps tab opens **Virlin Link**, which scans a QR and requires explicit approval. Virlin Connect lists private LAN addresses, displays the QR, and reports connection status. The desktop listener permits one authenticated session; no task, note, file, screen-mirroring, or other application command is enabled through this connection. The Android session lasts only while its screen is open. The next slice is a versioned read-only projection API, followed by allowlisted writes. The USB mirror remains an optional separate connection method, not part of QR pairing.

Virlin Connect 0.3.0 also offers an optional **USB** tab. It polls the bundled ADB tools for attached physical USB phones, distinguishes unauthorized/offline devices, and offers an explicit **Open Virlin** action only for an authorized phone with Virlin installed. That action opens the existing scrcpy mirror; it does not use the QR pairing protocol or a desktop data API. USB debugging and the phone's authorization prompt are required by this mode. Connecting a cable by itself cannot grant access. Emulators and ADB-over-Wi-Fi targets are excluded from the USB list and launch action.

Virlin Connect 0.4.0 separates **USB cable** from **USB mirror**. The cable route uses Android's USB tethering to create a local network, detects a USB/RNDIS network adapter on Windows, and carries the existing certificate-pinned QR pairing over that adapter. It needs no Developer options or ADB, but the user must enable USB tethering in Android Settings; Virlin cannot silently turn it on. Android and Windows are still only paired—no desktop task, note, bulk-import, file, or screen-control API has been added. The advanced mirror route continues to require USB debugging. Tethering can share mobile data and may be restricted or charged by a carrier; the user should disable it afterward if not needed.

The other no-debugging direct-USB candidate is Android Open Accessory (AOA), which requires device support, a phone-side USB permission grant, and a suitable Windows USB driver and host implementation. It is not a universal drop-in replacement for ADB mirroring, so it is not enabled by this release. Android documents that ordinary ADB requires Developer options and device authorization, while [USB accessory mode](https://source.android.com/docs/core/interaction/accessories/aoa) does not require debugging. [Pixel USB tethering instructions](https://support.google.com/pixelphone/answer/2812516) describe the user-side toggle; [Windows WinUSB requirements](https://learn.microsoft.com/en-us/windows-hardware/drivers/usbcon/windows-desktop-app-for-a-usb-device) explain the driver dependency for custom USB protocols.

To test on a phone, install the matching Android build and Windows Connect build, join the same Wi-Fi or hotspot, open Connect on Windows, then Apps → Virlin Link → Open scanner → Approve on Android. If Windows asks, allow this application on **private networks** only. A connection timeout may mean client isolation, an incorrect network interface, or firewall blocking; do not turn off the firewall globally or forward the pairing port to the Internet. A camera permission is requested by the QR scanner. A physical phone is needed to verify camera scanning and LAN reachability end-to-end.

The Virlin Link scanner uses a user-rotation-aware capture activity so it follows the phone between portrait and landscape while respecting rotation lock. The phone holds the authenticated socket for as long as the Link page remains open; a remote close returns it to the disconnected state. Both sides show **Connected** after the TLS and one-time-secret handshake. This is still a pairing-only connection: it does not unlock desktop editing, screen control, or document transfer over Wi-Fi. Those require a separately designed, versioned action API and authorization contract. Do not represent QR pairing as a usable desktop workspace until that API is implemented and tested on a physical phone.
