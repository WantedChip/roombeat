package com.roombeat.app.capture

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.roombeat.app.source.capture.InstalledMediaApp
import com.roombeat.app.ui.components.BeaconStatus
import com.roombeat.app.ui.components.RecessedPanel
import com.roombeat.app.ui.components.StatusBeacon
import com.roombeat.app.ui.components.TactileButtonVariant
import com.roombeat.app.ui.components.TactileKeycapButton
import com.roombeat.app.ui.theme.BorderMilled
import com.roombeat.app.ui.theme.SurfacePanel
import com.roombeat.app.ui.theme.SurfaceRecessed
import com.roombeat.app.ui.theme.SyncAmber
import com.roombeat.app.ui.theme.SyncGreen
import com.roombeat.app.ui.theme.SyncRed
import com.roombeat.app.ui.theme.TextBone
import com.roombeat.app.ui.theme.TextDim
import com.roombeat.app.ui.theme.TextMuted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Operating states of [CaptureFallbackHandler].
 */
sealed interface FallbackState {
    /** Normal audio capture is running or awaiting app launch. */
    object Idle : FallbackState

    /**
     * A media player app was launched; monitoring captured PCM frames for incoming audio.
     *
     * @param launchedApp The third-party media app that was launched.
     * @param elapsedSilenceMs Milliseconds of continuous silence observed since launch.
     * @param timeoutMs Configured threshold timeout before triggering opt-out warning.
     */
    data class Monitoring(
        val launchedApp: InstalledMediaApp,
        val elapsedSilenceMs: Long,
        val timeoutMs: Long
    ) : FallbackState

    /**
     * Non-zero audio energy detected; audio capture is actively streaming healthy content.
     *
     * @param launchedApp The currently playing media app, if known.
     * @param rmsDbfs Root-mean-square audio energy in dBFS.
     */
    data class ActiveAudio(
        val launchedApp: InstalledMediaApp?,
        val rmsDbfs: Double
    ) : FallbackState

    /**
     * Prolonged silence exceeded the timeout threshold after launching a media app.
     * Indicates the application is likely setting `AudioAttributes.FLAG_NO_SYSTEM_CAPTURE` (opt-out).
     *
     * @param launchedApp The third-party media app that appears to block system capture.
     * @param durationSilentMs Milliseconds of total silence recorded.
     * @param reason Technical explanation of the failure mode.
     */
    data class OptOutDetected(
        val launchedApp: InstalledMediaApp,
        val durationSilentMs: Long,
        val reason: String = "App set AudioAttributes.FLAG_NO_SYSTEM_CAPTURE or digital silence"
    ) : FallbackState
}

/**
 * Discrete events emitted by [CaptureFallbackHandler].
 */
sealed interface FallbackEvent {
    /**
     * Fired when prolonged silence exceeds the timeout, indicating the app opted out of capture.
     */
    data class SilenceWarningTriggered(
        val app: InstalledMediaApp,
        val durationSilentMs: Long,
        val guidanceMessage: String
    ) : FallbackEvent

    /**
     * Fired when non-silent audio frames arrive, recovering the capture pipeline.
     */
    data class AudioStreamRecovered(
        val app: InstalledMediaApp?,
        val rmsDbfs: Double
    ) : FallbackEvent

    /**
     * Fired when monitoring starts after launching an app.
     */
    data class MonitoringStarted(
        val app: InstalledMediaApp,
        val timeoutMs: Long
    ) : FallbackEvent

    /**
     * Fired when monitoring is dismissed or cancelled.
     */
    object MonitoringCancelled : FallbackEvent
}

/**
 * Alternative fallback sources recommended when an app blocks system capture.
 */
enum class FallbackDestination {
    /** Switch to local device storage audio playback (Module 01 - SAF Lossless). */
    LOCAL_STORAGE,

    /** Switch to Spotify App Remote SDK playback (Module 03 - SDK Sync). */
    SPOTIFY_REMOTE
}

/**
 * Capture opt-out and zero-audio detection logic for RoomBeat's system audio pipeline.
 *
 * Background:
 * Android's `AudioPlaybackCaptureConfiguration` only captures audio from third-party apps that allow it.
 * Applications can explicitly opt out of capture by setting `AudioAttributes.FLAG_NO_SYSTEM_CAPTURE`,
 * or by using DRM protected content, causing `AudioRecord` to output prolonged zero-audio / silence frames.
 *
 * Key Capabilities:
 * 1. Monitors audio activity and silence frames from [SystemAudioCaptureEngine] and [AudioSilenceDetector].
 * 2. When a media app is launched via [onAppLaunched], begins monitoring timer.
 * 3. If prolonged zero audio persists beyond [silenceTimeoutMs] (default 6,000ms / 6s), triggers [FallbackState.OptOutDetected].
 * 4. Self-healing: as soon as real audio frames arrive, automatically recovers to [FallbackState.ActiveAudio].
 * 5. Provides guidance for switching to uncompressed Local Storage (Module 01) or Spotify Remote (Module 03).
 */
