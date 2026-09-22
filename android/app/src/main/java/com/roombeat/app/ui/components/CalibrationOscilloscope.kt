package com.roombeat.app.ui.components

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roombeat.app.ui.theme.BorderActive
import com.roombeat.app.ui.theme.BorderMilled
import com.roombeat.app.ui.theme.ChassisBase
import com.roombeat.app.ui.theme.RoomBeatTheme
import com.roombeat.app.ui.theme.SignalOrange
import com.roombeat.app.ui.theme.SurfacePanel
import com.roombeat.app.ui.theme.SurfaceRecessed
import com.roombeat.app.ui.theme.SyncAmber
import com.roombeat.app.ui.theme.SyncGreen
import com.roombeat.app.ui.theme.SyncRed
import com.roombeat.app.ui.theme.TextBone
import com.roombeat.app.ui.theme.TextDim
import com.roombeat.app.ui.theme.TextMuted
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Detects whether the user or Android system has enabled reduced motion / disabled animations.
 * Inspects [Settings.Global.ANIMATOR_DURATION_SCALE] with fallback to normal motion.
 */
@Composable
fun isSystemReducedMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        try {
            val scale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f
            )
            scale == 0f
        } catch (_: Throwable) {
            false
        }
    }
}

/**
 * Custom Jetpack Compose [Canvas] component rendering a real-time sweeping acoustic oscilloscope radar.
 *
 * Adheres strictly to the "Tactile Acoustic Industrial" aesthetic:
 * - Machined obsidian base (#0B0C0E / #07080A), milled grid (#262A35), phosphor green (#00E599) sweeps/traces,
 *   amber (#FFB800) jitter rings, and signal orange (#FF5500) triggers.
 * - Draws at 60fps without triggering unnecessary Compose recompositions (animated values read solely
 *   in the DrawScope canvas draw phase).
 * - Reduced motion mode: Replaces sweeping radar rotation with a static high-contrast reticle,
 *   crisp concentric grid, and static acoustic waveform curve.
 *
 * @param modifier Compose modifier applied to the oscilloscope container.
 * @param isReducedMotion When true, disables rotating radar sweep in favor of static waveform telemetry.
 * @param isSyncLocked When true, locks reticle with phosphor green glow, brackets, and steady lock ring.
 * @param isMulticastBlocked When true, highlights perimeter and reticle with amber warning cues.
 * @param offsetMs Current average or peer clock offset in milliseconds.
 * @param jitterMs Current measured network jitter standard deviation in milliseconds.
 * @param rttMs Current average round-trip time in milliseconds.
 * @param progress Overall calibration progress fraction (0.0f to 1.0f).
 * @param rippleTrigger Monotonic sequence or timestamp of incoming probe echo, triggering visual ripples.
 */
