package com.roombeat.app.network.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Socket factory abstraction to allow injection in unit tests.
 */
fun interface SocketFactory {
    fun createSocket(): Socket
}

/**
 * Peer socket client connection manager for RoomBeat.
 * Handles client socket lifecycle, sending/receiving newline-delimited messages,
 * connection timeout detection (>5s), and automatic reconnection attempts.
 */
class ClientSocketConnection(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val socketFactory: SocketFactory = SocketFactory { Socket() }
) {
    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5000
        const val DEFAULT_MAX_RECONNECT_ATTEMPTS = 3
        const val DEFAULT_RECONNECT_DELAY_MS = 1000L
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
        ERROR
    }

    interface ClientConnectionListener {
        fun onConnected() {}
        fun onDisconnected(reason: String?) {}
        fun onReconnecting(attempt: Int, maxAttempts: Int) {}
        fun onMessageReceived(message: String) {}
        fun onError(error: Throwable) {}
    }

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    var listener: ClientConnectionListener? = null

    var autoReconnect: Boolean = true
    var maxReconnectAttempts: Int = DEFAULT_MAX_RECONNECT_ATTEMPTS
    var reconnectDelayMs: Long = DEFAULT_RECONNECT_DELAY_MS
    var connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS

    val isConnected: Boolean
        get() = _state.value == ConnectionState.CONNECTED

    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private val writeLock = Any()

    var targetHost: String? = null
        private set
    var targetPort: Int = -1
        private set

    private val manualDisconnect = AtomicBoolean(false)
    private var readJob: Job? = null
    private var reconnectJob: Job? = null

    /**
     * Establishes a TCP connection to the host session server.
     *
     * @param host Target host address or IP.
     * @param port Target port.
     * @param timeoutMs Connection timeout in milliseconds (default 5000ms).
     * @return true if connected successfully, false if timed out or failed.
     */
    suspend fun connect(
        host: String,
        port: Int,
        timeoutMs: Int = connectTimeoutMs
    ): Boolean = withContext(Dispatchers.IO) {
        if (_state.value == ConnectionState.CONNECTED) {
            return@withContext true
        }

        targetHost = host
        targetPort = port
        connectTimeoutMs = timeoutMs
        manualDisconnect.set(false)

        reconnectJob?.cancel()
        reconnectJob = null

        _state.value = ConnectionState.CONNECTING

        try {
            val s = socketFactory.createSocket()
            s.tcpNoDelay = true
            s.keepAlive = true
            s.connect(InetSocketAddress(host, port), timeoutMs)

            synchronized(writeLock) {
                socket = s
                writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))
            }

            _state.value = ConnectionState.CONNECTED
            listener?.onConnected()

            startReadLoop(s)
            true
        } catch (e: SocketTimeoutException) {
            _state.value = ConnectionState.ERROR
            listener?.onError(e)
            false
        } catch (e: Exception) {
            _state.value = ConnectionState.ERROR
            listener?.onError(e)
            false
        }
    }

    /**
     * Asynchronously initiates a connection to the server.
     */
    fun connectAsync(
        host: String,
        port: Int,
        timeoutMs: Int = connectTimeoutMs
    ): Job = scope.launch {
        connect(host, port, timeoutMs)
    }

    private fun startReadLoop(s: Socket) {
        readJob?.cancel()
        readJob = scope.launch(Dispatchers.IO) {
            try {
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
                while (isActive && !s.isClosed) {
                    val line = reader.readLine() ?: break // EOF
                    listener?.onMessageReceived(line)
                }
            } catch (_: SocketException) {
                // Connection closed or reset
            } catch (e: Exception) {
                if (!s.isClosed) {
                    listener?.onError(e)
                }
            } finally {
                handleSocketClosed()
            }
        }
    }

    private fun handleSocketClosed() {
        cleanSocket()

        if (manualDisconnect.get()) {
            _state.value = ConnectionState.DISCONNECTED
            listener?.onDisconnected("Disconnected by user")
            return
        }

        if (autoReconnect && targetHost != null && targetPort > 0) {
            startReconnectLoop()
        } else {
            _state.value = ConnectionState.DISCONNECTED
            listener?.onDisconnected("Connection terminated")
        }
    }

    private fun startReconnectLoop() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch(Dispatchers.IO) {
            val host = targetHost ?: return@launch
            val port = targetPort
            var connected = false

            for (attempt in 1..maxReconnectAttempts) {
                if (manualDisconnect.get() || !isActive) break

                _state.value = ConnectionState.RECONNECTING
                listener?.onReconnecting(attempt, maxReconnectAttempts)

                delay(reconnectDelayMs)
                if (manualDisconnect.get() || !isActive) break

                try {
                    val s = socketFactory.createSocket()
                    s.tcpNoDelay = true
                    s.keepAlive = true
                    s.connect(InetSocketAddress(host, port), connectTimeoutMs)

                    synchronized(writeLock) {
                        socket = s
                        writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))
                    }

                    _state.value = ConnectionState.CONNECTED
                    listener?.onConnected()
                    startReadLoop(s)
                    connected = true
                    break
                } catch (_: Exception) {
                    // Try next attempt
                }
            }

            if (!connected && !manualDisconnect.get()) {
                _state.value = ConnectionState.DISCONNECTED
                listener?.onDisconnected("Failed to reconnect after $maxReconnectAttempts attempts")
            }
        }
    }

    /**
     * Sends a message over the control socket connection.
     * Automatically appends a newline delimiter if omitted.
     *
     * @param message Text payload to send.
     * @return true if sent successfully, false if connection is closed or error occurred.
     */
    fun sendMessage(message: String): Boolean {
        if (_state.value != ConnectionState.CONNECTED) return false

        return try {
            synchronized(writeLock) {
                val w = writer ?: return false
                val s = socket ?: return false
                if (s.isClosed || !s.isConnected) return false

                w.write(message)
                if (!message.endsWith("\n")) {
                    w.write("\n")
                }
                w.flush()
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun cleanSocket() {
        synchronized(writeLock) {
            try {
                socket?.close()
            } catch (_: Exception) {}
            socket = null
            writer = null
        }
    }

    /**
     * Disconnects the socket and ceases any pending reconnect attempts.
     */
    @Synchronized
    fun disconnect() {
        manualDisconnect.set(true)

        reconnectJob?.cancel()
        reconnectJob = null

        readJob?.cancel()
        readJob = null

        cleanSocket()

        _state.value = ConnectionState.DISCONNECTED
        listener?.onDisconnected("Disconnected by user")
    }
}
