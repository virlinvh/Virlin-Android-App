package com.virlin.app.debug

import android.os.SystemClock
import android.util.Log
import com.virlin.app.BuildConfig

/**
 * DEBUG-only startup timeline instrumentation. No-ops in release builds.
 * Tag: VirlinStartup
 */
object VirlinStartup {
    const val TAG = "VirlinStartup"

    @Volatile private var t0: Long = 0L
    @Volatile private var launchId: Int = 0
    @Volatile private var virlinAppLogged = false
    @Volatile private var nowLogged = false
    @Volatile private var mockEmitLogged = false
    @Volatile private var firstLayoutLogged = false
    @Volatile private var firstDrawLogged = false

    fun beginLaunch() {
        if (!BuildConfig.DEBUG) return
        launchId += 1
        t0 = SystemClock.elapsedRealtime()
        virlinAppLogged = false
        nowLogged = false
        mockEmitLogged = false
        firstLayoutLogged = false
        firstDrawLogged = false
        mark("LAUNCH_BEGIN", "id=$launchId")
    }

    fun mark(event: String, detail: String = "") {
        if (!BuildConfig.DEBUG) return
        val elapsed = if (t0 == 0L) 0L else SystemClock.elapsedRealtime() - t0
        val thread = Thread.currentThread().name
        val extra = if (detail.isEmpty()) "" else " $detail"
        Log.i(TAG, "L$launchId +${elapsed}ms [$thread] $event$extra")
    }

    fun markOnceVirlinApp() {
        if (!BuildConfig.DEBUG) return
        if (virlinAppLogged) return
        virlinAppLogged = true
        mark("VirlinApp_FIRST_COMPOSITION")
    }

    fun markOnceNow() {
        if (!BuildConfig.DEBUG) return
        if (nowLogged) return
        nowLogged = true
        mark("NowScreen_FIRST_COMPOSITION")
    }

    fun markOnceMockEmit(detail: String) {
        if (!BuildConfig.DEBUG) return
        if (mockEmitLogged) return
        mockEmitLogged = true
        mark("MockData_FIRST_EMISSION", detail)
    }

    fun markOnceFirstLayout() {
        if (!BuildConfig.DEBUG) return
        if (firstLayoutLogged) return
        firstLayoutLogged = true
        mark("FIRST_LAYOUT")
    }

    fun markOnceFirstDraw() {
        if (!BuildConfig.DEBUG) return
        if (firstDrawLogged) return
        firstDrawLogged = true
        mark("FIRST_DRAW")
    }
}
