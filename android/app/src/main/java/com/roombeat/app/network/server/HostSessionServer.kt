package com.roombeat.app.network.server

import com.roombeat.app.network.NetworkInterfaceHelper
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
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Embedded host control socket server for RoomBeat.
 * Binds to local IP and default port 8080, falling back to 8081..8089 if occupied.
 * Manages concurrent peer control sockets (up to 8+ simultaneous clients) with thread-safe messaging.
 */
class HostSessionServer(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    enum class ServerState {
        STOPPED,
        STARTING,
        RUNNING,
        ERROR
    }

    data class ClientSession(
        val clientId: String,
        val remoteAddress: String,
        val connectedAtMs: Long = System.currentTimeMillis()
    )

    interface HostSessionListener {
        fun onServerStarted(host: String, port: Int) {}
        fun onServerStopped() {}
        fun onClientConnected(clientId: String, remoteAddress: String) {}
        fun onClientDisconnected(clientId: String, reason: String?) {}
        fun onMessageReceived(clientId: String, message: String) {}
        fun onError(error: Throwable) {}
    }

    private val _state = MutableStateFlow(ServerState.STOPPED)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    var listener: HostSessionListener? = null

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    var boundPort: Int? = null
        private set

    var boundAddress: String? = null
        private set

    val isRunning: Boolean
        get() = _state.value == ServerState.RUNNING

    private val clients = ConcurrentHashMap<String, ConnectedClient>()
    private val clientIdCounter = AtomicInteger(0)

    private class ConnectedClient(
        val id: String,
        val socket: Socket,
        val remoteAddress: String,
        val connectedAtMs: Long = System.currentTimeMillis()
    ) {
        private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
        private val writeLock = Any()
        var job: Job? = null

        fun send(message: String): Boolean {
            return try {
                synchronized(writeLock) {
                    if (socket.isClosed || !socket.isConnected) return false
                    writer.write(message)
                    if (!message.endsWith("\n")) {
                        writer.write("\n")
                    }
                    writer.flush()
                    true
                }
            } catch (_: Exception) {
                false
            }
        }

        fun close() {
            try {
                socket.close()
            } catch (_: Exception) {}
            job?.cancel()
        }
    }

    /**
     * Starts the host server on the local address.
     * Attempts binding to [preferredPort]. If already occupied, increments across [preferredPort]..[maxPort].
     *
     * @param bindHost Specific IP to bind to, or null to automatically resolve active Wi-Fi/Hotspot IP.
     * @param preferredPort Default port (8080).
     * @param maxPort Maximum fallback port (8089).
     * @return The port successfully bound to.
     * @throws IOException If all ports in the range are occupied or unavailable.
     */
    @Synchronized
    fun startServer(
        bindHost: String? = null,
        preferredPort: Int = NetworkInterfaceHelper.DEFAULT_SERVER_PORT,
        maxPort: Int = NetworkInterfaceHelper.PORT_FALLBACK_RANGE.last
    ): Int {
        if (_state.value == ServerState.RUNNING) {
            return boundPort ?: preferredPort
        }

        _state.value = ServerState.STARTING
        val targetHost = bindHost ?: NetworkInterfaceHelper.resolveBestHostAddress()
        val inetAddress = if (targetHost == "0.0.0.0" || targetHost.isBlank()) {
            null
        } else {
            InetAddress.getByName(targetHost)
        }

        var boundSocket: ServerSocket? = null
        var successfulPort = -1
        val bindErrors = mutableListOf<Throwable>()

        for (port in preferredPort..maxPort) {
            try {
                val candidateSocket = ServerSocket()
                candidateSocket.reuseAddress = true
                val endpoint = if (inetAddress != null) {
                    InetSocketAddress(inetAddress, port)
                } else {
                    InetSocketAddress(port)
                }
                candidateSocket.bind(endpoint, 50)
                boundSocket = candidateSocket
                successfulPort = port
                break
            } catch (e: BindException) {
                bindErrors.add(e)
            } catch (e: IOException) {
                bindErrors.add(e)
            }
        }

        if (boundSocket == null) {
            _state.value = ServerState.ERROR
            val ex = IOException(
                "Failed to bind HostSessionServer on any port in range $preferredPort..$maxPort for host $targetHost",
                bindErrors.lastOrNull()
            )
            listener?.onError(ex)
            throw ex
        }

        this.serverSocket = boundSocket
        this.boundPort = successfulPort
        this.boundAddress = targetHost
        _state.value = ServerState.RUNNING

        listener?.onServerStarted(targetHost, successfulPort)

        acceptJob = scope.launch(Dispatchers.IO) {
            acceptLoop(boundSocket)
        }

        return successfulPort
    }

    private suspend fun acceptLoop(serverSocket: ServerSocket) = withContext(Dispatchers.IO) {
        while (isActive && !serverSocket.isClosed) {
            try {
                val clientSocket = serverSocket.accept()
                clientSocket.tcpNoDelay = true
                clientSocket.keepAlive = true
                handleClientConnection(clientSocket)
            } catch (_: SocketException) {
                // Expected when server socket is closed during stopServer()
                break
            } catch (e: Exception) {
                if (isActive && !serverSocket.isClosed) {
                    listener?.onError(e)
                }
            }
        }
    }

    private fun handleClientConnection(socket: Socket) {
        val clientId = "peer-${clientIdCounter.incrementAndGet()}"
        val remoteAddress = socket.remoteSocketAddress?.toString() ?: "unknown"
        val client = ConnectedClient(clientId, socket, remoteAddress)
        clients[clientId] = client

        listener?.onClientConnected(clientId, remoteAddress)

        val job = scope.launch(Dispatchers.IO) {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                while (isActive && !socket.isClosed) {
                    val line = reader.readLine() ?: break // EOF
                    listener?.onMessageReceived(clientId, line)
                }
            } catch (_: SocketException) {
                // Connection closed or reset
            } catch (e: Exception) {
                if (!socket.isClosed) {
                    listener?.onError(e)
                }
            } finally {
                disconnectClient(clientId, "Client connection terminated")
            }
        }
        client.job = job
    }

    /**
     * Sends a message to a specific connected client.
     * Appends newline delimiter automatically if omitted.
     *
     * @return true if successfully sent, false if client not found or socket error.
     */
    fun sendMessage(clientId: String, message: String): Boolean {
        val client = clients[clientId] ?: return false
        return client.send(message)
    }

    /**
     * Broadcasts a message to all currently connected peer clients.
     *
     * @return Number of peers to which the message was successfully dispatched.
     */
    fun broadcastMessage(message: String): Int {
        var successCount = 0
        for (client in clients.values) {
            if (client.send(message)) {
                successCount++
            }
        }
        return successCount
    }

    /**
     * Disconnects a specific client by ID.
     */
    fun disconnectClient(clientId: String, reason: String? = null) {
        val client = clients.remove(clientId) ?: return
        client.close()
        listener?.onClientDisconnected(clientId, reason)
    }

    /**
     * Returns a snapshot list of currently connected peer client sessions.
     */
    fun getConnectedClients(): List<ClientSession> {
        return clients.values.map { ClientSession(it.id, it.remoteAddress, it.connectedAtMs) }
    }

    /**
     * Returns the number of currently connected peer clients.
     */
    fun getConnectedClientCount(): Int = clients.size

    /**
     * Stops the server, closes all active peer connections, and releases sockets.
     */
    @Synchronized
    fun stopServer() {
        if (_state.value == ServerState.STOPPED) return

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        acceptJob?.cancel()
        acceptJob = null

        val currentClients = ArrayList(clients.values)
        clients.clear()
        for (client in currentClients) {
            try {
                client.close()
                listener?.onClientDisconnected(client.id, "Server stopped")
            } catch (_: Exception) {}
        }

        boundPort = null
        boundAddress = null
        _state.value = ServerState.STOPPED
        listener?.onServerStopped()
    }
}
