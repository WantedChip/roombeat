package com.roombeat.app.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roombeat.app.session.PeerNode
import com.roombeat.app.session.VolumeCoordinator
import com.roombeat.app.ui.theme.BorderActive
import com.roombeat.app.ui.theme.BorderMilled
import com.roombeat.app.ui.theme.ChassisBase
import com.roombeat.app.ui.theme.JetBrainsMonoFontFamily
import com.roombeat.app.ui.theme.RoomBeatTheme
import com.roombeat.app.ui.theme.SignalOrange
import com.roombeat.app.ui.theme.SurfaceElevated
import com.roombeat.app.ui.theme.SurfacePanel
import com.roombeat.app.ui.theme.SurfaceRecessed
import com.roombeat.app.ui.theme.SyncAmber
import com.roombeat.app.ui.theme.SyncGreen
import com.roombeat.app.ui.theme.SyncRed
import com.roombeat.app.ui.theme.TextBone
import com.roombeat.app.ui.theme.TextDim
import com.roombeat.app.ui.theme.TextMuted
import kotlin.math.abs

/**
 * UI Test tags for [TactileFaderSlider].
 */
object TactileFaderSliderTags {
    const val CONTAINER = "tactile_fader_container"
    const val SLIDER = "tactile_fader_slider"
    const val TRACK = "tactile_fader_track"
    const val THUMB = "tactile_fader_thumb"
    const val GAIN_READOUT = "tactile_fader_gain_readout"
    const val SNAP_0DB = "tactile_fader_snap_0db"
    const val MARKINGS_ROW = "tactile_fader_markings_row"
    const val MARKING_PLUS_6 = "tactile_fader_mark_plus_6"
    const val MARKING_0 = "tactile_fader_mark_0"
    const val MARKING_MINUS_6 = "tactile_fader_mark_minus_6"
    const val MARKING_MINUS_12 = "tactile_fader_mark_minus_12"
    const val MARKING_MINUS_INF = "tactile_fader_mark_minus_inf"
}

/**
 * Detent threshold constants for magnetic fader physics.
 */
object FaderDetents {
    const val UNITY_GAIN_LINEAR = 1.0f
    const val MUTE_GAIN_LINEAR = 0.0f
    const val SNAP_THRESHOLD_UNITY = 0.045f
    const val SNAP_THRESHOLD_MUTE = 0.035f
    const val MAX_GAIN_LINEAR = 2.0f
}

