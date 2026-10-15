package com.virlin.app.ui.desktop

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.virlin.app.platform.desktop.DesktopPairingInvitation
import com.virlin.app.platform.desktop.PinnedDesktopTls
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Deliberately pairing-only: no task, note, or file operation is exposed. */
@Composable
fun DesktopConnectScreen(onBack: () -> Unit) {
    var invitation by remember { mutableStateOf<DesktopPairingInvitation?>(null) }
    var connection by remember { mutableStateOf<SSLSocket?>(null) }
    var status by remember { mutableStateOf("Scan the QR shown by Virlin Connect on your computer.") }
    var connecting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(connection) { onDispose { connection?.close() } }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val scanned = result.contents
        if (scanned == null) {
            status = "Scan cancelled."
        } else {
            val parsed = DesktopPairingInvitation.parse(scanned, System.currentTimeMillis() / 1000)
            invitation = parsed
            status = if (parsed == null) "Invalid or expired Virlin pairing QR. Generate a new one on the computer." else
                "Approve a secure connection to ${parsed.host}? Only pairing status is available in this version."
        }
    }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Top) {
        OutlinedButton(onClick = onBack) { Text("← Back") }
        Spacer(Modifier.height(30.dp))
        Text("Connect desktop", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("Both devices must use the same Wi-Fi or phone hotspot. No USB debugging or cloud account is needed.")
        Spacer(Modifier.height(26.dp))
        Text(status, color = Color(0xFF245241))
        Spacer(Modifier.height(24.dp))
        if (connection == null) {
            Button(onClick = {
                invitation = null
                scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Scan the Virlin Connect QR").setBeepEnabled(false))
            }, modifier = Modifier.fillMaxWidth(), enabled = !connecting) { Text("Scan QR") }
            val pending = invitation
            if (pending != null) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    connecting = true
                    status = "Verifying this computer…"
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { connectAndApprove(pending) }
                        connecting = false
                        result.onSuccess {
                            connection = it
                            invitation = null
                            status = "Connected securely to ${pending.host}. Keep this screen open. Desktop editing is not available yet."
                        }.onFailure {
                            status = when (it) {
                                is SocketTimeoutException -> "Connection timed out. Check that both devices share a network and Windows allows Virlin Connect on private networks."
                                else -> "Could not pair: ${it.message ?: "Connection failed"}. Generate a new QR and try again."
                            }
                            invitation = null
                        }
                    }
                }, modifier = Modifier.fillMaxWidth(), enabled = !connecting) { Text("Approve connection") }
            }
        } else {
            OutlinedButton(onClick = {
                connection?.close()
                connection = null
                status = "Disconnected. Scan a new QR to reconnect."
            }, modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
        }
    }
}

private fun connectAndApprove(invitation: DesktopPairingInvitation): Result<SSLSocket> = runCatching {
    val socket = PinnedDesktopTls.connect(invitation, System.currentTimeMillis() / 1000)
    try {
        socket.outputStream.write("PAIR ${invitation.secret}\n".toByteArray(Charsets.US_ASCII))
        socket.outputStream.flush()
        val expected = "PAIR-OK\n".toByteArray(Charsets.US_ASCII)
        val received = ByteArray(expected.size)
        var offset = 0
        while (offset < received.size) {
            val count = socket.inputStream.read(received, offset, received.size - offset)
            if (count < 0) throw IOException("Computer closed the connection")
            offset += count
        }
        if (!java.security.MessageDigest.isEqual(expected, received)) throw IOException("Computer rejected pairing")
        socket
    } catch (failure: Exception) {
        socket.close()
        throw failure
    }
}
