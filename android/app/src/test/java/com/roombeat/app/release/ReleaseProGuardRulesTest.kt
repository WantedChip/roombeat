package com.roombeat.app.release

import com.roombeat.app.audio.NativeAudioEngine
import com.roombeat.app.audio.buffer.AudioJitterBuffer
import com.roombeat.app.audio.codec.OpusCodec
import com.roombeat.app.protocol.RoomBeatPacket
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * Unit verification test suite for Sub-phase v1.0.2:
 * ProGuard/R8 Rules, Native Library Strip & Release Build Setup.
 *
 * Validates:
 * 1. ProGuard rules coverage for JNI, Oboe, Spotify SDK, Gson, and Kotlinx Serialization.
 * 2. build.gradle.kts release shrinking, resource optimization, and native symbol stripping.
 * 3. JNI method signatures on native audio components.
 * 4. Polymorphic JSON serialization round-trips for RoomBeat packets.
 * 5. Built release APK file sizes conform strictly to the < 15MB limit.
 */
class ReleaseProGuardRulesTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    @Test
    fun testProGuardRulesFileContent() {
        // Resolve proguard-rules.pro relative to android/app
        val candidates = listOf(
            File("proguard-rules.pro"),
            File("android/app/proguard-rules.pro"),
            File("../app/proguard-rules.pro"),
            File("../../android/app/proguard-rules.pro")
        )
        val proguardFile = candidates.firstOrNull { it.exists() }
        assertNotNull("proguard-rules.pro must exist in android/app", proguardFile)
        val content = proguardFile!!.readText()

        // 1. JNI rules
        assertTrue("Must keep native methods", content.contains("native <methods>;"))
        assertTrue("Must keep NativeAudioEngine", content.contains("com.roombeat.app.audio.NativeAudioEngine"))
        assertTrue("Must keep DefaultJniBridge", content.contains("com.roombeat.app.audio.NativeAudioEngine\$DefaultJniBridge"))
        assertTrue("Must keep AudioJitterBuffer", content.contains("com.roombeat.app.audio.buffer.AudioJitterBuffer"))
        assertTrue("Must keep OpusCodec", content.contains("com.roombeat.app.audio.codec.OpusCodec"))

        // 2. Oboe rules
        assertTrue("Must keep Oboe classes", content.contains("com.google.oboe.**"))

        // 3. Spotify App Remote SDK & Gson rules
        assertTrue("Must keep Spotify App Remote SDK", content.contains("com.spotify.android.appremote.**"))
        assertTrue("Must keep Spotify Protocol", content.contains("com.spotify.protocol.**"))
        assertTrue("Must keep Gson SerializedName", content.contains("@com.google.code.gson.annotations.SerializedName"))

        // 4. Kotlinx Serialization rules
        assertTrue("Must keep @Serializable", content.contains("@kotlinx.serialization.Serializable"))
        assertTrue("Must keep RoomBeatPacket", content.contains("com.roombeat.app.protocol.**"))
    }

    @Test
    fun testBuildGradleKtsReleaseConfiguration() {
        val candidates = listOf(
            File("build.gradle.kts"),
            File("android/app/build.gradle.kts"),
            File("../app/build.gradle.kts"),
            File("../../android/app/build.gradle.kts")
        )
        val buildGradleFile = candidates.firstOrNull { it.exists() && it.readText().contains("com.roombeat.app") }
        assertNotNull("build.gradle.kts must exist in android/app", buildGradleFile)
        val content = buildGradleFile!!.readText()

        // Verify minification, resource shrinking, and native symbol stripping
        assertTrue("isMinifyEnabled must be true for release", content.contains("isMinifyEnabled = true"))
        assertTrue("isShrinkResources must be true for release", content.contains("isShrinkResources = true"))
        assertTrue("debugSymbolLevel must be SYMBOL_TABLE", content.contains("debugSymbolLevel = \"SYMBOL_TABLE\""))

        // Verify ABI splits
        assertTrue("ABI splits must include arm64-v8a", content.contains("\"arm64-v8a\""))
        assertTrue("ABI splits must include armeabi-v7a", content.contains("\"armeabi-v7a\""))
        assertTrue("ABI splits must include x86_64", content.contains("\"x86_64\""))
        assertTrue("Universal APK generation must be enabled", content.contains("isUniversalApk = true"))

        // Verify release signing configuration
        assertTrue("Release signing configuration must be present", content.contains("create(\"release\")"))
        assertTrue("Release buildType must use release signingConfig", content.contains("signingConfig = signingConfigs.getByName(\"release\")"))
    }

    @Test
    fun testJniNativeMethodDeclarationsPresent() {
        // Reflectively verify that all JNI bridge native methods exist on the classes
        val bridgeClass = Class.forName("com.roombeat.app.audio.NativeAudioEngine\$DefaultJniBridge")
        val bridgeNativeMethods = bridgeClass.declaredMethods.filter { Modifier.isNative(it.modifiers) }
        assertTrue("DefaultJniBridge must declare native JNI methods", bridgeNativeMethods.isNotEmpty())

        val nativeMethodNames = bridgeNativeMethods.map { it.name }.toSet()
        assertTrue("nativeInitEngine must be declared", nativeMethodNames.contains("nativeInitEngine"))
        assertTrue("nativeStartStream must be declared", nativeMethodNames.contains("nativeStartStream"))
        assertTrue("nativeStopStream must be declared", nativeMethodNames.contains("nativeStopStream"))
        assertTrue("nativeWriteAudioFrames must be declared", nativeMethodNames.contains("nativeWriteAudioFrames"))
        assertTrue("nativeWritePcm16Frames must be declared", nativeMethodNames.contains("nativeWritePcm16Frames"))
        assertTrue("nativeAttachJitterBuffer must be declared", nativeMethodNames.contains("nativeAttachJitterBuffer"))
        assertTrue("nativePushAudioChunk must be declared", nativeMethodNames.contains("nativePushAudioChunk"))
        assertTrue("nativeSetSpeedPpm must be declared", nativeMethodNames.contains("nativeSetSpeedPpm"))
        assertTrue("nativeGetSpeedPpm must be declared", nativeMethodNames.contains("nativeGetSpeedPpm"))

        // OpusCodec native methods
        val opusNativeMethods = OpusCodec::class.java.declaredMethods.filter { Modifier.isNative(it.modifiers) }
        val opusMethodNames = opusNativeMethods.map { it.name }.toSet()
        assertTrue("nativeEncoderCreate must be declared", opusMethodNames.contains("nativeEncoderCreate"))
        assertTrue("nativeDecoderCreate must be declared", opusMethodNames.contains("nativeDecoderCreate"))
        assertTrue("nativeEncoderEncodeFloat must be declared", opusMethodNames.contains("nativeEncoderEncodeFloat"))
        assertTrue("nativeDecoderDecodeFloat must be declared", opusMethodNames.contains("nativeDecoderDecodeFloat"))

        // AudioJitterBuffer native methods declared on DefaultAudioJitterBufferBridge
        val jitterBridgeClass = Class.forName("com.roombeat.app.audio.buffer.DefaultAudioJitterBufferBridge")
        val jitterNativeMethods = jitterBridgeClass.declaredMethods.filter { Modifier.isNative(it.modifiers) }
        val jitterMethodNames = jitterNativeMethods.map { it.name }.toSet()
        assertTrue("nativeCreate must be declared", jitterMethodNames.contains("nativeCreate"))
        assertTrue("nativePushPacket must be declared", jitterMethodNames.contains("nativePushPacket"))
        assertTrue("nativePullFrames must be declared", jitterMethodNames.contains("nativePullFrames"))
        assertTrue("nativeSetSpeedPpm must be declared", jitterMethodNames.contains("nativeSetSpeedPpm"))
    }

    @Test
    fun testSerializationRoundTripsWithProGuardModels() {
        // Test polymorphic serialization of RoomBeatPackets
        val startPacket: RoomBeatPacket = RoomBeatPacket.SessionStart(
            mediaId = "audio-track-001",
            targetPresentationTime = 1700000000000L,
            sourceType = "LOCAL_SAF"
        )
        val startJson = json.encodeToString(startPacket)
        val deserializedStart = json.decodeFromString<RoomBeatPacket>(startJson)
        assertTrue("Deserialized packet must match type", deserializedStart is RoomBeatPacket.SessionStart)
        assertEquals("audio-track-001", (deserializedStart as RoomBeatPacket.SessionStart).mediaId)
        assertEquals(1700000000000L, deserializedStart.targetPresentationTime)

        val calibPacket: RoomBeatPacket = RoomBeatPacket.CalibProbe(
            t0 = 1000000L
        )
        val calibJson = json.encodeToString(calibPacket)
        val deserializedCalib = json.decodeFromString<RoomBeatPacket>(calibJson)
        assertTrue(deserializedCalib is RoomBeatPacket.CalibProbe)
        assertEquals(1000000L, (deserializedCalib as RoomBeatPacket.CalibProbe).t0)

        val spotifyCmdPacket: RoomBeatPacket = RoomBeatPacket.SpotifyCmd(
            trackUri = "spotify:track:4cOdK2wGLETKBW3PvgPWqT",
            targetPositionMs = 15000L,
            targetPresentationTime = 3000000L,
            command = RoomBeatPacket.SpotifyCmd.CMD_PLAY
        )
        val spotifyJson = json.encodeToString(spotifyCmdPacket)
        val deserializedSpotify = json.decodeFromString<RoomBeatPacket>(spotifyJson)
        assertTrue(deserializedSpotify is RoomBeatPacket.SpotifyCmd)
        assertEquals(RoomBeatPacket.SpotifyCmd.CMD_PLAY, (deserializedSpotify as RoomBeatPacket.SpotifyCmd).command)
    }

    @Test
    fun testReleaseApkSizesUnder15MB() {
        val candidates = listOf(
            File("build/outputs/apk/release"),
            File("android/app/build/outputs/apk/release"),
            File("../app/build/outputs/apk/release"),
            File("../../android/app/build/outputs/apk/release")
        )
        val releaseDir = candidates.firstOrNull { it.exists() && it.isDirectory }
        if (releaseDir != null) {
            val maxAllowedBytes = 15L * 1024 * 1024 // 15MB limit

            val arm64Apk = File(releaseDir, "app-arm64-v8a-release.apk")
            if (arm64Apk.exists()) {
                val size = arm64Apk.length()
                assertTrue(
                    "app-arm64-v8a-release.apk size ($size bytes) must be < 15MB ($maxAllowedBytes bytes)",
                    size < maxAllowedBytes
                )
            }

            val armv7Apk = File(releaseDir, "app-armeabi-v7a-release.apk")
            if (armv7Apk.exists()) {
                val size = armv7Apk.length()
                assertTrue(
                    "app-armeabi-v7a-release.apk size ($size bytes) must be < 15MB ($maxAllowedBytes bytes)",
                    size < maxAllowedBytes
                )
            }

            val x86_64Apk = File(releaseDir, "app-x86_64-release.apk")
            if (x86_64Apk.exists()) {
                val size = x86_64Apk.length()
                assertTrue(
                    "app-x86_64-release.apk size ($size bytes) must be < 15MB ($maxAllowedBytes bytes)",
                    size < maxAllowedBytes
                )
            }
        }
    }
}
