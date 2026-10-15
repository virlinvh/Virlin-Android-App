package com.virlin.app.platform.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DesktopPairingInvitationTest {
    private val now = 1_790_000_000L
    private val cert = "a".repeat(64)
    private val secret = "B".repeat(64)

    private fun payload(host: String = "192.168.1.8", port: String = "43123", expires: Long = now + 120) =
        "virlin-pair://connect/v1?host=$host&port=$port&cert=$cert&secret=$secret&expires=$expires"

    @Test fun acceptsPrivateLanInvitationAndNormalizesHex() {
        val invitation = DesktopPairingInvitation.parse(payload(), now)
        assertNotNull(invitation)
        assertEquals("192.168.1.8", invitation!!.host)
        assertEquals(43123, invitation.port)
        assertEquals(secret.lowercase(), invitation.secret)
    }

    @Test fun rejectsPublicOrLoopbackTargets() {
        for (host in listOf("8.8.8.8", "127.0.0.1", "example.com", "192.168.01.8", "192.168.1.999")) {
            assertNull(host, DesktopPairingInvitation.parse(payload(host = host), now))
        }
    }

    @Test fun rejectsExpiredOrExcessivelyLongInvitations() {
        assertNull(DesktopPairingInvitation.parse(payload(expires = now), now))
        assertNull(DesktopPairingInvitation.parse(payload(expires = now + 301), now))
    }

    @Test fun rejectsDuplicateFieldsAndMalformedCredentials() {
        assertNull(DesktopPairingInvitation.parse(payload() + "&secret=$secret", now))
        assertNull(DesktopPairingInvitation.parse(payload().replace(cert, "short"), now))
        assertNull(DesktopPairingInvitation.parse(payload().replace(secret, "bad"), now))
        assertNull(DesktopPairingInvitation.parse(payload(port = "22"), now))
    }

    @Test fun tlsDialerRejectsInvitationThatExpiredAfterScanning() {
        val invitation = DesktopPairingInvitation.parse(payload(expires = now + 1), now)!!
        assertThrows(IllegalArgumentException::class.java) {
            PinnedDesktopTls.connect(invitation, now + 1)
        }
    }
}
