package com.virlin.app.platform.desktop

import android.app.NotificationManager
import android.app.Service
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DesktopLinkServiceTest {
    @Test fun approvedLinkService_hasVisibleChannelAndNeverAutoRestarts() {
        val controller = Robolectric.buildService(DesktopLinkService::class.java).create()
        val service = controller.get()
        assertNotNull(service.getSystemService(NotificationManager::class.java)
            .getNotificationChannel("virlin_desktop_link"))
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        controller.destroy()
    }
}
