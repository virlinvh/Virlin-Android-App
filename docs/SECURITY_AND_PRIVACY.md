# Virlin security, privacy and recovery policy

Updated 2026-10-04. This describes the implementation, not a legal certification or a published store privacy policy.

## Data and network boundary

Projects, task names, hierarchy, execution history, captured notes and imported/recorded media are stored in the application's private Android storage. The database is not application-level encrypted; Android's device security and sandbox protect the local files. There is currently no account/cloud-sync service or automatic third-party crash/analytics upload.

Internet permission supports user-enabled link previews and YouTube playback. The relevant external service receives network requests, including the device's IP address. Local-first does not mean these optional online features are offline or anonymous. Microphone access is for user-initiated recording. Notifications and exact alarms support reminders, with optional exact-alarm access.

Release startup does not insert demo records. Debug fixtures remain development-only. Existing records are never deleted by title or by the demo seed marker.

## OS backup and transfer

- Android 8.0/8.1 (API 26/27): app data is excluded from OS backup because client-side backup encryption cannot be required there. Uninstall/device loss can lose local data on these versions; do not imply automatic recovery.
- Android 9–11: shared preferences, database and managed files require client-side encryption for backup. Availability depends on the user's transport/settings and lock screen. Do not promise device transfer on these older versions.
- Android 12+: cloud backup is disabled when encryption capabilities are absent. Device-to-device transfer includes the database, preferences and managed files together, preserving relative-file references.
- Cache previews are not part of the explicitly included managed-file domains. Backup quotas/transport policies may prevent large media recovery; a successful source configuration is not proof of a successful cloud restore.

These rules do not erase local data or any already-existing historical backup. A verified restore on disposable fixtures is a release gate. Palette settings and timer preferences participate in OS backup but are device-local settings: domain project export/duplication is not currently a supported palette-transfer contract.

Reference: [Android backup security recommendations](https://developer.android.com/privacy-and-security/risks/backup-best-practices), [Auto Backup rules](https://developer.android.com/identity/data/autobackup).

## Untrusted input

Office previews cap actual compressed input (12 MiB), decompressed data per pass (24 MiB), each entry (4 MiB), ZIP entries (512), text-node length (32,768), collected nodes (50,000), cells per row (1,024) and displayed rows/sheets/slides. Limits apply to skipped entries too. Parsing has a cooperative monotonic deadline and interruption checks; preview work is cancellable IO. Malformed document/read failures are friendly errors, not uncaught UI exceptions. No Office macros execute.

Map snapshots use bounded, background reads and remain read-only previews. Remote thumbnails cap payloads and dimensions, sample decoded pixels and close their network connections. A blocked/non-interruptible provider may still take its underlying timeout to finish; these guards are not a universal file-format security guarantee.

## Release and operational boundaries

Private receiver/FileProvider scope, transactional writes and explicit Room migrations must remain intact. Never use destructive migration fallback to fix a schema mismatch. Test imports, upgrades and recovery on a disposable emulator, not the populated user emulator.

Do not log task titles, note text, recording content or signing secrets to remote diagnostics. Any future telemetry needs an explicit privacy/consent design before integration. Store privacy/data-safety declarations and a public privacy-policy URL require owner review before publication.

A source audit or passing tests cannot certify resistance to every attack or production-scale crash/ANR rates. Dependency advisories, real-device performance/accessibility and staged-release monitoring remain ongoing responsibilities.