@Composable
fun CalibrationOscilloscope(
    modifier: Modifier = Modifier,
    isReducedMotion: Boolean = isSystemReducedMotionEnabled(),
    isSyncLocked: Boolean = false,
    isMulticastBlocked: Boolean = false,
    offsetMs: Double = 0.0,
    jitterMs: Double = 0.0,
    rttMs: Double = 0.0,
    progress: Float = 0.0f,
    rippleTrigger: Long = 0L
) {
    // 60fps infinite transition for radar sweep and waveform ripple
    val infiniteTransition = rememberInfiniteTransition(label = "OscilloscopeAnimations")

    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RadarSweepAngle"
    )

    val ripplePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "WaveformRipplePhase"
    )

    val lockBreatheAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "LockBreatheAlpha"
    )

    Box(
        modifier = modifier
            .aspectRatio(1.0f)
            .clip(CircleShape)
            .background(SurfaceRecessed)
            .testTag("calibration_oscilloscope"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .testTag("calibration_oscilloscope_canvas")
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val outerRadius = (minOf(size.width, size.height) / 2f) - 6.dp.toPx()
            if (outerRadius <= 0f) return@Canvas

            // 1. Outer Machined Enclosure Ring
            drawCircle(
                color = if (isMulticastBlocked) SyncAmber else if (isSyncLocked) SyncGreen.copy(alpha = lockBreatheAlpha) else BorderMilled,
                radius = outerRadius,
                center = center,
                style = Stroke(width = if (isSyncLocked) 2.dp.toPx() else 1.5.dp.toPx())
            )

            // Outer degree tick marks (every 30 degrees)
            for (deg in 0 until 360 step 30) {
                val rad = deg * PI.toFloat() / 180f
                val isMajor = deg % 90 == 0
                val tickLength = if (isMajor) 8.dp.toPx() else 4.dp.toPx()
                val start = Offset(
                    x = center.x + (outerRadius - tickLength) * cos(rad),
                    y = center.y + (outerRadius - tickLength) * sin(rad)
                )
                val end = Offset(
                    x = center.x + outerRadius * cos(rad),
                    y = center.y + outerRadius * sin(rad)
                )
                drawLine(
                    color = if (isMajor) BorderActive else BorderMilled,
                    start = start,
                    end = end,
                    strokeWidth = 1.dp.toPx()
                )
            }

            // 2. Concentric Milled Range Reticles (25%, 50%, 75%)
            val rangePercentages = floatArrayOf(0.25f, 0.50f, 0.75f)
            val dashedEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()), 0f)

            for (p in rangePercentages) {
                drawCircle(
                    color = BorderMilled.copy(alpha = 0.7f),
                    radius = outerRadius * p,
                    center = center,
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = dashedEffect
                    )
                )
            }

            // 3. Orthogonal Crosshairs (Horizontal & Vertical Axes)
            drawLine(
                color = BorderMilled,
                start = Offset(center.x - outerRadius, center.y),
                end = Offset(center.x + outerRadius, center.y),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = BorderMilled,
                start = Offset(center.x, center.y - outerRadius),
                end = Offset(center.x, center.y + outerRadius),
                strokeWidth = 1.dp.toPx()
            )

            // Crosshair tick marks along X and Y axes
            val tickCount = 6
            for (i in 1..tickCount) {
                val dist = (outerRadius / (tickCount + 1)) * i
                val tickSize = 3.dp.toPx()
                // Horizontal ticks
                drawLine(
                    color = BorderActive,
                    start = Offset(center.x + dist, center.y - tickSize),
                    end = Offset(center.x + dist, center.y + tickSize),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = BorderActive,
                    start = Offset(center.x - dist, center.y - tickSize),
                    end = Offset(center.x - dist, center.y + tickSize),
                    strokeWidth = 1.dp.toPx()
                )
                // Vertical ticks
                drawLine(
                    color = BorderActive,
                    start = Offset(center.x - tickSize, center.y + dist),
                    end = Offset(center.x + tickSize, center.y + dist),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = BorderActive,
                    start = Offset(center.x - tickSize, center.y - dist),
                    end = Offset(center.x + tickSize, center.y - dist),
                    strokeWidth = 1.dp.toPx()
                )
            }

            // 4. Jitter Range Ring (Amber Warning Ring)
            if (jitterMs > 0.0) {
                val jitterFraction = (jitterMs.toFloat() / 10.0f).coerceIn(0.15f, 0.95f)
                val jitterRadius = outerRadius * jitterFraction
                drawCircle(
                    color = SyncAmber.copy(alpha = 0.45f),
                    radius = jitterRadius,
                    center = center,
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()), 0f)
                    )
                )
            }

            // 5. Dynamic Sweep vs Reduced Motion Mode
            if (isReducedMotion) {
                // REDUCED MOTION MODE: Render static high-contrast reticle & acoustic waveform trace
                drawStaticWaveform(
                    center = center,
                    outerRadius = outerRadius,
                    isSyncLocked = isSyncLocked,
                    isMulticastBlocked = isMulticastBlocked
                )
            } else {
                // NORMAL MOTION MODE: Real-time sweeping radar beam and expanding probe ripples

                // Expanding waveform ripples synchronized with incoming probes
                val ripple1 = (ripplePhase * outerRadius)
                val ripple2 = (((ripplePhase + 0.5f) % 1f) * outerRadius)
                val rippleAlpha1 = (1f - (ripple1 / outerRadius)).coerceIn(0f, 1f) * 0.35f
                val rippleAlpha2 = (1f - (ripple2 / outerRadius)).coerceIn(0f, 1f) * 0.35f

                val rippleColor = if (isMulticastBlocked) SyncAmber else SyncGreen

                drawCircle(
                    color = rippleColor.copy(alpha = rippleAlpha1),
                    radius = ripple1,
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx())
                )
                drawCircle(
                    color = rippleColor.copy(alpha = rippleAlpha2),
                    radius = ripple2,
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx())
                )

                // Fading Phosphor Green Sweep Sector Trail (Trailing 45 degrees)
                val trailDegrees = 45f
                val stepDegrees = 5f
                var curStep = 0f
                while (curStep < trailDegrees) {
                    val angleOffset = -curStep
                    val startAngle = (sweepAngle + angleOffset + 360f) % 360f
                    val stepAlpha = ((1f - (curStep / trailDegrees)) * 0.22f).coerceIn(0f, 0.3f)
                    drawArc(
                        color = if (isMulticastBlocked) SyncAmber.copy(alpha = stepAlpha) else SyncGreen.copy(alpha = stepAlpha),
                        startAngle = startAngle,
                        sweepAngle = stepDegrees,
                        useCenter = true,
                        topLeft = Offset(center.x - outerRadius, center.y - outerRadius),
                        size = Size(outerRadius * 2f, outerRadius * 2f)
                    )
                    curStep += stepDegrees
                }

                // Phosphor Green Radar Sweep Beam
                val sweepRad = sweepAngle * PI.toFloat() / 180f
                val beamEnd = Offset(
                    x = center.x + outerRadius * cos(sweepRad),
                    y = center.y + outerRadius * sin(sweepRad)
                )

                drawLine(
                    color = if (isMulticastBlocked) SyncAmber else SyncGreen,
                    start = center,
                    end = beamEnd,
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )

                // Sweep Beam Tip Pip
                drawCircle(
                    color = if (isMulticastBlocked) SyncAmber else Color.White,
                    radius = 3.dp.toPx(),
                    center = beamEnd
                )
            }

            // 6. Sync-Locked Visual Stamping (Target Lock Brackets & Lock Ring)
            if (isSyncLocked) {
                val lockRingRadius = outerRadius * 0.55f
                drawCircle(
                    color = SyncGreen.copy(alpha = lockBreatheAlpha * 0.5f),
                    radius = lockRingRadius,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )

                // Precision corner lock brackets framing center
                val bracketSize = 14.dp.toPx()
                val bracketDist = 24.dp.toPx()
                val bracketColor = SyncGreen.copy(alpha = lockBreatheAlpha)
                val bracketStroke = 2.dp.toPx()

                // Top-Left bracket
                drawLine(bracketColor, Offset(center.x - bracketDist, center.y - bracketDist), Offset(center.x - bracketDist + bracketSize, center.y - bracketDist), bracketStroke)
                drawLine(bracketColor, Offset(center.x - bracketDist, center.y - bracketDist), Offset(center.x - bracketDist, center.y - bracketDist + bracketSize), bracketStroke)

                // Top-Right bracket
                drawLine(bracketColor, Offset(center.x + bracketDist, center.y - bracketDist), Offset(center.x + bracketDist - bracketSize, center.y - bracketDist), bracketStroke)
                drawLine(bracketColor, Offset(center.x + bracketDist, center.y - bracketDist), Offset(center.x + bracketDist, center.y - bracketDist + bracketSize), bracketStroke)

                // Bottom-Left bracket
                drawLine(bracketColor, Offset(center.x - bracketDist, center.y + bracketDist), Offset(center.x - bracketDist + bracketSize, center.y + bracketDist), bracketStroke)
                drawLine(bracketColor, Offset(center.x - bracketDist, center.y + bracketDist), Offset(center.x - bracketDist, center.y + bracketDist - bracketSize), bracketStroke)

                // Bottom-Right bracket
                drawLine(bracketColor, Offset(center.x + bracketDist, center.y + bracketDist), Offset(center.x + bracketDist - bracketSize, center.y + bracketDist), bracketStroke)
                drawLine(bracketColor, Offset(center.x + bracketDist, center.y + bracketDist), Offset(center.x + bracketDist, center.y + bracketDist - bracketSize), bracketStroke)
            }

            // 7. Center Acoustic Target Pin
            val centerDotColor = if (isMulticastBlocked) SyncAmber else if (isSyncLocked) SyncGreen else SignalOrange
            drawCircle(
                color = centerDotColor,
                radius = 4.dp.toPx(),
                center = center
            )
            drawCircle(
                color = centerDotColor.copy(alpha = 0.35f),
                radius = 8.dp.toPx(),
                center = center
            )

            // 8. Signal Orange Calibration Top Trigger Indicator
            val triggerTop = Offset(center.x, center.y - outerRadius)
            drawCircle(
                color = SignalOrange,
                radius = 3.5.dp.toPx(),
                center = triggerTop
            )
        }
    }
}

