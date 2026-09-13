package com.virlin.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.virlin.app.domain.VirlinGraph
import android.content.Intent
import com.virlin.app.mock.MockTimerEngine
import com.virlin.app.platform.NotificationNavigation
import com.virlin.app.ui.navigation.VirlinApp
import com.virlin.app.ui.theme.VirlinTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Domain first: seeds the repository from mock data once and starts the
        // domain -> display projection. The ticker only advances display counters.
        VirlinGraph.init(applicationContext)
        VirlinGraph.start()
        NotificationNavigation.offer(intent)
        MockTimerEngine.start()
        setContent {
            VirlinTheme {
                VirlinApp()
            }
        }
    }

    /** Notification taps while alive (singleTop): route without recreating the Activity. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        NotificationNavigation.offer(intent)
    }
}
