package com.virlin.app.platform.desktop

import java.net.URI

/**
 * Strictly validates the one-time desktop QR payload before any network activity.
 * The secret and fingerprint must never be logged or persisted.
 */
data class DesktopPairingInvitation private constructor(
    val host: String,
    val port: Int,
    val certificateSha256: String,
    val secret: String,
    val expiresAtEpochSeconds: Long,
) {
    companion object {
        private const val MAX_VALIDITY_SECONDS = 300L
        private val HEX_256 = Regex("[0-9a-fA-F]{64}")
        private val DECIMAL = Regex("[0-9]+")

        fun parse(raw: String, nowEpochSeconds: Long): DesktopPairingInvitation? {
            if (raw.length !in 150..512) return null
            val uri = runCatching { URI(raw) }.getOrNull() ?: return null
            if (uri.scheme != "virlin-pair" || uri.host != "connect" || uri.path != "/v1" ||
                uri.fragment != null || uri.userInfo != null) return null

            val pairs = uri.rawQuery?.split('&')?.map { part ->
                val separator = part.indexOf('=')
                if (separator < 1 || separator == part.lastIndex) return null
                part.substring(0, separator) to part.substring(separator + 1)
            } ?: return null
            if (pairs.size != 5 || pairs.map { it.first }.toSet() !=
                setOf("host", "port", "cert", "secret", "expires")) return null
            val fields = pairs.toMap()
            val host = fields["host"] ?: return null
            if (!isPrivateIpv4(host)) return null
            val portText = fields["port"] ?: return null
            if (!DECIMAL.matches(portText)) return null
            val port = portText.toIntOrNull()?.takeIf { it in 1024..65535 } ?: return null
            val certificate = fields["cert"]?.takeIf(HEX_256::matches) ?: return null
            val secret = fields["secret"]?.takeIf(HEX_256::matches) ?: return null
            val expiryText = fields["expires"] ?: return null
            if (!DECIMAL.matches(expiryText)) return null
            val expiry = expiryText.toLongOrNull() ?: return null
            if (expiry <= nowEpochSeconds || expiry - nowEpochSeconds > MAX_VALIDITY_SECONDS) return null
            return DesktopPairingInvitation(host, port, certificate.lowercase(), secret.lowercase(), expiry)
        }

        private fun isPrivateIpv4(host: String): Boolean {
            val octets = host.split('.')
            if (octets.size != 4 || octets.any { !DECIMAL.matches(it) ||
                    (it.length > 1 && it.startsWith('0')) }) return false
            val values = octets.map { it.toIntOrNull() ?: return false }
            if (values.any { it !in 0..255 }) return false
            return values[0] == 10 ||
                (values[0] == 172 && values[1] in 16..31) ||
                (values[0] == 192 && values[1] == 168) ||
                (values[0] == 169 && values[1] == 254)
        }
    }
}