/**
 * Draws a high-contrast static acoustic waveform across the scope for reduced-motion mode.
 */
private fun DrawScope.drawStaticWaveform(
    center: Offset,
    outerRadius: Float,
    isSyncLocked: Boolean,
    isMulticastBlocked: Boolean
) {
    val waveformPath = Path()
    val width = outerRadius * 1.6f
    val startX = center.x - (width / 2f)
    val endX = center.x + (width / 2f)
    val points = 80
    val amplitude = outerRadius * (if (isSyncLocked) 0.18f else 0.28f)
    val waveColor = if (isMulticastBlocked) SyncAmber else SyncGreen

    for (i in 0..points) {
        val fraction = i.toFloat() / points
        val x = startX + fraction * width
        // Modulated sine wave dampened at the edges
        val envelope = sin(fraction * PI.toFloat())
        val frequency = if (isSyncLocked) 4f * PI.toFloat() else 6f * PI.toFloat()
        val y = center.y + sin(fraction * frequency) * amplitude * envelope

        if (i == 0) {
            waveformPath.moveTo(x, y)
        } else {
            waveformPath.lineTo(x, y)
        }
    }

    drawPath(
        path = waveformPath,
        color = waveColor,
        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
    )
}

// =============================================================================
// Previews
// =============================================================================

