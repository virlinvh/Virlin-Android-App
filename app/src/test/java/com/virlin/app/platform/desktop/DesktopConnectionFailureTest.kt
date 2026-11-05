package com.virlin.app.platform.desktop

import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

class DesktopConnectionFailureTest {
    @Test fun timeoutMessagesIdentifyTheFailedStepWithoutLeakingCauseDetails() {
        val secret = "sensitive-pairing-secret"
        val messages = DesktopConnectionFailure.Stage.entries.map { stage ->
            val failure = DesktopConnectionFailure(stage, "172.16.26.120", SocketTimeoutException(secret))
            assertEquals(stage, failure.stage)
            assertTrue(failure.message!!.contains("172.16.26.120"))
            assertFalse(failure.message!!.contains(secret))
            failure.message
        }
        assertEquals(3, messages.toSet().size)
        assertTrue(messages[0]!!.contains("Could not reach"))
        assertTrue(messages[1]!!.contains("secure identity"))
        assertTrue(messages[2]!!.contains("Secure connection"))
    }
}
