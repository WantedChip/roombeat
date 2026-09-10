package com.roombeat.app.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets

/**
 * High-performance JSON serializer and deserializer for RoomBeat packets.
 * Encodes and decodes line-delimited and binary UTF-8 messages with zero-crash error containment.
 */
object PacketSerializer {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    /**
     * Serializes a [RoomBeatPacket] into a JSON string.
     */
    fun serialize(packet: RoomBeatPacket): String {
        return json.encodeToString(packet)
    }

    /**
     * Serializes a [RoomBeatPacket] into a newline-terminated string (`\n`),
     * ready for immediate transmission over line-delimited TCP sockets.
     */
    fun serializeToLine(packet: RoomBeatPacket): String {
        return serialize(packet) + "\n"
    }

    /**
     * Serializes a [RoomBeatPacket] into UTF-8 encoded bytes.
     */
    fun serializeToBytes(packet: RoomBeatPacket): ByteArray {
        return serialize(packet).toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * Serializes a [RoomBeatPacket] into newline-terminated UTF-8 encoded bytes.
     */
    fun serializeToLineBytes(packet: RoomBeatPacket): ByteArray {
        return serializeToLine(packet).toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * Deserializes a JSON string into a [RoomBeatPacket], returning a [Result].
     * Returns [Result.failure] if the JSON is malformed, has unknown types, or fails validation.
     */
    fun deserializeCatching(raw: String): Result<RoomBeatPacket> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Empty packet payload"))
        }
        return runCatching {
            json.decodeFromString<RoomBeatPacket>(trimmed)
        }
    }

    /**
     * Deserializes a JSON string into a [RoomBeatPacket], returning `null` on any error.
     * Prevents crashing or propagating exceptions on socket reading threads.
     */
    fun deserialize(raw: String): RoomBeatPacket? {
        return deserializeCatching(raw).getOrNull()
    }

    /**
     * Deserializes a line-delimited message (stripping trailing whitespace/newlines).
     */
    fun deserializeLine(line: String): RoomBeatPacket? {
        return deserialize(line)
    }

    /**
     * Deserializes UTF-8 encoded bytes into a [RoomBeatPacket], returning `null` on error.
     */
    fun deserialize(bytes: ByteArray): RoomBeatPacket? {
        if (bytes.isEmpty()) return null
        return try {
            val text = String(bytes, StandardCharsets.UTF_8)
            deserialize(text)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Deserializes UTF-8 encoded bytes into a [RoomBeatPacket], returning [Result].
     */
    fun deserializeCatching(bytes: ByteArray): Result<RoomBeatPacket> {
        return try {
            val text = String(bytes, StandardCharsets.UTF_8)
            deserializeCatching(text)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