@Preview(name = "Active Oscilloscope Sweep", showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
private fun CalibrationOscilloscopeActivePreview() {
    RoomBeatTheme {
        Box(modifier = Modifier.padding(24.dp).size(280.dp)) {
            CalibrationOscilloscope(
                isReducedMotion = false,
                isSyncLocked = false,
                isMulticastBlocked = false,
                offsetMs = 0.34,
                jitterMs = 1.2,
                rttMs = 4.5,
                progress = 0.65f
            )
        }
    }
}

@Preview(name = "Sync Locked Oscilloscope", showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
private fun CalibrationOscilloscopeLockedPreview() {
    RoomBeatTheme {
        Box(modifier = Modifier.padding(24.dp).size(280.dp)) {
            CalibrationOscilloscope(
                isReducedMotion = false,
                isSyncLocked = true,
                isMulticastBlocked = false,
                offsetMs = 0.18,
                jitterMs = 0.45,
                rttMs = 3.1,
                progress = 1.0f
            )
        }
    }
}

@Preview(name = "Reduced Motion Static Waveform", showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
private fun CalibrationOscilloscopeReducedMotionPreview() {
    RoomBeatTheme {
        Box(modifier = Modifier.padding(24.dp).size(280.dp)) {
            CalibrationOscilloscope(
                isReducedMotion = true,
                isSyncLocked = true,
                isMulticastBlocked = false,
                offsetMs = 0.22,
                jitterMs = 0.5,
                rttMs = 3.4,
                progress = 1.0f
            )
        }
    }
}

@Preview(name = "Multicast Blocked Oscilloscope", showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
private fun CalibrationOscilloscopeBlockedPreview() {
    RoomBeatTheme {
        Box(modifier = Modifier.padding(24.dp).size(280.dp)) {
            CalibrationOscilloscope(
                isReducedMotion = false,
                isSyncLocked = false,
                isMulticastBlocked = true,
                offsetMs = 4.8,
                jitterMs = 8.5,
                rttMs = 34.0,
                progress = 0.4f
            )
        }
    }
}
