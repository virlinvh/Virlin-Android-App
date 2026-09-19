package com.virlin.app

import android.content.Intent
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.lifecycleScope
import com.virlin.app.debug.VirlinStartup
import com.virlin.app.domain.VirlinGraph
import com.virlin.app.mock.MockTimerEngine
import com.virlin.app.platform.NotificationNavigation
import com.virlin.app.ui.navigation.VirlinApp
import com.virlin.app.ui.theme.VirlinTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        VirlinStartup.beginLaunch()
        VirlinStartup.mark("onCreate_ENTER", "savedInstance=${savedInstanceState != null}")
        VirlinStartup.mark("splash_setup", "Theme.Virlin windowBackground only (no SplashScreen lib)")
        VirlinStartup.mark("super.onCreate_ENTER")
        super.onCreate(savedInstanceState)
        VirlinStartup.mark("super.onCreate_EXIT")

        VirlinGraph.init(applicationContext)
        VirlinStartup.mark("VirlinGraph.init_DONE")
        NotificationNavigation.offer(intent)

        // DEBUG/TEST extras — ignored in release (BuildConfig.DEBUG).
        if (com.virlin.app.BuildConfig.DEBUG) {
            if (intent.hasExtra(EXTRA_HYDRATE_DELAY_MS)) {
                VirlinGraph.debugHydrationDelayMs = intent.getLongExtra(EXTRA_HYDRATE_DELAY_MS, 0L)
            }
            if (intent.getBooleanExtra(EXTRA_HYDRATE_FAIL, false)) {
                VirlinGraph.debugFailNextHydration = true
            }
        }

        // First Compose frame MUST NOT wait for Room.
        VirlinStartup.mark("setContent_START")
        setContent {
            VirlinTheme {
                LaunchedEffect(Unit) {
                    VirlinStartup.mark("reportFullyDrawn_ENTER")
                    reportFullyDrawn()
                    VirlinStartup.mark("reportFullyDrawn_EXIT")
                }
                VirlinApp()
            }
        }
        VirlinStartup.mark("setContent_END")

        MockTimerEngine.start()
        VirlinStartup.mark("MockTimerEngine.start_DONE")

        // Hydrate on IO; DomainDisplayBridge starts inside ensureReady after bind.
        lifecycleScope.launch {
            VirlinStartup.mark("startAsync_SCHEDULED")
            VirlinGraph.startAsync()
        }

        window.decorView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                VirlinStartup.markOnceFirstLayout()
                window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(this)
            }
        })
        window.decorView.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                VirlinStartup.markOnceFirstDraw()
            }
        })
        VirlinStartup.mark("onCreate_EXIT")
    }

    override fun onStart() {
        super.onStart()
        VirlinStartup.mark("onStart")
    }

    override fun onResume() {
        super.onResume()
        VirlinStartup.mark("onResume")
    }

    override fun onPostResume() {
        super.onPostResume()
        VirlinStartup.mark("onPostResume")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        VirlinStartup.mark("onWindowFocusChanged", "hasFocus=$hasFocus")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        NotificationNavigation.offer(intent)
    }

    companion object {
        const val EXTRA_HYDRATE_DELAY_MS = "hydrate_delay_ms"
        const val EXTRA_HYDRATE_FAIL = "hydrate_fail"
    }
}
