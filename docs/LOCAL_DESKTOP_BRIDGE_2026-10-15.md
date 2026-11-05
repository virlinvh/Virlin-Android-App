# Local desktop bridge (no debugging)

Status: pairing plus a first desktop project workspace slice implemented on `codex/local-qr-desktop-bridge`. Full app parity is not yet implemented.

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

In 0.4.0, the Virlin Link scanner used a user-rotation-aware capture activity and held the authenticated socket while the Link page stayed open. Both sides showed **Connected** after the TLS and one-time-secret handshake, but that release was pairing-only: no project access, screen control, or document transfer. The 0.5.0 slice below replaces that pairing-only limitation for its explicitly listed operations; physical-phone testing is still required before wider distribution.

## Project workspace slice (0.5.0)

The approved, certificate-pinned socket now accepts a version-1 JSON-lines protocol. A request and response are bounded at 512 KiB and 1 MiB respectively; the larger request bound supports the existing 250,000-character hierarchy format. The allowlist is `get_tree`, `create_project`, `create_workstream`, `create_task`, `preview_bulk`, and `apply_bulk`. The phone builds the project/workstream/task projection from `WorkStreamRepository`; creation and bulk import go through the existing `VirlinActions` contract. Bulk input is parsed with the same `BulkHierarchy` validator as the Android UI, previewed on the desktop, then applied atomically from a session-local draft; the apply request sends only its key. Task creation can target a project, workstream, or parent task, so hierarchy depth is governed by the domain rather than a desktop-only level limit. Each creation carries a per-session UUID idempotency key; the phone caches the last 128 receipts so a repeated request in one session does not create another item. No raw SQL, generic intent, file transfer, or screen control is exposed. The phone approval text names the capabilities granted to this computer.

The desktop shows this as a **basic project workspace**, not full Virlin. It cannot yet edit or delete items, create notes or rich documents, transfer files, or use execution/review features. Those require additional explicit actions and parity tests. The phone remains the sole data owner, and disconnect ends access.

Both the Android app and Windows Connect must be updated to compatible builds. To try it, open Virlin Link on the phone, show a new QR in Windows Connect, scan and approve it. The approved session now belongs to the app process instead of the Virlin Link screen, so the user may navigate among Virlin pages without dropping the connection. It ends on explicit Disconnect, network loss, or when Virlin leaves the foreground. This is not a background service; use the Apps → Virlin Link page to check or end the session. The desktop project workspace offers **+ Project**, **+ Workstream**, **+ Task**, **+ Subtask**, and bulk import at each hierarchy destination. The same workspace is available over Wi-Fi, a hotspot, or a USB-tethered network; USB screen mirror remains separate. If a creation or import response times out, check the phone before trying again. Keep the title and target unchanged to reuse the in-session request key. Do not claim this as physical-phone verified until the paired devices have been tested together.

The Windows project directory/detail view now follows the phone's project-card visual language, but visual similarity is not feature parity. Mind maps, files, notes, reviews, and other app features need explicit phone-side APIs and desktop screens before they can be used through this local link. The alternative of an exact live screen mirror remains the separate USB-debugging route. A no-debugging Wi-Fi/USB-tethered link cannot simply take over the Android UI; the desktop client must implement each feature against the phone's data/action contract.

## Background connection update (2026-10-28)

The earlier foreground-only lifetime described above is superseded. After explicit QR approval, Android starts a `connectedDevice` foreground service with a persistent Virlin Connect notification and a Disconnect action. Switching apps, locking the phone, or taking a call no longer deliberately closes the socket. Explicit Disconnect, a stopped service, process death, or actual network loss still ends access; the service is `START_NOT_STICKY` and never reconnects without a fresh approval. The phone continues to initiate the TLS connection and does not expose a background listener. Test on a physical phone because emulator/unit tests cannot prove call-time Wi-Fi behavior or OEM battery-management policies.

The Projects directory uses the current Android page as its visual reference: project count, search, status rail, project identity, workstream count, and progress ring. The phone computes progress with its canonical `ProgressCalculator` and sends the percentage (or null for unstructured work); Windows must never derive a false `0%` from missing work. The phone also sends each workstream's effective attention state so desktop filters use live state instead of project completion. User-selected custom icon images are not transferred by this slice, so their desktop avatars fall back to an initial until the file bridge is built. Search and filter run over the fetched tree; Refresh retrieves subsequent phone changes. The connected Windows view exposes only Projects as active navigation; other app areas remain visibly unavailable until their protocols are implemented.