class CaptureFallbackHandler(
    val silenceTimeoutMs: Long = DEFAULT_SILENCE_TIMEOUT_MS,
    val silenceThresholdDbfs: Double = AudioSilenceDetector.DEFAULT_THRESHOLD_DBFS,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    var onFallbackTriggered: ((FallbackEvent.SilenceWarningTriggered) -> Unit)? = null,
    var onStreamRecovered: ((FallbackEvent.AudioStreamRecovered) -> Unit)? = null
) {
    companion object {
        /** Default silence duration before declaring opt-out (6.0 seconds, within 5-8s window). */
        const val DEFAULT_SILENCE_TIMEOUT_MS = 6000L

        /** Minimum silence duration required to trigger warning. */
        const val MIN_SILENCE_TIMEOUT_MS = 2000L
    }

    private val _state = MutableStateFlow<FallbackState>(FallbackState.Idle)
    val state: StateFlow<FallbackState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<FallbackEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<FallbackEvent> = _events.asSharedFlow()

    private var activeApp: InstalledMediaApp? = null
    private var monitoringStartTimeMs: Long = 0L
    private var silenceStartTimeMs: Long = 0L

    /**
     * Currently active launched media application, if any.
     */
    val launchedApp: InstalledMediaApp?
        get() = activeApp

    /**
     * Invoked when a user selects and launches an external media player.
     * Transitions state to [FallbackState.Monitoring] and initiates silence timer.
     */
    @Synchronized
    fun onAppLaunched(app: InstalledMediaApp) {
        val now = timeProvider()
        activeApp = app
        monitoringStartTimeMs = now
        silenceStartTimeMs = now

        _state.value = FallbackState.Monitoring(
            launchedApp = app,
            elapsedSilenceMs = 0L,
            timeoutMs = silenceTimeoutMs
        )
        _events.tryEmit(FallbackEvent.MonitoringStarted(app, silenceTimeoutMs))
    }

    /**
     * Evaluates a captured PCM audio frame from [SystemAudioCaptureEngine].
     */
    fun onAudioFrame(frame: CapturedAudioFrame) {
        onFrameProcessed(
            isSilent = frame.isSilent,
            rmsDbfs = frame.rmsDbfs,
            timestampMs = timeProvider()
        )
    }

    /**
     * Processes silence classification metrics for a single audio frame slice.
     *
     * @param isSilent True if the frame was classified as silence by [AudioSilenceDetector].
     * @param rmsDbfs Root-mean-square audio energy in dBFS.
     * @param timestampMs Monotonic or wall-clock epoch timestamp in milliseconds.
     */
    @Synchronized
    fun onFrameProcessed(
        isSilent: Boolean,
        rmsDbfs: Double = AudioSilenceDetector.SILENCE_DBFS_FLOOR,
        timestampMs: Long = timeProvider()
    ) {
        val currentState = _state.value

        if (!isSilent && rmsDbfs >= silenceThresholdDbfs) {
            // Audio energy detected! Active sound is flowing.
            val wasInProblemState = (currentState is FallbackState.OptOutDetected || currentState is FallbackState.Monitoring)

            _state.value = FallbackState.ActiveAudio(
                launchedApp = activeApp,
                rmsDbfs = rmsDbfs
            )

            if (wasInProblemState) {
                val recoveryEvent = FallbackEvent.AudioStreamRecovered(activeApp, rmsDbfs)
                _events.tryEmit(recoveryEvent)
                onStreamRecovered?.invoke(recoveryEvent)
            }
            return
        }

        // Frame is silent
        when (currentState) {
            is FallbackState.Monitoring -> {
                val elapsed = timestampMs - monitoringStartTimeMs
                if (elapsed >= silenceTimeoutMs) {
                    // Timeout exceeded! Prolonged zero audio after app launch.
                    val app = currentState.launchedApp
                    val guidance = "Application '${app.appName}' appears to opt out of system audio capture " +
                            "(AudioAttributes.FLAG_NO_SYSTEM_CAPTURE). Switch to Local Storage or Spotify Remote."

                    _state.value = FallbackState.OptOutDetected(
                        launchedApp = app,
                        durationSilentMs = elapsed,
                        reason = guidance
                    )

                    val warningEvent = FallbackEvent.SilenceWarningTriggered(app, elapsed, guidance)
                    _events.tryEmit(warningEvent)
                    onFallbackTriggered?.invoke(warningEvent)
                } else {
                    _state.value = FallbackState.Monitoring(
                        launchedApp = currentState.launchedApp,
                        elapsedSilenceMs = elapsed,
                        timeoutMs = silenceTimeoutMs
                    )
                }
            }

            is FallbackState.OptOutDetected -> {
                // Prolonged silence continues; update elapsed duration
                val elapsed = timestampMs - monitoringStartTimeMs
                _state.value = currentState.copy(durationSilentMs = elapsed)
            }

            is FallbackState.ActiveAudio -> {
                // Normal lull / pause during active streaming; remain active or monitor
            }

            is FallbackState.Idle -> {
                // Idle, do nothing
            }
        }
    }

    /**
     * Dismisses the active opt-out warning banner and returns to [FallbackState.Idle].
     */
    @Synchronized
    fun dismissWarning() {
        _state.value = FallbackState.Idle
        _events.tryEmit(FallbackEvent.MonitoringCancelled)
    }

    /**
     * Resets the handler state, clearing active app references and timers.
     */
    @Synchronized
    fun reset() {
        activeApp = null
        monitoringStartTimeMs = 0L
        silenceStartTimeMs = 0L
        _state.value = FallbackState.Idle
    }

    /**
     * Attaches this handler to a [SystemAudioCaptureEngine] instance, observing frames on [scope].
     */
    fun attachCaptureEngine(engine: SystemAudioCaptureEngine, scope: CoroutineScope): Job {
        return scope.launch {
            engine.audioFrames.collect { frame ->
                onAudioFrame(frame)
            }
        }
    }

    /**
     * Attaches this handler to a [CaptureStreamingBridge] instance, observing frames on [scope].
     */
    fun attachStreamingBridge(bridge: CaptureStreamingBridge, scope: CoroutineScope): Job {
        return scope.launch {
            // Bridge tracks silence metrics internally; observe telemetry or direct frames
            bridge.statsFlow.collect { stats ->
                if (stats.packetsSent > 0 && stats.activeFramesSent > 0) {
                    onFrameProcessed(isSilent = false, rmsDbfs = -20.0)
                }
            }
        }
    }
}

