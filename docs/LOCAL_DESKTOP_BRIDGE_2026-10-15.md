# Local desktop bridge (no debugging)

Status: implementation in stages on `codex/local-qr-desktop-bridge`. This document is the contract, not a claim that desktop editing already works.

## Product decision

Replace the ADB/scrcpy requirement with a **local Virlin desktop client**. The Android installation remains the sole data owner. The laptop displays a desktop-sized client and sends explicit operations to the phone; it does not copy or open the Room database, inject touches, enable accessibility, or capture the screen. The earlier scrcpy installer remains an optional development prototype only and is not the intended user onboarding flow.

The phone and laptop must share a local network (Wi-Fi or the phone's hotspot). No account, VPS, Internet connection, or USB debugging is required. Lack of LAN reachability must be shown as a connection error, never silently routed through a cloud relay.

## Pairing and transport

1. The desktop starts a listener on a private LAN address, creates an ephemeral TLS identity and a 256-bit random invitation secret, and shows a QR code. The invitation expires after at most five minutes.
2. The user opens **Connect desktop** inside Virlin and scans the QR. A manual code/address flow may be added later, but must not weaken the certificate-pinning contract.
3. The phone validates the invitation strictly (version, private IP, port, expiry, fingerprint, secret), then opens an outbound TLS connection and pins the desktop certificate fingerprint from the QR **before** sending the secret or any app data.
4. The desktop accepts the secret once, shows the phone's device name, and requires confirmation on the phone. Subsequent messages are authenticated within that TLS session. Disconnect on either device revokes the in-memory session. Reconnect requires a new invitation.
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

`DesktopPairingInvitation` is the pure Android invitation validator. `PinnedDesktopTls` can establish a TLS connection pinned to that invitation's certificate fingerprint; it sends no secret or app data itself. The desktop has an isolated, unexposed `PairingListener` that creates a fresh certificate and secret, limits attempts and expiry, and passes a local TLS handshake test. Neither side is wired to an app screen or exposes an API yet. The next slice is in-app scanner/confirmation plus desktop UI wiring, followed by read-only projections. The old USB connector is left unchanged until an actual replacement is usable.
