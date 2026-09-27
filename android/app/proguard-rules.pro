# RoomBeat ProGuard & R8 Shrinking, Optimization, and Obfuscation Rules
# Sub-phase v1.0.2: ProGuard/R8 Rules, Native Library Strip & Release Build Setup

# ---------------------------------------------------------------------------
# General Optimization & Debugging Attributes
# ---------------------------------------------------------------------------
# Preserve line numbers and source files for release crash reporting
-keepattributes SourceFile, LineNumberTable
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# ---------------------------------------------------------------------------
# JNI Entry Points & Native Audio Engine Keep Rules
# ---------------------------------------------------------------------------
# Preserve all native methods across the entire application
-keepclasseswithmembernames class * {
    native <methods>;
}

# Explicitly keep RoomBeat audio engine classes and JNI bridges called from or bound to C++
-keep class com.roombeat.app.audio.** {
    native <methods>;
    <fields>;
    <methods>;
}
-keep class com.roombeat.app.audio.NativeAudioEngine { *; }
-keep class com.roombeat.app.audio.NativeAudioEngine$* { *; }
-keep class com.roombeat.app.audio.NativeAudioEngine$DefaultJniBridge { *; }
-keep class com.roombeat.app.audio.NativeAudioEngine$AudioEngineBridge { *; }
-keep class com.roombeat.app.audio.AudioEngineState { *; }
-keep class com.roombeat.app.audio.AudioEngineResult { *; }

# Keep AudioJitterBuffer and OpusCodec classes and JNI entry points
-keep class com.roombeat.app.audio.buffer.AudioJitterBuffer { *; }
-keep class com.roombeat.app.audio.buffer.AudioJitterBuffer$* { *; }
-keep class com.roombeat.app.audio.buffer.AudioJitterBuffer$JitterBufferStats { *; }
-keep class com.roombeat.app.audio.buffer.AudioJitterBufferBridge { *; }
-keep class com.roombeat.app.audio.buffer.DefaultAudioJitterBufferBridge { *; }
-keep class com.roombeat.app.audio.codec.OpusCodec { *; }
-keep class com.roombeat.app.audio.codec.OpusCodec$* { *; }
-keep class com.roombeat.app.audio.codec.OpusEncoder { *; }
-keep class com.roombeat.app.audio.codec.OpusDecoder { *; }
-keep class com.roombeat.app.audio.codec.OpusConstants { *; }

# ---------------------------------------------------------------------------
# Google Oboe Audio Engine (Prefab & C++ Bindings)
# ---------------------------------------------------------------------------
-keep class com.google.oboe.** { *; }
-dontwarn com.google.oboe.**

# ---------------------------------------------------------------------------
# Spotify App Remote SDK & Protocol Keep Rules
# ---------------------------------------------------------------------------
-keep class com.spotify.android.appremote.** { *; }
-keep interface com.spotify.android.appremote.** { *; }
-keep class com.spotify.protocol.** { *; }
-keep interface com.spotify.protocol.** { *; }
-keepclassmembers class com.spotify.protocol.** {
    <fields>;
    <methods>;
}
-dontwarn com.spotify.android.appremote.**
-dontwarn com.spotify.protocol.**

# Gson serialization rules for Spotify IPC communication
-keepclassmembers class * {
    @com.google.code.gson.annotations.SerializedName <fields>;
    @com.google.code.gson.annotations.Expose <fields>;
}
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# ---------------------------------------------------------------------------
# Kotlinx Serialization Rules
# ---------------------------------------------------------------------------
# Keep all @Serializable classes, companion serializers, and descriptors
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class **$serializer {
    *** INSTANCE;
}

# Preserve RoomBeat protocol packet models and payloads
-keep class com.roombeat.app.protocol.** { *; }
-keep class com.roombeat.app.network.discovery.DiscoveredRoom { *; }
-keep class com.roombeat.app.session.QrRoomPayload { *; }
-dontwarn kotlinx.serialization.**

# ---------------------------------------------------------------------------
# CameraX, MLKit Barcode Scanning & ZXing
# ---------------------------------------------------------------------------
-keep class androidx.camera.core.** { *; }
-keep class androidx.camera.camera2.** { *; }
-keep class androidx.camera.lifecycle.** { *; }
-keep class androidx.camera.view.** { *; }
-dontwarn androidx.camera.**
-keep class com.google.mlkit.vision.barcode.** { *; }
-keep class com.google.mlkit.vision.common.** { *; }
-dontwarn com.google.mlkit.**
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ---------------------------------------------------------------------------
# Android Architecture & System Components
# ---------------------------------------------------------------------------
-keep class com.roombeat.app.MainActivity { *; }
-keep class com.roombeat.app.service.RoomBeatCaptureService { *; }
-keep class com.roombeat.app.source.spotify.SpotifyAuthCallbackActivity { *; }
-keep class com.roombeat.app.system.** { *; }

# ---------------------------------------------------------------------------
# Kotlin Coroutines
# ---------------------------------------------------------------------------
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**
