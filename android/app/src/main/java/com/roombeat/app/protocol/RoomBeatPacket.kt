package com.roombeat.app.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Sealed class hierarchy modeling all RoomBeat protocol messages defined in Roadmap §11.
 * Supports polymorphic JSON serialization using the "type" discriminator matching Roadmap §11 names.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface RoomBeatPacket {

    /**
     * Uppercase type discriminator tag matching Roadmap §11 message name.
     */
    val packetType: String

    // ==========================================
    // Calibration & Clock Sync
    // ==========================================

    @Serializable
    @SerialName(TYPE_CALIB_PROBE)
    data class CalibProbe(
        @SerialName("t0") val t0: Long
    ) : RoomBeatPacket {
        override val packetType: String get() = TYPE_CALIB_PROBE
    }

    @Serializable
    @SerialName(TYPE_CALIB_ECHO)
    data class CalibEcho(
        @SerialName("t0") val t0: Long,
        @SerialName("t1") val t1: Long,
        @SerialName("t2") val t2: Long
    ) : RoomBeatPacket {
        override val packetType: String get() = TYPE_CALIB_ECHO
    }

    @Serializable
    @SerialName(TYPE_CALIB_RESULT)
    data class CalibResult(
        @SerialName("offset_ms") val offsetMs: Double,
        @SerialName("rtt_ms") val rttMs: Double,
        @SerialName("jitter_ms") val jitterMs: Double = 0.0
    ) : RoomBeatPacket {
        override val packetType: String get() = TYPE_CALIB_RESULT
    }

    // ==========================================
    // Room Join & Discovery
    // ==========================================

    @Serializable
    @SerialName(TYPE_ROOM_JOIN)
    data class RoomJoin(
        @SerialName("code") val code: String
    ) : RoomBeatPacket {
        override val packetType: String get() = TYPE_ROOM_JOIN
    }

    @Serializable
    @SerialName(TYPE_ROOM_JOIN_QR)
    data class RoomJoinQr(
        @SerialName("code") val code: String,
        @SerialName("session_id") val sessionId: String
    ) : RoomBeatPacket {
        override val packetType: String get() = TYPE_ROOM_JOIN_QR
    }

    @Serializable
    @SerialName(TYPE_ROOM_JOIN_ACK)
    data class RoomJoinAck(
        @SerialName("session_id") val sessionId: String,
        @SerialName("host_time_ms") val hostTimeMs: Long,
        @SerialName("client_id") val clientId: String,
        @SerialName("multicast_addr") val multicastAddr: String,
        @SerialName("multicast_port") val multicastPort: Int
    ) : RoomBeatPacket {
        override val packetType: String get() = TYPE_ROOM_JOIN_ACK
    }

    @Serializable
    @SerialName(TYPE_ROOM_JOIN_ERR)
    data class RoomJoinErr(
        @SerialName("error_code") val errorCode: String,
        @SerialName("message") val message: String? = null
    ) : RoomBeatPacket {
        override val packetType: String get() = TYPE_ROOM_JOIN_ERR
    }

    // ==========================================
    // Session Control & Volume
    // ==========================================

    @Serializable
    @SerialName(TYPE_SESSION_START)
    data class SessionStart(
        @SerialName("media_id") val mediaId: String,
        @SerialName("target_presentation_time") val targetPresentationTime: Long,
        @SerialName("source_type") val sourceType: String
    ) : RoomBeatPacket {
        override val packetType: String get() = TYPE_SESSION_START
    }

    @Serializable
    @SerialName(TYPE_SESSION_PAUSE)
    data class SessionPause(
        @SerialName("at_presentation_time") val atPresentationTime: Long
    ) : RoomBeatPacket {
        override val packetType: String get() = TYPE_SESSION_PAUSE
    }

    @Serializable
    @SerialName(TYPE_SESSION_SEEK)
    data class SessionSeek(
        @SerialName("position_ms") val positionMs: Long,
        @SerialName("target_presentation_time") val targetPresentationTime: Long
    ) : RoomBeatPacket {
        override val packetType: String get() = TYPE_SESSION_SEEK
    }

    @Serializable
    @SerialName(TYPE_SESSION_VOLUME)
    data class SessionVolume(
        @SerialName("device_id") val deviceId: String,
        @SerialName("volume_level") val volumeLevel: Float
    ) : RoomBeatPacket {
        override val packetType: String get() = TYPE_SESSION_VOLUME
    }

    @Serializable
    @SerialName(TYPE_SESSION_MASTER_VOLUME)
    data class SessionMasterVolume(
        @SerialName("master_volume") val masterVolume: Float
    ) : RoomBeatPacket {
        override val packetType: String get() = TYPE_SESSION_MASTER_VOLUME
    }

    @Serializable
    @SerialName(TYPE_SESSION_DRIFT_CORRECT)
    data class SessionDriftCorrect(
        @SerialName("device_id") val deviceId: String,
        @SerialName("speed_ppm_adjust") val speedPpmAdjust: Int
    ) : RoomBeatPacket {
        override val packetType: String get() = TYPE_SESSION_DRIFT_CORRECT
    }

    @Serializable
    @SerialName(TYPE_SESSION_STOP)
    data class SessionStop(
        @SerialName("at_presentation_time") val atPresentationTime: Long
    ) : RoomBeatPacket {
        override val packetType: String get() = TYPE_SESSION_STOP
    }

    @Serializable
    @SerialName(TYPE_SESSION_END)
    data class SessionEnd(
        @SerialName("reason") val reason: String? = null
    ) : RoomBeatPacket {
        override val packetType: String get() = TYPE_SESSION_END
    }

    @Serializable
    @SerialName(TYPE_ROOM_LEAVE)
    data class RoomLeave(
        @SerialName("device_id") val deviceId: String
    ) : RoomBeatPacket {
        override val packetType: String get() = TYPE_ROOM_LEAVE
    }

    // ==========================================
    // Heartbeat & Liveness
    // ==========================================

    @Serializable
    @SerialName(TYPE_PEER_PING)
    data class PeerPing(
        @SerialName("timestamp_ms") val timestampMs: Long
    ) : RoomBeatPacket {
        override val packetType: String get() = TYPE_PEER_PING
    }

    @Serializable
    @SerialName(TYPE_PEER_PONG)
    data class PeerPong(
        @SerialName("timestamp_ms") val timestampMs: Long
    ) : RoomBeatPacket {
        override val packetType: String get() = TYPE_PEER_PONG
    }

    // ==========================================
    // Audio Delivery (Multicast / Stream)
    // ==========================================

    @Serializable
    @SerialName(TYPE_AUDIO_CHUNK)
    data class AudioChunk(
        @SerialName("seq") val seq: Long,
        @SerialName("opus_frame") val opusFrame: String,
        @SerialName("target_presentation_time") val targetPresentationTime: Long
    ) : RoomBeatPacket {
        override val packetType: String get() = TYPE_AUDIO_CHUNK
    }

    // ==========================================
    // Spotify Path (App Remote SDK)
    // ==========================================

    @Serializable
    @SerialName(TYPE_SPOTIFY_WARM)
    data class SpotifyWarm(
        @SerialName("timestamp_ms") val timestampMs: Long = 0L
    ) : RoomBeatPacket {
        override val packetType: String get() = TYPE_SPOTIFY_WARM
    }

    @Serializable
    @SerialName(TYPE_SPOTIFY_CMD)
    data class SpotifyCmd(
        @SerialName("track_uri") val trackUri: String,
        @SerialName("target_position_ms") val targetPositionMs: Long,
        @SerialName("target_presentation_time") val targetPresentationTime: Long
    ) : RoomBeatPacket {
        override val packetType: String get() = TYPE_SPOTIFY_CMD
    }

    @Serializable
    @SerialName(TYPE_SPOTIFY_STATE_REPORT)
    data class SpotifyStateReport(
        @SerialName("device_id") val deviceId: String,
        @SerialName("reported_position_ms") val reportedPositionMs: Long,
        @SerialName("sampled_at") val sampledAt: Long
    ) : RoomBeatPacket {
        override val packetType: String get() = TYPE_SPOTIFY_STATE_REPORT
    }

    // ==========================================
    // Multicast Health & Diagnostics
    // ==========================================

    @Serializable
    @SerialName(TYPE_MULTICAST_TEST_BEACON)
    data class MulticastTestBeacon(
        @SerialName("seq") val seq: Int,
        @SerialName("session_id") val sessionId: String,
        @SerialName("timestamp_ms") val timestampMs: Long,
        @SerialName("total_burst") val totalBurst: Int = 10
    ) : RoomBeatPacket {
        override val packetType: String get() = TYPE_MULTICAST_TEST_BEACON
    }

    @Serializable
    @SerialName(TYPE_MULTICAST_PROBE_REPORT)
    data class MulticastProbeReport(
        @SerialName("device_id") val deviceId: String,
        @SerialName("session_id") val sessionId: String,
        @SerialName("received_count") val receivedCount: Int,
        @SerialName("total_sent") val totalSent: Int = 10,
        @SerialName("reception_rate") val receptionRate: Double = 0.0,
        @SerialName("is_blocked") val isBlocked: Boolean = false
    ) : RoomBeatPacket {
        override val packetType: String get() = TYPE_MULTICAST_PROBE_REPORT
    }

    companion object {
        const val TYPE_CALIB_PROBE = "CALIB_PROBE"
        const val TYPE_CALIB_ECHO = "CALIB_ECHO"
        const val TYPE_CALIB_RESULT = "CALIB_RESULT"

        const val TYPE_ROOM_JOIN = "ROOM_JOIN"
        const val TYPE_ROOM_JOIN_QR = "ROOM_JOIN_QR"
        const val TYPE_ROOM_JOIN_ACK = "ROOM_JOIN_ACK"
        const val TYPE_ROOM_JOIN_ERR = "ROOM_JOIN_ERR"

        const val TYPE_SESSION_START = "SESSION_START"
        const val TYPE_SESSION_PAUSE = "SESSION_PAUSE"
        const val TYPE_SESSION_SEEK = "SESSION_SEEK"
        const val TYPE_SESSION_VOLUME = "SESSION_VOLUME"
        const val TYPE_SESSION_MASTER_VOLUME = "SESSION_MASTER_VOLUME"
        const val TYPE_SESSION_DRIFT_CORRECT = "SESSION_DRIFT_CORRECT"
        const val TYPE_SESSION_STOP = "SESSION_STOP"
        const val TYPE_SESSION_END = "SESSION_END"
        const val TYPE_ROOM_LEAVE = "ROOM_LEAVE"

        const val TYPE_PEER_PING = "PEER_PING"
        const val TYPE_PEER_PONG = "PEER_PONG"

        const val TYPE_AUDIO_CHUNK = "AUDIO_CHUNK"

        const val TYPE_SPOTIFY_WARM = "SPOTIFY_WARM"
        const val TYPE_SPOTIFY_CMD = "SPOTIFY_CMD"
        const val TYPE_SPOTIFY_STATE_REPORT = "SPOTIFY_STATE_REPORT"

        const val TYPE_MULTICAST_TEST_BEACON = "MULTICAST_TEST_BEACON"
        const val TYPE_MULTICAST_PROBE_REPORT = "MULTICAST_PROBE_REPORT"
    }
}
