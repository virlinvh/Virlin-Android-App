package com.virlin.app.ui.desktop

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.virlin.app.R
import com.virlin.app.platform.desktop.DesktopPairingInvitation
import com.virlin.app.platform.desktop.PinnedDesktopTls
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF19231D)
private val Muted = Color(0xFF607267)
private val Green = Color(0xFF087858)
private val Cream = Color(0xFFF9F8F5)
private val SoftGreen = Color(0xFFF0F9F2)

/** Pairing UI only; no task, note, file, or desktop-workspace API is exposed yet. */
@Composable
fun DesktopConnectScreen(onBack: () -> Unit) {
    var invitation by remember { mutableStateOf<DesktopPairingInvitation?>(null) }
    var connection by remember { mutableStateOf<SSLSocket?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Capture the socket for this effect. Reading the mutable state in onDispose
    // closes the *new* socket when pairing changes null -> connected.
    DisposableEffect(connection) {
        val ownedSocket = connection
        onDispose { ownedSocket?.close() }
    }
    LaunchedEffect(connection) {
        val observedSocket = connection ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                while (isActive && !observedSocket.isClosed) {
                    try {
                        if (observedSocket.inputStream.read() < 0) break
                    } catch (_: SocketTimeoutException) {
                        // The desktop sends no app data yet; keep waiting for a close.
                    }
                }
            } catch (_: IOException) {
                // A closed or interrupted socket is reported below.
            }
        }
        if (connection === observedSocket) {
            connection = null
            status = "Computer disconnected. Scan a fresh code to reconnect."
        }
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val scanned = result.contents
        if (scanned != null) {
            val parsed = DesktopPairingInvitation.parse(scanned, System.currentTimeMillis() / 1000)
            invitation = parsed
            status = if (parsed == null) "This Virlin Link code is invalid or expired. Generate a new one on the computer." else null
        }
    }

    Column(
        Modifier.fillMaxSize().background(Cream).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp).testTag("virlin_link_screen"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Ink)
            }
            Text("Virlin Link", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Ink,
                modifier = Modifier.padding(start = 8.dp))
        }
        Spacer(Modifier.height(28.dp))
        Image(painterResource(R.drawable.virlin_app_link), contentDescription = null,
            modifier = Modifier.size(80.dp))
        Spacer(Modifier.height(18.dp))
        Text("Virlin, wherever you work.", fontSize = 27.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.height(8.dp))
        Text("Connect over Wi-Fi, a hotspot, or a USB-tethered cable. Your projects remain on your phone.",
            fontSize = 15.sp, color = Muted, lineHeight = 22.sp)
        Spacer(Modifier.height(8.dp))
        Text("For USB without debugging, connect the cable and turn on USB tethering in the phone's Network & internet settings before scanning the computer's code.",
            fontSize = 13.sp, color = Muted, lineHeight = 19.sp)
        Spacer(Modifier.height(28.dp))

        Column(
            Modifier.fillMaxWidth().background(SoftGreen, RoundedCornerShape(20.dp))
                .border(1.dp, Color(0xFFCBE8D7), RoundedCornerShape(20.dp)).padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (connection == null) Icons.Default.QrCodeScanner else Icons.Default.CheckCircle,
                    contentDescription = null, tint = Green, modifier = Modifier.size(25.dp))
                Text(if (connection == null) "Scan a computer" else "Computer connected",
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (connection == null) "Scan the one-time code shown by Virlin Connect, then approve the connection."
                else "Secure connection active. Keep this page open while testing the link.",
                fontSize = 14.sp, lineHeight = 20.sp, color = Muted,
            )
            Spacer(Modifier.height(18.dp))
            if (connection == null) {
                Button(
                    onClick = {
                        invitation = null
                        status = null
                        scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Scan the Virlin Connect QR").setBeepEnabled(false)
                            .setCaptureActivity(VirlinQrCaptureActivity::class.java)
                            .setOrientationLocked(false))
                    }, enabled = !connecting, modifier = Modifier.fillMaxWidth().height(48.dp).testTag("virlin_link_scan"),
                    colors = ButtonDefaults.buttonColors(containerColor = Green),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Open scanner", fontSize = 15.sp)
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null,
                        modifier = Modifier.padding(start = 8.dp).size(18.dp))
                }
            } else {
                OutlinedButton(onClick = {
                    connection?.close()
                    connection = null
                    status = "Disconnected. Scan a fresh code to reconnect."
                }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) {
                    Text("Disconnect")
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth().border(1.dp, Color(0xFFE0E8E1), RoundedCornerShape(16.dp))
                .padding(16.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.DesktopWindows, contentDescription = null, tint = Muted, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f).padding(start = 13.dp)) {
                Text("Open desktop workspace", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Muted)
                Text("Coming in a later update", fontSize = 13.sp, color = Muted)
            }
            Icon(Icons.Default.Lock, contentDescription = "Not available yet", tint = Muted,
                modifier = Modifier.size(20.dp))
        }
        if (status != null) {
            Spacer(Modifier.height(18.dp))
            Text(status.orEmpty(), color = if (connecting) Green else if (status!!.startsWith("Disconnected")) Muted else Color(0xFF9F3D32),
                fontSize = 14.sp, lineHeight = 20.sp)
        }
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Security, contentDescription = null, tint = Green, modifier = Modifier.size(18.dp))
            Text("Private local link · No USB debugging for pairing", color = Muted, fontSize = 13.sp)
        }
        Spacer(Modifier.height(18.dp))
    }

    val pending = invitation
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { invitation = null },
            title = { Text("Approve this computer?") },
            text = { Text("Connect securely to ${pending.host}? This version shares pairing status only; it does not give the computer access to your projects or files.") },
            confirmButton = {
                Button(onClick = {
                    invitation = null
                    connecting = true
                    status = "Verifying this computer…"
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { connectAndApprove(pending) }
                        connecting = false
                        result.onSuccess {
                            connection = it
                            status = null
                        }.onFailure {
                            status = when (it) {
                                is SocketTimeoutException -> "Connection timed out. Check the selected Wi-Fi or USB-tethered network and Windows firewall permission."
                                else -> "Could not pair: ${it.message ?: "Connection failed"}. Generate a new code and try again."
                            }
                        }
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text("Approve") }
            },
            dismissButton = { OutlinedButton(onClick = { invitation = null }) { Text("Cancel") } },
        )
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