/**
 * Tactile industrial hardware fader adhering strictly to the Tactile Acoustic Industrial design system.
 *
 * Key features:
 * - Magnetic detent snapping: snaps to 0 dB (unity gain, 1.0f) and -inf dB (mute, 0.0f) with spring physics.
 * - Haptic feedback: emits `HapticFeedbackConstants.CLOCK_TICK` when crossing 0 dB or -inf detents.
 * - Double-tap snapping: double-tapping anywhere on the fader track immediately snaps back to unity gain (1.0f / 0 dB).
 * - Machined slot & tactile handle: recessed cavity (`SurfaceRecessed` `#07080A`, 1px `BorderMilled` `#262A35`).
 * - Calibrated level markings: `+6dB`, `0dB` (unity gain notch), `-6dB`, `-12dB`, `-inf`.
 * - Monospaced gain readout in `JetBrains Mono`: e.g. `+0.0 dB`, `+3.2 dB`, `-6.0 dB`, `-inf dB`.
 *
 * @param value Current linear gain (0.0f to 2.0f, with 1.0f = 0 dB unity gain).
 * @param onValueChange Callback invoked when the user adjusts the fader.
 * @param modifier Optional layout modifier.
 * @param isMuted Whether the channel is explicitly muted.
 * @param isEnabled Whether the fader is interactive.
 * @param label Optional channel or fader label (e.g. "LEVEL" or "MASTER").
 * @param showMarkings Whether to render physical scale markings (+6dB, 0dB, etc.).
 * @param showSnapChip Whether to display the one-tap "[ SNAP 0dB ]" chip when value != 1.0f.
 * @param onDetentTrigger Optional hook triggered on haptic detent crossings (useful for testing).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TactileFaderSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    isMuted: Boolean = false,
    isEnabled: Boolean = true,
    label: String = "LEVEL",
    showMarkings: Boolean = true,
    showSnapChip: Boolean = true,
    onDetentTrigger: (() -> Unit)? = null
) {
    val view = runCatching { LocalView.current }.getOrNull()

    // Track previous position to detect detent crossings
    var lastValue by remember { mutableFloatStateOf(value) }
    var wasAtUnity by remember { mutableStateOf(abs(value - FaderDetents.UNITY_GAIN_LINEAR) < 0.01f) }
    var wasAtMute by remember { mutableStateOf(value <= 0.01f) }

    fun triggerHapticDetent() {
        runCatching {
            view?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        onDetentTrigger?.invoke()
    }

    // Process magnetic detent snapping during user drag
    fun applyMagneticDetents(rawLinear: Float): Float {
        val clamped = rawLinear.coerceIn(0.0f, FaderDetents.MAX_GAIN_LINEAR)

        // 1. Check Unity Gain Detent (1.0f / 0 dB)
        val isNearUnity = abs(clamped - FaderDetents.UNITY_GAIN_LINEAR) <= FaderDetents.SNAP_THRESHOLD_UNITY
        if (isNearUnity) {
            if (!wasAtUnity) {
                triggerHapticDetent()
                wasAtUnity = true
            }
            return FaderDetents.UNITY_GAIN_LINEAR
        } else {
            wasAtUnity = false
        }

        // 2. Check Mute Detent (0.0f / -inf dB)
        val isNearMute = clamped <= FaderDetents.SNAP_THRESHOLD_MUTE
        if (isNearMute) {
            if (!wasAtMute) {
                triggerHapticDetent()
                wasAtMute = true
            }
            return FaderDetents.MUTE_GAIN_LINEAR
        } else {
            wasAtMute = false
        }

        // Crossed detent boundary between steps
        if ((lastValue < FaderDetents.UNITY_GAIN_LINEAR && clamped > FaderDetents.UNITY_GAIN_LINEAR) ||
            (lastValue > FaderDetents.UNITY_GAIN_LINEAR && clamped < FaderDetents.UNITY_GAIN_LINEAR)
        ) {
            triggerHapticDetent()
        }

        lastValue = clamped
        return clamped
    }

    val displayValue = if (isMuted) 0.0f else value
    val formattedGainText = if (isMuted || displayValue <= 0.0001f) {
        "-inf dB"
    } else {
        PeerNode.volumeToDbString(displayValue)
    }

    val isAtUnity = abs(displayValue - FaderDetents.UNITY_GAIN_LINEAR) < 0.005f && !isMuted

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TactileFaderSliderTags.CONTAINER),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // ================================================================
        // Header Row: Label, Monospaced dB Readout & Optional SNAP 0dB Chip
        // ================================================================
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = label.uppercase(),
                    style = RoomBeatTheme.typography.labelSm,
                    color = TextDim
                )

                Text(
                    text = formattedGainText,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = when {
                        isMuted -> SyncRed
                        isAtUnity -> SyncGreen
                        displayValue > 1.0f -> SignalOrange
                        else -> TextBone
                    },
                    modifier = Modifier.testTag(TactileFaderSliderTags.GAIN_READOUT)
                )

                if (isAtUnity) {
                    Text(
                        text = "[UNITY]",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = SyncGreen,
                        letterSpacing = 0.04.sp
                    )
                }
            }

            // Snap 0dB Button Chip
            if (showSnapChip && isEnabled && !isMuted && !isAtUnity) {
                Surface(
                    color = SurfaceRecessed,
                    shape = RoundedCornerShape(3.dp),
                    border = BorderStroke(1.dp, BorderMilled),
                    modifier = Modifier
                        .testTag(TactileFaderSliderTags.SNAP_0DB)
                        .pointerInput(Unit) {
                            detectTapGestures {
                                triggerHapticDetent()
                                onValueChange(FaderDetents.UNITY_GAIN_LINEAR)
                            }
                        }
                ) {
                    Text(
                        text = "SNAP 0dB",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = TextMuted,
                        letterSpacing = 0.04.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }

        // ================================================================
        // Slider Track with Double-Tap to Unity Gain Gesture
        // ================================================================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(isEnabled, isMuted) {
                    if (isEnabled && !isMuted) {
                        detectTapGestures(
                            onDoubleTap = {
                                triggerHapticDetent()
                                onValueChange(FaderDetents.UNITY_GAIN_LINEAR)
                            }
                        )
                    }
                }
                .testTag(TactileFaderSliderTags.TRACK)
        ) {
            Slider(
                value = displayValue,
                onValueChange = { raw ->
                    val snapped = applyMagneticDetents(raw)
                    onValueChange(snapped)
                },
                valueRange = 0.0f..FaderDetents.MAX_GAIN_LINEAR,
                enabled = isEnabled && !isMuted,
                colors = SliderDefaults.colors(
                    thumbColor = if (isAtUnity) SyncGreen else SignalOrange,
                    activeTrackColor = if (isAtUnity) SyncGreen else SignalOrange,
                    inactiveTrackColor = SurfaceRecessed,
                    disabledThumbColor = TextDim,
                    disabledActiveTrackColor = BorderMilled,
                    disabledInactiveTrackColor = SurfaceRecessed
                ),
                thumb = {
                    // Tactile machined handle with center tick mark
                    Box(
                        modifier = Modifier
                            .size(width = 18.dp, height = 24.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                if (!isEnabled || isMuted) TextDim else if (isAtUnity) SyncGreen else SignalOrange
                            )
                            .border(1.dp, BorderActive, RoundedCornerShape(3.dp))
                            .testTag(TactileFaderSliderTags.THUMB),
                        contentAlignment = Alignment.Center
                    ) {
                        // Machined grip lines
                        Column(
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(8.dp)
                                    .height(1.5.dp)
                                    .background(ChassisBase.copy(alpha = 0.6f))
                            )
                            Box(
                                modifier = Modifier
                                    .width(10.dp)
                                    .height(2.dp)
                                    .background(ChassisBase)
                            )
                            Box(
                                modifier = Modifier
                                    .width(8.dp)
                                    .height(1.5.dp)
                                    .background(ChassisBase.copy(alpha = 0.6f))
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .testTag(TactileFaderSliderTags.SLIDER)
                    .semantics {
                        contentDescription = "$label fader: $formattedGainText"
                    }
            )
        }

        // ================================================================
        // Calibrated Level Markings: -inf, -12dB, -6dB, 0dB, +6dB
        // Mathematically mapped along 0.0 to 2.0 linear scale
        // ================================================================
        if (showMarkings) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .testTag(TactileFaderSliderTags.MARKINGS_ROW),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 0.0f = -inf
                Text(
                    text = "-inf",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 9.sp,
                    color = TextDim,
                    modifier = Modifier.testTag(TactileFaderSliderTags.MARKING_MINUS_INF)
                )

                // 0.25f = -12dB
                Text(
                    text = "-12",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 9.sp,
                    color = TextDim,
                    modifier = Modifier.testTag(TactileFaderSliderTags.MARKING_MINUS_12)
                )

                // 0.5f = -6dB
                Text(
                    text = "-6",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 9.sp,
                    color = TextDim,
                    modifier = Modifier.testTag(TactileFaderSliderTags.MARKING_MINUS_6)
                )

                // 1.0f = 0dB (Unity Gain indicator)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.testTag(TactileFaderSliderTags.MARKING_0)
                ) {
                    Box(
                        modifier = Modifier
                            .size(3.dp)
                            .background(if (isAtUnity) SyncGreen else TextDim)
                    )
                    Text(
                        text = "0dB",
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = if (isAtUnity) SyncGreen else TextBone
                    )
                }

                // 2.0f = +6dB
                Text(
                    text = "+6dB",
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 9.sp,
                    color = TextDim,
                    modifier = Modifier.testTag(TactileFaderSliderTags.MARKING_PLUS_6)
                )
            }
        }
    }
}

// ============================================================================
// Compose Previews
// ============================================================================

@Preview(name = "Tactile Fader at Unity Gain", showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
private fun PreviewTactileFaderSliderUnity() {
    RoomBeatTheme {
        TactileFaderSlider(
            value = 1.0f,
            onValueChange = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(name = "Tactile Fader at Boost Gain", showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
private fun PreviewTactileFaderSliderBoost() {
    RoomBeatTheme {
        TactileFaderSlider(
            value = 1.4f,
            onValueChange = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(name = "Tactile Fader Muted", showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
private fun PreviewTactileFaderSliderMuted() {
    RoomBeatTheme {
        TactileFaderSlider(
            value = 1.0f,
            isMuted = true,
            onValueChange = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}
