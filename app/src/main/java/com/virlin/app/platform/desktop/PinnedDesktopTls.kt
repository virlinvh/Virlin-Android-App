package com.virlin.app.platform.desktop

import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** Opens only a TLS connection whose leaf certificate matches the scanned QR fingerprint. */
object PinnedDesktopTls {
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 10_000

    fun connect(invitation: DesktopPairingInvitation, nowEpochSeconds: Long): SSLSocket {
        require(nowEpochSeconds < invitation.expiresAtEpochSeconds) { "Pairing invitation expired" }
        val expected = invitation.certificateSha256.hexBytes()
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {
                throw CertificateException("Client certificates are not accepted")
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
                val certificate = chain?.firstOrNull() ?: throw CertificateException("No desktop certificate")
                val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                if (!MessageDigest.isEqual(expected, actual)) {
                    throw CertificateException("Desktop identity did not match the pairing QR")
                }
            }
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trust), null)
        val socket = context.socketFactory.createSocket() as SSLSocket
        try {
            socket.enabledProtocols = socket.supportedProtocols.filter {
                it == "TLSv1.3" || it == "TLSv1.2"
            }.toTypedArray()
            socket.soTimeout = READ_TIMEOUT_MS
            socket.connect(InetSocketAddress(invitation.host, invitation.port), CONNECT_TIMEOUT_MS)
            socket.startHandshake() // No pairing secret or app data is written before pin verification.
            return socket
        } catch (failure: Exception) {
            socket.close()
            throw failure
        }
    }

    private fun String.hexBytes(): ByteArray = ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
