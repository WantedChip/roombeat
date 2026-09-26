package com.roombeat.app.ui.components

import com.roombeat.app.ui.theme.SyncAmber
import com.roombeat.app.ui.theme.SyncGreen
import com.roombeat.app.ui.theme.SyncRed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryHeaderBadgeTest {

    @Test
    fun testDefaultStreamTelemetryValues() {
        val telemetry = StreamTelemetry()
        assertEquals("OPUS 128k", telemetry.codec)
        assertEquals(48_000, telemetry.sampleRateHz)
        assertEquals(20, telemetry.frameDurationMs)
        assertEquals(0.2, telemetry.driftMs, 0.001)
        assertEquals(0.0, telemetry.packetLossPercent, 0.001)
        assertEquals("ROOMBEAT RIG", telemetry.roomName)
        assertEquals(1, telemetry.peerCount)
        assertTrue(telemetry.isLocked)
        assertEquals("ACTIVE", telemetry.connectionStatus)
    }

    @Test
    fun testFormattedStreamTelemetryStandardString() {
        val telemetry = StreamTelemetry(
            sampleRateHz = 48_000,
            frameDurationMs = 20,
            driftMs = 0.2,
            packetLossPercent = 0.0
        )
        val formatted = telemetry.formattedStreamTelemetry
        assertEquals("LIVE OPUS STREAM · 48kHz / 20ms · ±0.2ms DRIFT · 0.0% LOSS", formatted)
    }

    @Test
    fun testFormattedStreamTelemetryWithCustomValues() {
        val telemetry = StreamTelemetry(
            sampleRateHz = 44_100,
            frameDurationMs = 10,
            driftMs = -0.5,
            packetLossPercent = 1.2
        )
        val formatted = telemetry.formattedStreamTelemetry
        assertEquals("LIVE OPUS STREAM · 44kHz / 10ms · ±0.5ms DRIFT · 1.2% LOSS", formatted)
    }

    @Test
    fun testFormattedDriftOutput() {
        val positive = StreamTelemetry(driftMs = 0.4)
        assertEquals("±0.4ms", positive.formattedDrift)

        val negative = StreamTelemetry(driftMs = -1.2)
        assertEquals("±1.2ms", negative.formattedDrift)
    }

    @Test
    fun testDriftStatusColorMapping() {
        // Locked / Normal drift (<= 1.5ms and loss <= 2%)
        val normal = StreamTelemetry(driftMs = 0.3, packetLossPercent = 0.0)
        assertEquals(SyncGreen, normal.driftStatusColor)

        // Warning drift (> 1.5ms)
        val warningDrift = StreamTelemetry(driftMs = 1.8, packetLossPercent = 0.0)
        assertEquals(SyncAmber, warningDrift.driftStatusColor)

        // Error packet loss (> 2.0%)
        val errorLoss = StreamTelemetry(driftMs = 0.3, packetLossPercent = 2.5)
        assertEquals(SyncRed, errorLoss.driftStatusColor)
    }

    @Test
    fun testTestTagsConstants() {
        assertEquals("telemetry_header_badge_container", TelemetryHeaderBadgeTags.CONTAINER)
        assertEquals("telemetry_room_name", TelemetryHeaderBadgeTags.ROOM_NAME)
        assertEquals("telemetry_peer_count", TelemetryHeaderBadgeTags.PEER_COUNT)
        assertEquals("telemetry_connection_status", TelemetryHeaderBadgeTags.CONNECTION_STATUS)
        assertEquals("telemetry_stream_telemetry", TelemetryHeaderBadgeTags.STREAM_TELEMETRY)
        assertEquals("telemetry_codec_badge", TelemetryHeaderBadgeTags.CODEC_BADGE)
        assertEquals("telemetry_sync_beacon", TelemetryHeaderBadgeTags.SYNC_BEACON)
        assertEquals("telemetry_drift_readout", TelemetryHeaderBadgeTags.DRIFT_READOUT)
    }
}