/**
 * Tactile Acoustic Industrial warning banner advising user when a third-party app
 * actively opts out of Android system audio capture.
 */
@Composable
fun CaptureFallbackBanner(
    state: FallbackState,
    onSelectDestination: (FallbackDestination) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val optOutState = state as? FallbackState.OptOutDetected ?: return

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("capture_fallback_banner"),
        shape = RoundedCornerShape(8.dp),
        color = SurfacePanel,
        border = BorderStroke(1.dp, SyncAmber)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header: Warning Beacon + Title
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusBeacon(status = BeaconStatus.WARNING)
                Text(
                    text = "CAPTURE OPT-OUT DETECTED",
                    color = TextBone,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                RecessedPanel(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 6.dp,
                        vertical = 2.dp
                    )
                ) {
                    Text(
                        text = optOutState.launchedApp.appName.uppercase(),
                        color = SyncAmber,
                        fontWeight = FontWeight.Bold,
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                    )
                }
            }

            // Explanatory Technical Copy
            Text(
                text = "${optOutState.launchedApp.appName} blocks Android system audio capture " +
                        "(AudioAttributes.FLAG_NO_SYSTEM_CAPTURE), resulting in zero audio output.\n\n" +
                        "Switch to an alternative source to sync across your sound-rig:",
                color = TextMuted,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
            )

            // Action Trigger Keycaps
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TactileKeycapButton(
                    onClick = { onSelectDestination(FallbackDestination.LOCAL_STORAGE) },
                    variant = TactileButtonVariant.PRIMARY,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("btn_fallback_local")
                ) {
                    Text(text = "SWITCH TO LOCAL STORAGE (MODULE 01)")
                }

                TactileKeycapButton(
                    onClick = { onSelectDestination(FallbackDestination.SPOTIFY_REMOTE) },
                    variant = TactileButtonVariant.SURFACE,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("btn_fallback_spotify")
                ) {
                    Text(text = "USE SPOTIFY REMOTE (MODULE 03)")
                }
            }

            // Dismiss Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TactileKeycapButton(
                    onClick = onDismiss,
                    variant = TactileButtonVariant.SURFACE,
                    modifier = Modifier.testTag("btn_fallback_dismiss")
                ) {
                    Text(
                        text = "DISMISS",
                        color = TextDim
                    )
                }
            }
        }
    }
}

/**
 * Modal dialog variant for opt-out warning recovery.
 */
@Composable
fun CaptureOptOutDialog(
    state: FallbackState.OptOutDetected,
    onSelectDestination: (FallbackDestination) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Dialog(onDismissRequest = onDismiss) {
        CaptureFallbackBanner(
            state = state,
            onSelectDestination = onSelectDestination,
            onDismiss = onDismiss,
            modifier = modifier
        )
    }
}
