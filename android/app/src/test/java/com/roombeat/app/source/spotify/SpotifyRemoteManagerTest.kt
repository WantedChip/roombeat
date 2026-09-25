package com.roombeat.app.source.spotify

import android.net.Uri
import com.roombeat.app.protocol.PacketContext
import com.roombeat.app.protocol.PacketDispatcher
import com.roombeat.app.protocol.RoomBeatPacket
import com.spotify.android.appremote.api.error.AuthenticationFailedException
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.OfflineModeException
import com.spotify.android.appremote.api.error.SpotifyConnectionTerminatedException
import com.spotify.android.appremote.api.error.SpotifyDisconnectedException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SpotifyRemoteManagerTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var fakeConnector: FakeSpotifyConnector
    private lateinit var manager: SpotifyRemoteManager

    @Before
    fun setUp() {
        fakeConnector = FakeSpotifyConnector(isInstalled = true, autoRespondConnected = true)
        manager = SpotifyRemoteManager(
            clientId = "test-client-id",
            redirectUri = "roombeat://spotify-callback",
            connector = fakeConnector,
            dispatcher = testDispatcher,
            scope = testScope
        )
    }

    @After
    fun tearDown() {
        manager.close()
    }

    @Test
    fun testInitialStateIsDisconnected() {
        assertEquals(SpotifyAuthState.Disconnected, manager.authState.value)
        assertFalse(manager.isConnected)
        assertNull(manager.currentRemote)
        assertEquals(0L, manager.lastConnectedAtMs)
    }

    @Test
    fun testSuccessfulWarmUpFlow() {
        manager.initiateWarmUp(null, showAuthView = true)

        assertEquals(1, fakeConnector.connectCallCount)
        assertEquals("test-client-id", fakeConnector.lastClientId)
        assertEquals("roombeat://spotify-callback", fakeConnector.lastRedirectUri)
        assertTrue(fakeConnector.lastShowAuthView)

        assertTrue(manager.isConnected)
        assertTrue(manager.authState.value is SpotifyAuthState.Connected)
        assertNotNull(manager.currentRemote)
        assertTrue(manager.currentRemote?.isConnected == true)
        assertTrue(manager.lastConnectedAtMs > 0L)
    }

    @Test
    fun testWarmUpWhenAlreadyConnectedIsNoOp() {
        manager.initiateWarmUp(null)
        assertEquals(1, fakeConnector.connectCallCount)
        assertTrue(manager.isConnected)

        // Second call should be ignored
        manager.initiateWarmUp(null)
        assertEquals(1, fakeConnector.connectCallCount)
    }

    @Test
    fun testWarmUpWhenAlreadyConnectingIsNoOp() {
        fakeConnector.autoRespondConnected = false

        manager.initiateWarmUp(null)
        assertEquals(1, fakeConnector.connectCallCount)
        assertTrue(manager.authState.value is SpotifyAuthState.Connecting)

        // Attempting another warm-up while in connecting state
        manager.initiateWarmUp(null)
        assertEquals(1, fakeConnector.connectCallCount)

        // Completing connection now
        fakeConnector.triggerConnected()
        assertTrue(manager.isConnected)
    }

    @Test
    fun testFailureCouldNotFindSpotifyApp() {
        fakeConnector.isInstalled = false

        manager.initiateWarmUp(null)

        val state = manager.authState.value
        assertTrue(state is SpotifyAuthState.Error)
        val error = state as SpotifyAuthState.Error
        assertEquals(SpotifyAuthErrorType.APP_NOT_INSTALLED, error.errorType)
        assertTrue(error.isAppMissing)
        assertFalse(manager.isConnected)
        assertNull(manager.currentRemote)
    }

    @Test
    fun testFailureNotLoggedInException() {
        fakeConnector.failureToEmit = NotLoggedInException("User is not signed in", null)

        manager.initiateWarmUp(null)

        val state = manager.authState.value
        assertTrue(state is SpotifyAuthState.Error)
        val error = state as SpotifyAuthState.Error
        assertEquals(SpotifyAuthErrorType.NOT_LOGGED_IN, error.errorType)
        assertTrue(error.isNotLoggedIn)
    }

    @Test
    fun testFailureUserNotAuthorizedException() {
        fakeConnector.failureToEmit = UserNotAuthorizedException("User denied consent", null)

        manager.initiateWarmUp(null)

        val state = manager.authState.value
        assertTrue(state is SpotifyAuthState.Error)
        val error = state as SpotifyAuthState.Error
        assertEquals(SpotifyAuthErrorType.USER_NOT_AUTHORIZED, error.errorType)
        assertTrue(error.isAuthDenied)
    }

    @Test
    fun testFailureAuthenticationFailedException() {
        fakeConnector.failureToEmit = AuthenticationFailedException("Client auth failed", null)

        manager.initiateWarmUp(null)

        val state = manager.authState.value
        assertTrue(state is SpotifyAuthState.Error)
        val error = state as SpotifyAuthState.Error
        assertEquals(SpotifyAuthErrorType.AUTHENTICATION_FAILED, error.errorType)
        assertTrue(error.isAuthDenied)
    }

    @Test
    fun testFailureOfflineModeException() {
        fakeConnector.failureToEmit = OfflineModeException("Spotify offline", null)

        manager.initiateWarmUp(null)

        val state = manager.authState.value
        assertTrue(state is SpotifyAuthState.Error)
        val error = state as SpotifyAuthState.Error
        assertEquals(SpotifyAuthErrorType.OFFLINE_MODE, error.errorType)
    }

    @Test
    fun testFailureSpotifyConnectionTerminatedException() {
        fakeConnector.failureToEmit = SpotifyConnectionTerminatedException()

        manager.initiateWarmUp(null)

        val state = manager.authState.value
        assertTrue(state is SpotifyAuthState.Error)
        val error = state as SpotifyAuthState.Error
        assertEquals(SpotifyAuthErrorType.CONNECTION_TERMINATED, error.errorType)
    }

    @Test
    fun testFailureSpotifyDisconnectedException() {
        fakeConnector.failureToEmit = SpotifyDisconnectedException()

        manager.initiateWarmUp(null)

        val state = manager.authState.value
        assertTrue(state is SpotifyAuthState.Error)
        val error = state as SpotifyAuthState.Error
        assertEquals(SpotifyAuthErrorType.CONNECTION_TERMINATED, error.errorType)
    }

    @Test
    fun testFailureGenericExceptionFallbacks() {
        // App not installed string fallback
        val notInstalledError = SpotifyRemoteManager.mapThrowableToErrorType(IOException("Could not find Spotify on device"))
        assertEquals(SpotifyAuthErrorType.APP_NOT_INSTALLED, notInstalledError)

        // Not logged in string fallback
        val notLoggedInError = SpotifyRemoteManager.mapThrowableToErrorType(RuntimeException("User is not logged in"))
        assertEquals(SpotifyAuthErrorType.NOT_LOGGED_IN, notLoggedInError)

        // Denied string fallback
        val deniedError = SpotifyRemoteManager.mapThrowableToErrorType(IllegalStateException("Authorization was denied by user"))
        assertEquals(SpotifyAuthErrorType.USER_NOT_AUTHORIZED, deniedError)

        // Timeout string fallback
        val timeoutError = SpotifyRemoteManager.mapThrowableToErrorType(RuntimeException("Connection timeout occurred"))
        assertEquals(SpotifyAuthErrorType.TIMEOUT, timeoutError)

        // Unknown fallback
        val unknownError = SpotifyRemoteManager.mapThrowableToErrorType(NullPointerException("Something unexpected"))
        assertEquals(SpotifyAuthErrorType.UNKNOWN, unknownError)
    }

    @Test
    fun testSuspendWarmUpSuccess() = runTest(testDispatcher) {
        val result = manager.warmUp(null, showAuthView = true)

        assertTrue(result is SpotifyAuthState.Connected)
        assertTrue(manager.isConnected)
    }

    @Test
    fun testSuspendWarmUpFailure() = runTest(testDispatcher) {
        fakeConnector.failureToEmit = NotLoggedInException("Need login", null)

        val result = manager.warmUp(null, showAuthView = true)

        assertTrue(result is SpotifyAuthState.Error)
        val error = result as SpotifyAuthState.Error
        assertEquals(SpotifyAuthErrorType.NOT_LOGGED_IN, error.errorType)
        assertFalse(manager.isConnected)
    }

    @Test
    fun testSuspendWarmUpTimeout() = runTest(testDispatcher) {
        fakeConnector.autoRespondConnected = false

        val warmUpJob = testScope.runTest {
            manager.warmUp(null, showAuthView = true, timeoutMs = 2000L)
        }

        // Advance beyond timeout
        advanceTimeBy(3000L)

        val state = manager.authState.value
        assertTrue(state is SpotifyAuthState.Error)
        val error = state as SpotifyAuthState.Error
        assertEquals(SpotifyAuthErrorType.TIMEOUT, error.errorType)
    }

    @Test
    fun testDisconnect() {
        manager.initiateWarmUp(null)
        assertTrue(manager.isConnected)
        val remote = manager.currentRemote as FakeSpotifyAppRemoteFacade

        manager.disconnect()

        assertFalse(manager.isConnected)
        assertNull(manager.currentRemote)
        assertEquals(SpotifyAuthState.Disconnected, manager.authState.value)
        assertEquals(1, fakeConnector.disconnectCallCount)
        assertEquals(1, remote.disconnectCallCount)
    }

    @Test
    fun testResetState() {
        manager.initiateWarmUp(null)
        assertTrue(manager.isConnected)

        manager.resetState()

        assertFalse(manager.isConnected)
        assertEquals(SpotifyAuthState.Disconnected, manager.authState.value)
    }

    @Test
    fun testCreateSpotifyWarmPacket() {
        val before = System.currentTimeMillis()
        val packet = manager.createSpotifyWarmPacket()
        val after = System.currentTimeMillis()

        assertEquals(RoomBeatPacket.TYPE_SPOTIFY_WARM, packet.packetType)
        assertTrue(packet.timestampMs in before..after)
    }

    @Test
    fun testHandleSpotifyWarmPacketInitiatesWarmUp() {
        assertFalse(manager.isConnected)

        val warmPacket = RoomBeatPacket.SpotifyWarm(timestampMs = 123456L)
        manager.handleSpotifyWarmPacket(null, warmPacket)

        assertEquals(1, fakeConnector.connectCallCount)
        assertFalse(fakeConnector.lastShowAuthView) // Background pre-warm avoids interrupting UI
        assertTrue(manager.isConnected)
    }

    @Test
    fun testHandleSpotifyWarmPacketWhenAlreadyConnectedDoesNotReconnect() {
        manager.initiateWarmUp(null)
        assertEquals(1, fakeConnector.connectCallCount)

        val warmPacket = RoomBeatPacket.SpotifyWarm(timestampMs = 123456L)
        manager.handleSpotifyWarmPacket(null, warmPacket)

        assertEquals(1, fakeConnector.connectCallCount)
    }

    @Test
    fun testRegisterPacketHandlerWithDispatcher() = runTest(testDispatcher) {
        val dispatcher = PacketDispatcher(testDispatcher)
        val registration = manager.registerPacketHandler(dispatcher, null)

        assertFalse(manager.isConnected)

        val packet = RoomBeatPacket.SpotifyWarm(timestampMs = 5000L)
        dispatcher.dispatch(packet, PacketContext(senderId = "peer-host"))
        advanceUntilIdle()

        assertTrue(manager.isConnected)
        assertEquals(1, fakeConnector.connectCallCount)

        // Test unregister
        registration.unregister()
        manager.disconnect()
        assertEquals(SpotifyAuthState.Disconnected, manager.authState.value)

        dispatcher.dispatch(packet, PacketContext(senderId = "peer-host"))
        advanceUntilIdle()

        // Should not have reconnected
        assertFalse(manager.isConnected)
    }

    @Test
    fun testBroadcastWarmHandshake() = runTest(testDispatcher) {
        var broadcastPacket: RoomBeatPacket? = null
        manager.broadcastWarmHandshake { packet ->
            broadcastPacket = packet
        }

        assertNotNull(broadcastPacket)
        assertEquals(RoomBeatPacket.TYPE_SPOTIFY_WARM, broadcastPacket?.packetType)
        assertTrue((broadcastPacket as RoomBeatPacket.SpotifyWarm).timestampMs > 0L)
    }

    @Test
    fun testOnAuthRedirectHandling() {
        // Valid redirect without error
        val validUri = "roombeat://spotify-callback?code=AQD123"
        assertTrue(manager.onAuthRedirect(validUri))

        // Valid redirect with error parameter
        val errorUri = "roombeat://spotify-callback?error=access_denied"
        assertTrue(manager.onAuthRedirect(errorUri))
        val state = manager.authState.value
        assertTrue(state is SpotifyAuthState.Error)
        assertEquals(SpotifyAuthErrorType.USER_NOT_AUTHORIZED, (state as SpotifyAuthState.Error).errorType)

        // Non-matching URI
        val otherUri = "https://spotify.com/callback"
        assertFalse(manager.onAuthRedirect(otherUri))
    }

    @Test
    fun testStaticHandleAuthRedirect() {
        val uri = "roombeat://spotify-callback?code=AQD999"
        val handled = SpotifyRemoteManager.handleAuthRedirect(uri)
        assertTrue(handled)
    }

    @Test
    fun testIsSpotifyInstalledDelegation() {
        assertTrue(manager.connector.isSpotifyInstalled(null))
        fakeConnector.isInstalled = false
        assertFalse(manager.connector.isSpotifyInstalled(null))
    }
}
