package com.roombeat.app.network.server

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HostSessionServerTest {

    private lateinit var server: HostSessionServer
    private val loopbackHost = "127.0.0.1"

    @Before
    fun setUp() {
        server = HostSessionServer()
    }

    @After
    fun tearDown() {
        server.stopServer()
    }

    @Test
    fun testServerBindsAndListensOnLocalAddress() {
        val port = server.startServer(bindHost = loopbackHost, preferredPort = 8080, maxPort = 8089)
        assertTrue(server.isRunning)
        assertEquals(port, server.boundPort)
        assertEquals(loopbackHost, server.boundAddress)

        // Connect a raw socket to verify it's listening and accepting connections
        val socket = Socket()
        socket.connect(InetSocketAddress(loopbackHost, port), 2000)
        assertTrue(socket.isConnected)
        socket.close()
    }

    @Test
    fun testPortConflictAutomaticallyIncrements() {
        // Occupy port 8080 with a dummy ServerSocket
        val blocker8080 = ServerSocket()
        blocker8080.reuseAddress = true
        blocker8080.bind(InetSocketAddress(InetAddress.getByName(loopbackHost), 8080))

        try {
            // Start HostSessionServer with preferredPort = 8080
            val boundPort = server.startServer(bindHost = loopbackHost, preferredPort = 8080, maxPort = 8085)
            // It should automatically fall back to 8081
            assertEquals(8081, boundPort)
            assertEquals(8081, server.boundPort)
            assertTrue(server.isRunning)
        } finally {
            blocker8080.close()
        }
    }

    @Test(expected = IOException::class)
    fun testPortExhaustionThrowsIOException() {
        val blocker1 = ServerSocket()
        val blocker2 = ServerSocket()
        try {
            blocker1.reuseAddress = true
            blocker1.bind(InetSocketAddress(InetAddress.getByName(loopbackHost), 8080))
            blocker2.reuseAddress = true
            blocker2.bind(InetSocketAddress(InetAddress.getByName(loopbackHost), 8081))

            // Range is 8080..8081, both occupied -> should throw IOException
            server.startServer(bindHost = loopbackHost, preferredPort = 8080, maxPort = 8081)
        } finally {
            blocker1.close()
            blocker2.close()
        }
    }

    @Test
    fun testMultiplePeerClientsConcurrentCommunication() {
        val clientCount = 8
        val port = server.startServer(bindHost = loopbackHost, preferredPort = 8080, maxPort = 8089)

        val connectedLatch = CountDownLatch(clientCount)
        val messagesReceivedByServer = CopyOnWriteArrayList<Pair<String, String>>()
        val serverMessageLatch = CountDownLatch(clientCount)

        server.listener = object : HostSessionServer.HostSessionListener {
            override fun onClientConnected(clientId: String, remoteAddress: String) {
                connectedLatch.countDown()
            }

            override fun onMessageReceived(clientId: String, message: String) {
                messagesReceivedByServer.add(clientId to message)
                serverMessageLatch.countDown()
            }
        }

        // Connect 8 concurrent client sockets
        val clientSockets = mutableListOf<Socket>()
        val clientReaders = mutableListOf<BufferedReader>()
        val clientWriters = mutableListOf<BufferedWriter>()

        for (i in 0 until clientCount) {
            val socket = Socket()
            socket.connect(InetSocketAddress(loopbackHost, port), 2000)
            socket.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
            clientSockets.add(socket)
            clientReaders.add(reader)
            clientWriters.add(writer)
        }

        // Wait for server to recognize all 8 clients
        assertTrue("All 8 clients should connect", connectedLatch.await(5, TimeUnit.SECONDS))
        assertEquals(clientCount, server.getConnectedClientCount())

        // Each client sends a unique message to the server
        for (i in 0 until clientCount) {
            clientWriters[i].write("HELLO_FROM_CLIENT_$i\n")
            clientWriters[i].flush()
        }

        assertTrue("Server should receive all 8 messages", serverMessageLatch.await(5, TimeUnit.SECONDS))
        assertEquals(clientCount, messagesReceivedByServer.size)

        // Server broadcasts a message to all 8 clients
        val broadcastPayload = "BROADCAST_SYNC_START"
        val dispatchedCount = server.broadcastMessage(broadcastPayload)
        assertEquals(clientCount, dispatchedCount)

        // Verify each client receives the broadcast message
        for (i in 0 until clientCount) {
            val received = clientReaders[i].readLine()
            assertEquals(broadcastPayload, received)
        }

        // Clean up client sockets
        for (socket in clientSockets) {
            socket.close()
        }
    }

    @Test
    fun testCleanServerTerminationAndSocketRelease() {
        val port = server.startServer(bindHost = loopbackHost, preferredPort = 8080, maxPort = 8089)
        assertTrue(server.isRunning)

        // Connect a client socket
        val clientSocket = Socket()
        clientSocket.connect(InetSocketAddress(loopbackHost, port), 2000)
        assertTrue(clientSocket.isConnected)

        val stoppedLatch = CountDownLatch(1)
        server.listener = object : HostSessionServer.HostSessionListener {
            override fun onServerStopped() {
                stoppedLatch.countDown()
            }
        }

        // Stop server
        server.stopServer()

        assertTrue(stoppedLatch.await(3, TimeUnit.SECONDS))
        assertFalse(server.isRunning)
        assertEquals(0, server.getConnectedClientCount())

        // Verify the port is immediately releasable and can be bound by another socket
        val newSocket = ServerSocket()
        newSocket.reuseAddress = true
        newSocket.bind(InetSocketAddress(InetAddress.getByName(loopbackHost), port))
        assertTrue(newSocket.isBound)
        newSocket.close()

        clientSocket.close()
    }

    @Test
    fun testSendMessageToSpecificClient() {
        val port = server.startServer(bindHost = loopbackHost, preferredPort = 8080, maxPort = 8089)
        val connectedClientIds = CopyOnWriteArrayList<String>()
        val clientConnectedLatch = CountDownLatch(2)

        server.listener = object : HostSessionServer.HostSessionListener {
            override fun onClientConnected(clientId: String, remoteAddress: String) {
                connectedClientIds.add(clientId)
                clientConnectedLatch.countDown()
            }
        }

        val socket1 = Socket()
        socket1.connect(InetSocketAddress(loopbackHost, port), 2000)
        val reader1 = BufferedReader(InputStreamReader(socket1.getInputStream(), StandardCharsets.UTF_8))

        val socket2 = Socket()
        socket2.connect(InetSocketAddress(loopbackHost, port), 2000)
        val reader2 = BufferedReader(InputStreamReader(socket2.getInputStream(), StandardCharsets.UTF_8))

        assertTrue(clientConnectedLatch.await(5, TimeUnit.SECONDS))
        assertEquals(2, connectedClientIds.size)

        val targetClientId = connectedClientIds[0]
        val privateMessage = "TARGETED_MESSAGE_FOR_CLIENT_0"
        val sent = server.sendMessage(targetClientId, privateMessage)
        assertTrue(sent)

        val received1 = reader1.readLine()
        assertEquals(privateMessage, received1)

        // Verify client 2 did not receive this message
        socket1.close()
        socket2.close()
    }

    @Test
    fun testDisconnectClient() {
        val port = server.startServer(bindHost = loopbackHost, preferredPort = 8080, maxPort = 8089)
        val connectedClientId = CopyOnWriteArrayList<String>()
        val connectLatch = CountDownLatch(1)
        val disconnectLatch = CountDownLatch(1)

        server.listener = object : HostSessionServer.HostSessionListener {
            override fun onClientConnected(clientId: String, remoteAddress: String) {
                connectedClientId.add(clientId)
                connectLatch.countDown()
            }

            override fun onClientDisconnected(clientId: String, reason: String?) {
                disconnectLatch.countDown()
            }
        }

        val socket = Socket()
        socket.connect(InetSocketAddress(loopbackHost, port), 2000)
        assertTrue(connectLatch.await(5, TimeUnit.SECONDS))
        assertEquals(1, server.getConnectedClientCount())

        server.disconnectClient(connectedClientId.first(), "Kicked by host")
        assertTrue(disconnectLatch.await(3, TimeUnit.SECONDS))
        assertEquals(0, server.getConnectedClientCount())

        socket.close()
    }
}
