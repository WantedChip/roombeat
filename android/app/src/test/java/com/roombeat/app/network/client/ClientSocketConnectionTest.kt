package com.roombeat.app.network.client

import com.roombeat.app.network.server.HostSessionServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ClientSocketConnectionTest {

    private lateinit var server: HostSessionServer
    private lateinit var client: ClientSocketConnection
    private val loopbackHost = "127.0.0.1"

    @Before
    fun setUp() {
        server = HostSessionServer()
        client = ClientSocketConnection()
    }

    @After
    fun tearDown() {
        client.disconnect()
        server.stopServer()
    }

    @Test
    fun testConnectAndExchangeMessages() = runBlocking {
        val port = server.startServer(bindHost = loopbackHost, preferredPort = 8080, maxPort = 8089)

        val serverReceivedMessage = CountDownLatch(1)
        val clientReceivedMessage = CountDownLatch(1)
        var serverMsgText = ""
        var clientMsgText = ""

        server.listener = object : HostSessionServer.HostSessionListener {
            override fun onMessageReceived(clientId: String, message: String) {
                serverMsgText = message
                serverReceivedMessage.countDown()
                // Reply back to the client
                server.sendMessage(clientId, "REPLY_FROM_SERVER")
            }
        }

        client.listener = object : ClientSocketConnection.ClientConnectionListener {
            override fun onMessageReceived(message: String) {
                clientMsgText = message
                clientReceivedMessage.countDown()
            }
        }

        val connected = client.connect(loopbackHost, port)
        assertTrue(connected)
        assertTrue(client.isConnected)
        assertEquals(ClientSocketConnection.ConnectionState.CONNECTED, client.state.value)

        // Client sends message to server
        val sent = client.sendMessage("HELLO_FROM_CLIENT")
        assertTrue(sent)

        assertTrue("Server should receive client message", serverReceivedMessage.await(5, TimeUnit.SECONDS))
        assertEquals("HELLO_FROM_CLIENT", serverMsgText)

        assertTrue("Client should receive server reply", clientReceivedMessage.await(5, TimeUnit.SECONDS))
        assertEquals("REPLY_FROM_SERVER", clientMsgText)
    }

    @Test
    fun testConnectionTimeout() = runBlocking {
        assertEquals(5000, ClientSocketConnection.DEFAULT_CONNECT_TIMEOUT_MS)
        assertEquals(5000, client.connectTimeoutMs)

        val errorReported = CountDownLatch(1)
        var capturedError: Throwable? = null

        // Inject a socket factory that triggers a timeout exception
        val timeoutSocketFactory = SocketFactory {
            object : Socket() {
                override fun connect(endpoint: SocketAddress?, timeout: Int) {
                    throw SocketTimeoutException("Simulated connection timeout after ${timeout}ms")
                }
            }
        }

        val timeoutClient = ClientSocketConnection(socketFactory = timeoutSocketFactory)
        timeoutClient.listener = object : ClientSocketConnection.ClientConnectionListener {
            override fun onError(error: Throwable) {
                capturedError = error
                errorReported.countDown()
            }
        }

        val connected = timeoutClient.connect("10.255.255.1", 8080, timeoutMs = 5000)
        assertFalse(connected)
        assertEquals(ClientSocketConnection.ConnectionState.ERROR, timeoutClient.state.value)
        assertTrue(errorReported.await(2, TimeUnit.SECONDS))
        assertTrue(capturedError is SocketTimeoutException)
    }

    @Test
    fun testReconnectionLogicOnUnexpectedServerDrop() = runBlocking {
        val port = server.startServer(bindHost = loopbackHost, preferredPort = 8080, maxPort = 8089)

        client.reconnectDelayMs = 100L
        client.maxReconnectAttempts = 3

        val initialConnectLatch = CountDownLatch(1)
        val reconnectingLatch = CountDownLatch(1)
        val reconnectedLatch = CountDownLatch(1)
        val reconnectAttempts = AtomicInteger(0)

        var hasInitiallyConnected = false

        client.listener = object : ClientSocketConnection.ClientConnectionListener {
            override fun onConnected() {
                if (!hasInitiallyConnected) {
                    hasInitiallyConnected = true
                    initialConnectLatch.countDown()
                } else {
                    reconnectedLatch.countDown()
                }
            }

            override fun onReconnecting(attempt: Int, maxAttempts: Int) {
                reconnectAttempts.incrementAndGet()
                reconnectingLatch.countDown()
            }
        }

        assertTrue(client.connect(loopbackHost, port))
        assertTrue(initialConnectLatch.await(5, TimeUnit.SECONDS))

        // Force server to disconnect the client without client initiating disconnect
        val deadline = System.currentTimeMillis() + 5000
        var clients = server.getConnectedClients()
        while (clients.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            clients = server.getConnectedClients()
        }
        val connectedClient = clients.first()
        server.disconnectClient(connectedClient.clientId, "Server force drop")

        // Verify client enters reconnecting state
        assertTrue("Client should enter reconnecting state", reconnectingLatch.await(5, TimeUnit.SECONDS))
        assertTrue(reconnectAttempts.get() >= 1)

        // Server is still running and listening on the port, so the reconnect attempt should succeed
        assertTrue("Client should successfully reconnect", reconnectedLatch.await(5, TimeUnit.SECONDS))
        assertTrue(client.isConnected)
        assertEquals(ClientSocketConnection.ConnectionState.CONNECTED, client.state.value)
    }

    @Test
    fun testReconnectionExhaustionWhenServerStaysDown() = runBlocking {
        val port = server.startServer(bindHost = loopbackHost, preferredPort = 8080, maxPort = 8089)

        client.reconnectDelayMs = 50L
        client.maxReconnectAttempts = 2

        val initialConnectLatch = CountDownLatch(1)
        val disconnectedLatch = CountDownLatch(1)
        val reconnectAttempts = AtomicInteger(0)
        var disconnectReason: String? = null

        client.listener = object : ClientSocketConnection.ClientConnectionListener {
            override fun onConnected() {
                initialConnectLatch.countDown()
            }

            override fun onReconnecting(attempt: Int, maxAttempts: Int) {
                reconnectAttempts.incrementAndGet()
            }

            override fun onDisconnected(reason: String?) {
                disconnectReason = reason
                disconnectedLatch.countDown()
            }
        }

        assertTrue(client.connect(loopbackHost, port))
        assertTrue(initialConnectLatch.await(5, TimeUnit.SECONDS))

        // Stop server so all reconnect attempts fail
        server.stopServer()

        assertTrue("Client should disconnect after exhausting attempts", disconnectedLatch.await(5, TimeUnit.SECONDS))
        assertEquals(2, reconnectAttempts.get())
        assertEquals(ClientSocketConnection.ConnectionState.DISCONNECTED, client.state.value)
        assertFalse(client.isConnected)
        assertTrue(disconnectReason?.contains("Failed to reconnect") == true)
    }

    @Test
    fun testExplicitDisconnectDoesNotTriggerReconnect() = runBlocking {
        val port = server.startServer(bindHost = loopbackHost, preferredPort = 8080, maxPort = 8089)

        client.reconnectDelayMs = 50L
        client.maxReconnectAttempts = 3

        val reconnectTriggered = AtomicBoolean(false)
        val disconnectLatch = CountDownLatch(1)

        client.listener = object : ClientSocketConnection.ClientConnectionListener {
            override fun onReconnecting(attempt: Int, maxAttempts: Int) {
                reconnectTriggered.set(true)
            }

            override fun onDisconnected(reason: String?) {
                disconnectLatch.countDown()
            }
        }

        assertTrue(client.connect(loopbackHost, port))
        assertTrue(client.isConnected)

        // Explicit disconnect
        client.disconnect()

        assertTrue(disconnectLatch.await(2, TimeUnit.SECONDS))
        assertEquals(ClientSocketConnection.ConnectionState.DISCONNECTED, client.state.value)
        assertFalse(client.isConnected)

        // Wait to verify no reconnect was scheduled
        Thread.sleep(150)
        assertFalse("Explicit disconnect must not trigger reconnect", reconnectTriggered.get())
    }
}
