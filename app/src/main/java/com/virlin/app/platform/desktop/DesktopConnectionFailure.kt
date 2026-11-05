package com.virlin.app.platform.desktop

import java.io.IOException

/** No invitation secret or certificate contents may appear in connection diagnostics. */
class DesktopConnectionFailure(val stage: Stage, val address: String, cause: Throwable) :
    IOException(message(stage, address), cause) {
    enum class Stage { NETWORK, TLS, APPROVAL }
    companion object {
        fun message(stage: Stage, address: String): String = when (stage) {
            Stage.NETWORK -> "Could not reach the computer at $address. Connect both devices to the same Wi-Fi or phone hotspot, then generate a new QR. Guest/college Wi-Fi may block connections between devices. Check Windows firewall permission for the exact app you opened."
            Stage.TLS -> "Reached $address, but the secure identity check did not finish. Keep Virlin Connect open and scan a newly generated QR."
            Stage.APPROVAL -> "Secure connection to $address succeeded, but the computer did not confirm pairing. Disconnect in Virlin Connect, generate a new QR, and approve again."
        }
    }
}
