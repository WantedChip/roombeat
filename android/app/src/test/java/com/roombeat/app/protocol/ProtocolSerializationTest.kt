package com.roombeat.app.protocol

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ProtocolSerializationTest {

    private lateinit var testDispatcher: CoroutineDispatcher
    private lateinit var testScope: TestScope
    private lateinit var dispatcher: PacketDispatcher

    @Before
    fun setUp() {
        testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
        dispatcher = PacketDispatcher(
            defaultDispatcher = testDispatcher,
            scope = testScope
        )
    }

    // =========================================================================
    // 1. Round-Trip Serialization & Deserialization Tests (All 22 Packet Types)
    // =========================================================================

    @Test
    fun testCalibProbeRoundTrip() {
        val original = RoomBeatPacket.CalibProbe(t0 = 1726000000000L)
        val json = PacketSerializer.serialize(original)

        assertTrue(json.contains("\"type\":\"CALIB_PROBE\""))
        assertTrue(json.contains("\"t0\":1726000000000"))

        val deserialized = PacketSerializer.deserialize(json)
        assertNotNull(deserialized)
        assertTrue(deserialized is RoomBeatPacket.CalibProbe)
        assertEquals(original, deserialized)
        assertEquals(RoomBeatPacket.TYPE_CALIB_PROBE, (deserialized as RoomBeatPacket.CalibProbe).packetType)
    }

    @Test
    fun testCalibEchoRoundTrip() {
        val original = RoomBeatPacket.CalibEcho(
            t0 = 1000L,
            t1 = 1010L,
            t2 = 1012L
        )
        val json = PacketSerializer.serialize(original)

        assertTrue(json.contains("\"type\":\"CALIB_ECHO\""))
        assertTrue(json.contains("\"t0\":1000"))
        assertTrue(json.contains("\"t1\":1010"))
        assertTrue(json.contains("\"t2\":1012"))

        val deserialized = PacketSerializer.deserialize(json)
        assertNotNull(deserialized)
        assertTrue(deserialized is RoomBeatPacket.CalibEcho)
        assertEquals(original, deserialized)
    }

    @Test
    fun testCalibResultRoundTrip() {
        val original = RoomBeatPacket.CalibResult(
            offsetMs = -4.25,
            rttMs = 12.8
        )
        val json = PacketSerializer.serialize(original)

        assertTrue(json.contains("\"type\":\"CALIB_RESULT\""))
        assertTrue(json.contains("\"offset_ms\":-4.25"))
        assertTrue(json.contains("\"rtt_ms\":12.8"))

        val deserialized = PacketSerializer.deserialize(json)
        assertNotNull(deserialized)
        assertTrue(deserialized is RoomBeatPacket.CalibResult)
        assertEquals(original, deserialized)
    }

    @Test
    fun testRoomJoinRoundTrip() {
        val original = RoomBeatPacket.RoomJoin(code = "481923")
        val json = PacketSerializer.serialize(original)

        assertTrue(json.contains("\"type\":\"ROOM_JOIN\""))
        assertTrue(json.contains("\"code\":\"481923\""))

        val deserialized = PacketSerializer.deserialize(json)
        assertNotNull(deserialized)
        assertTrue(deserialized is RoomBeatPacket.RoomJoin)
        assertEquals(original, deserialized)
    }

    @Test
    fun testRoomJoinQrRoundTrip() {
        val original = RoomBeatPacket.RoomJoinQr(
            code = "992144",
            sessionId = "sess-alpha-77"
        )
        val json = PacketSerializer.serialize(original)

        assertTrue(json.contains("\"type\":\"ROOM_JOIN_QR\""))
        assertTrue(json.contains("\"code\":\"992144\""))
        assertTrue(json.contains("\"session_id\":\"sess-alpha-77\""))

        val deserialized = PacketSerializer.deserialize(json)
        assertNotNull(deserialized)
        assertTrue(deserialized is RoomBeatPacket.RoomJoinQr)
        assertEquals(original, deserialized)
    }

    @Test
    fun testRoomJoinAckRoundTrip() {
        val original = RoomBeatPacket.RoomJoinAck(
            sessionId = "sess-node-01",
            hostTimeMs = 1726001234567L,
            clientId = "peer-dev-08",
            multicastAddr = "239.255.0.1",
            multicastPort = 9000
        )
        val json = PacketSerializer.serialize(original)

        assertTrue(json.contains("\"type\":\"ROOM_JOIN_ACK\""))
        assertTrue(json.contains("\"session_id\":\"sess-node-01\""))
        assertTrue(json.contains("\"host_time_ms\":1726001234567"))
        assertTrue(json.contains("\"client_id\":\"peer-dev-08\""))
        assertTrue(json.contains("\"multicast_addr\":\"239.255.0.1\""))
        assertTrue(json.contains("\"multicast_port\":9000"))

        val deserialized = PacketSerializer.deserialize(json)
        assertNotNull(deserialized)
        assertTrue(deserialized is RoomBeatPacket.RoomJoinAck)
        assertEquals(original, deserialized)
    }

    @Test
    fun testRoomJoinErrRoundTrip() {
        val originalWithMsg = RoomBeatPacket.RoomJoinErr(
            errorCode = "ERR_PIN_MISMATCH",
            message = "Supplied PIN is invalid or expired"
        )
        val json = PacketSerializer.serialize(originalWithMsg)

        assertTrue(json.contains("\"type\":\"ROOM_JOIN_ERR\""))
        assertTrue(json.contains("\"error_code\":\"ERR_PIN_MISMATCH\""))

        val deserialized = PacketSerializer.deserialize(json)
        assertNotNull(deserialized)
        assertEquals(originalWithMsg, deserialized)

        // Without optional message
        val originalWithoutMsg = RoomBeatPacket.RoomJoinErr(errorCode = "ERR_ROOM_FULL")
        val jsonNoMsg = PacketSerializer.serialize(originalWithoutMsg)
        val deserializedNoMsg = PacketSerializer.deserialize(jsonNoMsg)
        assertEquals(originalWithoutMsg, deserializedNoMsg)
    }

    @Test
    fun testSessionStartRoundTrip() {
        val original = RoomBeatPacket.SessionStart(
            mediaId = "audio_track_001.flac",
            targetPresentationTime = 1726005555000L,
            sourceType = "LOCAL_FILE"
        )
        val json = PacketSerializer.serialize(original)

        assertTrue(json.contains("\"type\":\"SESSION_START\""))
        assertTrue(json.contains("\"media_id\":\"audio_track_001.flac\""))
        assertTrue(json.contains("\"target_presentation_time\":1726005555000"))
        assertTrue(json.contains("\"source_type\":\"LOCAL_FILE\""))

        val deserialized = PacketSerializer.deserialize(json)
        assertNotNull(deserialized)
        assertTrue(deserialized is RoomBeatPacket.SessionStart)
        assertEquals(original, deserialized)
    }

    @Test
    fun testSessionPauseRoundTrip() {
        val original = RoomBeatPacket.SessionPause(atPresentationTime = 1726005560000L)
        val json = PacketSerializer.serialize(original)

        assertTrue(json.contains("\"type\":\"SESSION_PAUSE\""))
        assertTrue(json.contains("\"at_presentation_time\":1726005560000"))

        val deserialized = PacketSerializer.deserialize(json)
        assertNotNull(deserialized)
        assertTrue(deserialized is RoomBeatPacket.SessionPause)
        assertEquals(original, deserialized)
    }

    @Test
    fun testSessionSeekRoundTrip() {
        val original = RoomBeatPacket.SessionSeek(
            positionMs = 45000L,
            targetPresentationTime = 1726005570000L
        )
        val json = PacketSerializer.serialize(original)

        assertTrue(json.contains("\"type\":\"SESSION_SEEK\""))
        assertTrue(json.contains("\"position_ms\":45000"))
        assertTrue(json.contains("\"target_presentation_time\":1726005570000"))

        val deserialized = PacketSerializer.deserialize(json)
        assertNotNull(deserialized)
        assertTrue(deserialized is RoomBeatPacket.SessionSeek)
        assertEquals(original, deserialized)
    }

    @Test
    fun testSessionVolumeRoundTrip() {
        val original = RoomBeatPacket.SessionVolume(
            deviceId = "dev-pixel-9",
            volumeLevel = 0.85f
        )
        val json = PacketSerializer.serialize(original)

        assertTrue(json.contains("\"type\":\"SESSION_VOLUME\""))
        assertTrue(json.contains("\"device_id\":\"dev-pixel-9\""))
        assertTrue(json.contains("\"volume_level\":0.85"))

        val deserialized = PacketSerializer.deserialize(json)
        assertNotNull(deserialized)
        assertTrue(deserialized is RoomBeatPacket.SessionVolume)
        assertEquals(original, deserialized)
    }

    @Test
    fun testSessionMasterVolumeRoundTrip() {
        val original = RoomBeatPacket.SessionMasterVolume(masterVolume = 0.92f)
        val json = PacketSerializer.serialize(original)

        assertTrue(json.contains("\"type\":\"SESSION_MASTER_VOLUME\""))
        assertTrue(json.contains("\"master_volume\":0.92"))

        val deserialized = PacketSerializer.deserialize(json)
        assertNotNull(deserialized)
        assertTrue(deserialized is RoomBeatPacket.SessionMasterVolume)
        assertEquals(original, deserialized)
    }

    @Test
    fun testSessionDriftCorrectRoundTrip() {
        val original = RoomBeatPacket.SessionDriftCorrect(
            deviceId = "dev-s24-ultra",
            speedPpmAdjust = 500
        )
        val json = PacketSerializer.serialize(original)

        assertTrue(json.contains("\"type\":\"SESSION_DRIFT_CORRECT\""))
        assertTrue(json.contains("\"device_id\":\"dev-s24-ultra\""))
        assertTrue(json.contains("\"speed_ppm_adjust\":500"))

        val deserialized = PacketSerializer.deserialize(json)
        assertNotNull(deserialized)
        assertTrue(deserialized is RoomBeatPacket.SessionDriftCorrect)
        assertEquals(original, deserialized)
    }

    @Test
    fun testSessionStopRoundTrip() {
        val original = RoomBeatPacket.SessionStop(atPresentationTime = 1726005580000L)
        val json = PacketSerializer.serialize(original)

        assertTrue(json.contains("\"type\":\"SESSION_STOP\""))
        assertTrue(json.contains("\"at_presentation_time\":1726005580000"))

        val deserialized = PacketSerializer.deserialize(json)
        assertNotNull(deserialized)
        assertTrue(deserialized is RoomBeatPacket.SessionStop)
        assertEquals(original, deserialized)
    }

    @Test
    fun testSessionEndRoundTrip() {
        val original = RoomBeatPacket.SessionEnd(reason = "Host initiated teardown")
        val json = PacketSerializer.serialize(original)

        assertTrue(json.contains("\"type\":\"SESSION_END\""))

        val deserialized = PacketSerializer.deserialize(json)
        assertNotNull(deserialized)
        assertTrue(deserialized is RoomBeatPacket.SessionEnd)
        assertEquals(original, deserialized)

        // Empty session end
        val emptyEnd = RoomBeatPacket.SessionEnd()
        val emptyJson = PacketSerializer.serialize(emptyEnd)
        assertEquals(emptyEnd, PacketSerializer.deserialize(emptyJson))
    }

    @Test
    fun testRoomLeaveRoundTrip() {
        val original = RoomBeatPacket.RoomLeave(deviceId = "client-leaving-42")
        val json = PacketSerializer.serialize(original)

        assertTrue(json.contains("\"type\":\"ROOM_LEAVE\""))
        assertTrue(json.contains("\"device_id\":\"client-leaving-42\""))

        val deserialized = PacketSerializer.deserialize(json)
        assertNotNull(deserialized)
        assertTrue(deserialized is RoomBeatPacket.RoomLeave)
        assertEquals(original, deserialized)
    }

    @Test
    fun testPeerPingRoundTrip() {
        val original = RoomBeatPacket.PeerPing(timestampMs = 1726009999000L)
        val json = PacketSerializer.serialize(original)

        assertTrue(json.contains("\"type\":\"PEER_PING\""))
        assertTrue(json.contains("\"timestamp_ms\":1726009999000"))

        val deserialized = PacketSerializer.deserialize(json)
        assertNotNull(deserialized)
        assertTrue(deserialized is RoomBeatPacket.PeerPing)
        assertEquals(original, deserialized)
    }

    @Test
    fun testPeerPongRoundTrip() {
        val original = RoomBeatPacket.PeerPong(timestampMs = 1726009999020L)
        val json = PacketSerializer.serialize(original)

        assertTrue(json.contains("\"type\":\"PEER_PONG\""))
        assertTrue(json.contains("\"timestamp_ms\":1726009999020"))

        val deserialized = PacketSerializer.deserialize(json)
        assertNotNull(deserialized)
        assertTrue(deserialized is RoomBeatPacket.PeerPong)
        assertEquals(original, deserialized)
    }

    @Test
    fun testAudioChunkRoundTrip() {
        val original = RoomBeatPacket.AudioChunk(
            seq = 1042L,
            opusFrame = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhc=",
            targetPresentationTime = 1726001111222L
        )
        val json = PacketSerializer.serialize(original)

        assertTrue(json.contains("\"type\":\"AUDIO_CHUNK\""))
        assertTrue(json.contains("\"seq\":1042"))
        assertTrue(json.contains("\"opus_frame\":\"AQIDBAUGBwgJCgsMDQ4PEBESExQVFhc=\""))
        assertTrue(json.contains("\"target_presentation_time\":1726001111222"))

        val deserialized = PacketSerializer.deserialize(json)
        assertNotNull(deserialized)
        assertTrue(deserialized is RoomBeatPacket.AudioChunk)
        assertEquals(original, deserialized)
    }

    @Test
    fun testSpotifyWarmRoundTrip() {
        val original = RoomBeatPacket.SpotifyWarm(timestampMs = 1726001230000L)
        val json = PacketSerializer.serialize(original)

        assertTrue(json.contains("\"type\":\"SPOTIFY_WARM\""))

        val deserialized = PacketSerializer.deserialize(json)
        assertNotNull(deserialized)
        assertTrue(deserialized is RoomBeatPacket.SpotifyWarm)
        assertEquals(original, deserialized)
    }

    @Test
    fun testSpotifyCmdRoundTrip() {
        val original = RoomBeatPacket.SpotifyCmd(
            trackUri = "spotify:track:6rqhFgbbKwnb9MLmUQDhG6",
            targetPositionMs = 30000L,
            targetPresentationTime = 1726004444000L
        )
        val json = PacketSerializer.serialize(original)

        assertTrue(json.contains("\"type\":\"SPOTIFY_CMD\""))
        assertTrue(json.contains("\"track_uri\":\"spotify:track:6rqhFgbbKwnb9MLmUQDhG6\""))
        assertTrue(json.contains("\"target_position_ms\":30000"))
        assertTrue(json.contains("\"target_presentation_time\":1726004444000"))

        val deserialized = PacketSerializer.deserialize(json)
        assertNotNull(deserialized)
        assertTrue(deserialized is RoomBeatPacket.SpotifyCmd)
        assertEquals(original, deserialized)
    }

    @Test
    fun testSpotifyStateReportRoundTrip() {
        val original = RoomBeatPacket.SpotifyStateReport(
            deviceId = "spotify-dev-3",
            reportedPositionMs = 30120L,
            sampledAt = 1726004444120L
        )
        val json = PacketSerializer.serialize(original)

        assertTrue(json.contains("\"type\":\"SPOTIFY_STATE_REPORT\""))
        assertTrue(json.contains("\"device_id\":\"spotify-dev-3\""))
        assertTrue(json.contains("\"reported_position_ms\":30120"))
        assertTrue(json.contains("\"sampled_at\":1726004444120"))

        val deserialized = PacketSerializer.deserialize(json)
        assertNotNull(deserialized)
        assertTrue(deserialized is RoomBeatPacket.SpotifyStateReport)
        assertEquals(original, deserialized)
    }

    // =========================================================================
    // 2. Line-Delimited & Byte Serialization Tests
    // =========================================================================

    @Test
    fun testSerializeToLine() {
        val packet = RoomBeatPacket.RoomJoin(code = "123456")
        val line = PacketSerializer.serializeToLine(packet)

        assertTrue(line.endsWith("\n"))
        val decoded = PacketSerializer.deserializeLine(line)
        assertEquals(packet, decoded)
    }

    @Test
    fun testByteSerializationRoundTrip() {
        val packet = RoomBeatPacket.CalibProbe(t0 = 9999L)
        val bytes = PacketSerializer.serializeToBytes(packet)

        assertTrue(bytes.isNotEmpty())
        val decoded = PacketSerializer.deserialize(bytes)
        assertEquals(packet, decoded)

        val lineBytes = PacketSerializer.serializeToLineBytes(packet)
        assertTrue(lineBytes.isNotEmpty())
        assertEquals('\n'.code.toByte(), lineBytes.last())
        assertEquals(packet, PacketSerializer.deserialize(lineBytes))
    }

    // =========================================================================
    // 3. Error Handling & Malformed Payload Tests
    // =========================================================================

    @Test
    fun testEmptyAndBlankStringsFailGracefully() {
        assertNull(PacketSerializer.deserialize(""))
        assertNull(PacketSerializer.deserialize("   \n\t   "))
        assertNull(PacketSerializer.deserializeLine(""))
        assertNull(PacketSerializer.deserialize(ByteArray(0)))

        assertTrue(PacketSerializer.deserializeCatching("").isFailure)
        assertTrue(PacketSerializer.deserializeCatching(ByteArray(0)).isFailure)
    }

    @Test
    fun testMalformedJsonFailsGracefully() {
        val corruptedJson = "{ not: valid: json! }"
        assertNull(PacketSerializer.deserialize(corruptedJson))
        assertTrue(PacketSerializer.deserializeCatching(corruptedJson).isFailure)

        val unclosed = "{\"type\":\"ROOM_JOIN\",\"code\":\"123"
        assertNull(PacketSerializer.deserialize(unclosed))
        assertTrue(PacketSerializer.deserializeCatching(unclosed).isFailure)
    }

    @Test
    fun testUnknownPacketTypeFailsGracefully() {
        val unknown = "{\"type\":\"FUTURE_UNKNOWN_TYPE\",\"data\":\"something\"}"
        assertNull(PacketSerializer.deserialize(unknown))

        val result = PacketSerializer.deserializeCatching(unknown)
        assertTrue(result.isFailure)
    }

    @Test
    fun testMissingDiscriminatorFailsGracefully() {
        val noType = "{\"code\":\"123456\"}"
        assertNull(PacketSerializer.deserialize(noType))
        assertTrue(PacketSerializer.deserializeCatching(noType).isFailure)
    }

    @Test
    fun testMissingRequiredFieldFailsGracefully() {
        // ROOM_JOIN requires "code"
        val missingField = "{\"type\":\"ROOM_JOIN\"}"
        assertNull(PacketSerializer.deserialize(missingField))
        assertTrue(PacketSerializer.deserializeCatching(missingField).isFailure)
    }

    @Test
    fun testIgnoresUnknownKeysOnValidPacket() {
        // Forward compatibility: extra future fields are ignored
        val jsonWithExtra = "{\"type\":\"ROOM_JOIN\",\"code\":\"654321\",\"future_field\":\"ignored_val\"}"
        val parsed = PacketSerializer.deserialize(jsonWithExtra)
        assertNotNull(parsed)
        assertTrue(parsed is RoomBeatPacket.RoomJoin)
        assertEquals("654321", (parsed as RoomBeatPacket.RoomJoin).code)
    }

    // =========================================================================
    // 4. PacketDispatcher Routing, Coroutines & Exception Isolation Tests
    // =========================================================================

    @Test
    fun testDispatcherRoutesToSpecificTypeHandler() = testScope.runTest {
        val receivedJoin = AtomicBoolean(false)
        val receivedPing = AtomicBoolean(false)

        dispatcher.registerHandler<RoomBeatPacket.RoomJoin> { packet, context ->
            if (packet.code == "123456" && context.senderId == "client-1") {
                receivedJoin.set(true)
            }
        }

        dispatcher.registerHandler<RoomBeatPacket.PeerPing> { _, _ ->
            receivedPing.set(true)
        }

        val joinPacket = RoomBeatPacket.RoomJoin("123456")
        dispatcher.dispatch(joinPacket, PacketContext(senderId = "client-1"))
        advanceUntilIdle()

        assertTrue(receivedJoin.get())
        assertFalse(receivedPing.get())
    }

    @Test
    fun testMultipleHandlersForSameType() = testScope.runTest {
        val counter = AtomicInteger(0)

        dispatcher.registerHandler<RoomBeatPacket.CalibProbe> { _, _ ->
            counter.incrementAndGet()
        }
        dispatcher.registerHandler<RoomBeatPacket.CalibProbe> { _, _ ->
            counter.incrementAndGet()
        }

        dispatcher.dispatch(RoomBeatPacket.CalibProbe(t0 = 100L))
        advanceUntilIdle()

        assertEquals(2, counter.get())
    }

    @Test
    fun testGlobalHandlerReceivesAllPackets() = testScope.runTest {
        val receivedPackets = mutableListOf<String>()

        dispatcher.registerGlobalHandler { packet, _ ->
            receivedPackets.add(packet.packetType)
        }

        dispatcher.dispatch(RoomBeatPacket.CalibProbe(1L))
        dispatcher.dispatch(RoomBeatPacket.RoomJoin("111111"))
        dispatcher.dispatch(RoomBeatPacket.SessionStop(2L))
        advanceUntilIdle()

        assertEquals(3, receivedPackets.size)
        assertEquals(
            listOf(
                RoomBeatPacket.TYPE_CALIB_PROBE,
                RoomBeatPacket.TYPE_ROOM_JOIN,
                RoomBeatPacket.TYPE_SESSION_STOP
            ),
            receivedPackets
        )
    }

    @Test
    fun testHandlerUnregistration() = testScope.runTest {
        val count = AtomicInteger(0)
        val registration = dispatcher.registerHandler<RoomBeatPacket.PeerPing> { _, _ ->
            count.incrementAndGet()
        }

        dispatcher.dispatch(RoomBeatPacket.PeerPing(100L))
        advanceUntilIdle()
        assertEquals(1, count.get())

        registration.unregister()

        dispatcher.dispatch(RoomBeatPacket.PeerPing(200L))
        advanceUntilIdle()
        assertEquals(1, count.get())
    }

    @Test
    fun testUnregisterAllForClass() = testScope.runTest {
        val count = AtomicInteger(0)
        dispatcher.registerHandler<RoomBeatPacket.SessionPause> { _, _ -> count.incrementAndGet() }
        dispatcher.registerHandler<RoomBeatPacket.SessionPause> { _, _ -> count.incrementAndGet() }
        dispatcher.registerHandler<RoomBeatPacket.SessionStart> { _, _ -> count.incrementAndGet() }

        assertEquals(2, dispatcher.getHandlerCount(RoomBeatPacket.SessionPause::class))

        dispatcher.unregisterAll(RoomBeatPacket.SessionPause::class)
        assertEquals(0, dispatcher.getHandlerCount(RoomBeatPacket.SessionPause::class))
        assertEquals(1, dispatcher.getHandlerCount(RoomBeatPacket.SessionStart::class))

        dispatcher.dispatch(RoomBeatPacket.SessionPause(atPresentationTime = 100L))
        advanceUntilIdle()
        assertEquals(0, count.get())

        dispatcher.dispatch(RoomBeatPacket.SessionStart("m1", 200L, "LOCAL"))
        advanceUntilIdle()
        assertEquals(1, count.get())
    }

    @Test
    fun testUnregisterAllCompletely() = testScope.runTest {
        val count = AtomicInteger(0)
        dispatcher.registerHandler<RoomBeatPacket.RoomLeave> { _, _ -> count.incrementAndGet() }
        dispatcher.registerGlobalHandler { _, _ -> count.incrementAndGet() }

        dispatcher.unregisterAll()

        dispatcher.dispatch(RoomBeatPacket.RoomLeave("dev-1"))
        advanceUntilIdle()
        assertEquals(0, count.get())
    }

    @Test
    fun testExceptionIsolationDoesNotCrashDispatcherOrOtherHandlers() = testScope.runTest {
        val capturedError = AtomicBoolean(false)
        val secondHandlerExecuted = AtomicBoolean(false)

        dispatcher.errorHandler = { error, packet, context ->
            if (error.message == "Simulated handler exception" &&
                packet is RoomBeatPacket.RoomJoin &&
                context.senderId == "faulty-client"
            ) {
                capturedError.set(true)
            }
        }

        // Handler 1 throws
        dispatcher.registerHandler<RoomBeatPacket.RoomJoin> { _, _ ->
            throw RuntimeException("Simulated handler exception")
        }

        // Handler 2 should still run despite Handler 1 throwing
        dispatcher.registerHandler<RoomBeatPacket.RoomJoin> { _, _ ->
            secondHandlerExecuted.set(true)
        }

        // Must not throw exception
        dispatcher.dispatch(
            RoomBeatPacket.RoomJoin("123456"),
            PacketContext(senderId = "faulty-client")
        )
        advanceUntilIdle()

        assertTrue(capturedError.get())
        assertTrue(secondHandlerExecuted.get())
    }

    @Test
    fun testDispatchLineSuccessAndFailure() = testScope.runTest {
        val receivedCode = AtomicBoolean(false)
        dispatcher.registerHandler<RoomBeatPacket.RoomJoin> { packet, _ ->
            if (packet.code == "654321") receivedCode.set(true)
        }

        val validLine = "{\"type\":\"ROOM_JOIN\",\"code\":\"654321\"}\n"
        val success = dispatcher.dispatchLine(validLine)
        advanceUntilIdle()

        assertTrue(success)
        assertTrue(receivedCode.get())

        // Invalid line returns false without throwing
        val invalidSuccess = dispatcher.dispatchLine("{ not valid json }\n")
        assertFalse(invalidSuccess)
    }

    @Test
    fun testDispatchAsyncExecutesInBackground() = testScope.runTest {
        val executed = AtomicBoolean(false)
        dispatcher.registerHandler<RoomBeatPacket.SpotifyWarm> { _, _ ->
            executed.set(true)
        }

        val job = dispatcher.dispatchAsync(RoomBeatPacket.SpotifyWarm())
        assertFalse(executed.get()) // Not yet advanced

        advanceUntilIdle()
        assertTrue(executed.get())
        assertTrue(job.isCompleted)
    }

    @Test
    fun testDispatchLineAsync() = testScope.runTest {
        val executed = AtomicBoolean(false)
        dispatcher.registerHandler<RoomBeatPacket.PeerPing> { _, _ ->
            executed.set(true)
        }

        val success = dispatcher.dispatchLineAsync("{\"type\":\"PEER_PING\",\"timestamp_ms\":5555}\n")
        assertTrue(success)

        advanceUntilIdle()
        assertTrue(executed.get())

        val fail = dispatcher.dispatchLineAsync("garbage line")
        assertFalse(fail)
    }
}
