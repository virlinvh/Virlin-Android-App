plugins {
    id("com.android.application") version "8.5.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0" apply false
    // Screenshot regression testing (test-only; no production/runtime impact).
    id("io.github.takahirom.roborazzi") version "1.26.0" apply false
    // Room annotation processing. KSP version is pinned to the Kotlin version above (2.0.0).
    id("com.google.devtools.ksp") version "2.0.0-1.0.24" apply false
}
