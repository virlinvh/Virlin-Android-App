# Android / toolchain pins (Cursor)

**Authoritative pins** are root `build.gradle.kts` + `app/build.gradle.kts` (module declares deps directly):

| Item | Version |
|---|---|
| AGP | 8.5.1 |
| Gradle | 8.9 |
| Kotlin / Compose plugin | 2.0.0 |
| KSP | 2.0.0-1.0.24 |
| Compose BOM | 2024.06.00 |
| Room | 2.6.1 |
| Roborazzi | 1.26.0 |
| compileSdk / targetSdk | 34 |
| minSdk | 26 |
| Java | 17 |

`gradle/libs.versions.toml` lists **newer** versions but is **not consumed** — do not “apply” it.

**Pixel 8 / API 35** is the **test device**, not a requirement to bump compileSdk/targetSdk to 35.

## Compose / architecture reminders

- Unidirectional StateFlow; UI never mutates Room/DAOs.
- Bottom bar owns navigation-bar inset; respect IME and scroll ownership.
- One source of truth for Focus timer / WorkStream state / FocusSession.
- MockData = display bridge via `DomainDisplayBridge` — not domain authority.
- Surgical changes only; match existing style.
