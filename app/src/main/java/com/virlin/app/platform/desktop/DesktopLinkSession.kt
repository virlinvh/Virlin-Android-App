package com.virlin.app.platform.desktop

import android.content.Intent
import androidx.core.content.ContextCompat
import com.virlin.app.domain.VirlinGraph
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns the approved link independently of any Compose destination. */
object DesktopLinkSession {
    sealed interface State {
        data object Disconnected : State
        data object Connected : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var socket: SSLSocket? = null
    private var reader: Job? = null
    private var connecting = false
    private val _state = MutableStateFlow<State>(State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()

    suspend fun connect(invitation: DesktopPairingInvitation): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            synchronized(lock) {
                check(socket == null && !connecting) { "Disconnect the current computer first" }
                connecting = true
            }
            try {
                val context = VirlinGraph.desktopFilesContext()
                    ?: error("Virlin is not ready to connect")
                // Start while the approval screen is visible; Android then permits the local link
                // to remain active during a call, lock-screen, or app switch.
                ContextCompat.startForegroundService(context, Intent(context, DesktopLinkService::class.java))
                try {
                    val candidate = PinnedDesktopTls.connect(invitation, System.currentTimeMillis() / 1000)
                    try {
                        candidate.outputStream.write("PAIR ${invitation.secret}\n".toByteArray(Charsets.US_ASCII))
                        candidate.outputStream.flush()
                        val expected = "PAIR-OK\n".toByteArray(Charsets.US_ASCII)
                        val received = ByteArray(expected.size)
                        var offset = 0
                        while (offset < received.size) {
                            val count = try {
                                candidate.inputStream.read(received, offset, received.size - offset)
                            } catch (failure: SocketTimeoutException) {
                                throw DesktopConnectionFailure(DesktopConnectionFailure.Stage.APPROVAL, invitation.host, failure)
                            }
                            if (count < 0) throw IOException("Computer closed the connection")
                            offset += count
                        }
                        if (!MessageDigest.isEqual(expected, received)) throw IOException("Computer rejected pairing")
                        synchronized(lock) {
                            check(socket == null) { "Another computer is already connected" }
                            socket = candidate
                            _state.value = State.Connected
                            reader = scope.launch { serve(candidate) }
                        }
                    } catch (failure: Exception) {
                        candidate.close()
                        throw failure
                    }
                } catch (failure: Exception) {
                    context.stopService(Intent(context, DesktopLinkService::class.java))
                    throw failure
                }
            } finally {
                synchronized(lock) { connecting = false }
            }
        }
    }

    fun disconnect() {
        val context = VirlinGraph.desktopFilesContext()
        synchronized(lock) {
            val old = socket
            socket = null
            _state.value = State.Disconnected
            reader?.cancel()
            reader = null
            old?.close()
        }
        context?.stopService(Intent(context, DesktopLinkService::class.java))
    }

    private suspend fun serve(ownedSocket: SSLSocket) {
        val protocol = DesktopWorkspaceProtocol(VirlinGraph.repository, VirlinGraph.actions,
            VirlinGraph.desktopFilesContext())
        try {
            while (scope.isActive && !ownedSocket.isClosed) {
                try {
                    if (!protocol.next(ownedSocket.inputStream, ownedSocket.outputStream)) break
                } catch (_: SocketTimeoutException) {
                    // The desktop may be idle while the user navigates within Virlin.
                }
            }
        } catch (_: IOException) {
            // A remote close or network loss ends this session.
        } finally {
            protocol.close()
            var ended = false
            synchronized(lock) {
                if (socket === ownedSocket) {
                    socket = null
                    reader = null
                    _state.value = State.Disconnected
                    ended = true
                }
                ownedSocket.close()
            }
            if (ended) VirlinGraph.desktopFilesContext()?.let { context ->
                context.stopService(Intent(context, DesktopLinkService::class.java))
            }
        }
    }
}
