package com.roombeat.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roombeat.app.audio.AudioRmsAnalyzer
import com.roombeat.app.audio.ChannelLevels
import com.roombeat.app.ui.theme.BorderActive
import com.roombeat.app.ui.theme.BorderMilled
import com.roombeat.app.ui.theme.ChassisBase
import com.roombeat.app.ui.theme.JetBrainsMonoFontFamily
import com.roombeat.app.ui.theme.RoomBeatTheme
import com.roombeat.app.ui.theme.SurfacePanel
import com.roombeat.app.ui.theme.SurfaceRecessed
import com.roombeat.app.ui.theme.SyncAmber
import com.roombeat.app.ui.theme.SyncGreen
import com.roombeat.app.ui.theme.SyncRed
import com.roombeat.app.ui.theme.TextBone
import com.roombeat.app.ui.theme.TextDim
import com.roombeat.app.ui.theme.TextMuted
import kotlinx.coroutines.isActive

/**
 * Standard calibrated dB ticks for VU peak meter scale.
 */
private val SCALE_TICKS_DB = listOf(3.0f, 0.0f, -6.0f, -12.0f, -24.0f, -36.0f, -48.0f, -60.0f)

/**
 * High-performance, multi-channel VU peak meter adhering strictly to the
 * Tactile Acoustic Industrial design system.
 *
 * Key features:
 * - Compose [Canvas] segmented hardware LED bars:
 *   - Phosphor Green (`#00E599`) below -6 dBFS
 *   - Amber (`#FFB800`) from -6 dBFS to 0 dBFS
 *   - Red (`#FF334B`) above 0 dBFS / clip
 * - IEC 60268-10 ballistic physics:
 *   - Instantaneous attack/rise (<=16ms)
 *   - 300ms peak hold indicator
 *   - 250ms exponential decay falloff
 * - High-efficiency 60fps/120fps Canvas drawing driven by frame time with zero Compose recomposition stutter.
 * - Reduced motion mode: renders solid static RMS bars with prominent numeric dB readout (`-3.2 dB`).
 * - Multi-channel view visualizing amplitude activity across local host and all connected peers.
 *
 * @param channels List of [ChannelLevels] representing all participating phone nodes.
 * @param modifier Outer layout modifier.
 * @param isReducedMotion Accessibility flag. When true, disables continuous 60fps frame loops
 *                        and renders solid static RMS bars with numeric dB readout.
 * @param segmentCount Number of discrete hardware LED segments per meter bar (default 24).
 * @param showScaleTicks Whether to render calibrated dBFS scale ticks on the left rail.
 * @param showNumericReadouts Whether to display monospaced dB readouts below channels.
 * @param meterHeight Height of the meter canvas area.
 * @param title Header title string.
 * @param onChannelClick Optional callback when a channel column is tapped.
 */
@Composable
fun MultiChannelVuMeter(
    channels: List<ChannelLevels>,
    modifier: Modifier = Modifier,
    isReducedMotion: Boolean = isSystemReducedMotionEnabled(),
    segmentCount: Int = 24,
    showScaleTicks: Boolean = true,
    showNumericReadouts: Boolean = true,
    meterHeight: Dp = 190.dp,
    title: String = "MULTI-CHANNEL VU METER",
    onChannelClick: ((String) -> Unit)? = null
) {
    MultiChannelVuMeter(
        channelsProvider = { channels },
        modifier = modifier,
        isReducedMotion = isReducedMotion,
        segmentCount = segmentCount,
        showScaleTicks = showScaleTicks,
        showNumericReadouts = showNumericReadouts,
        meterHeight = meterHeight,
        title = title,
        onChannelClick = onChannelClick
    )
}

/**
 * Overload of [MultiChannelVuMeter] backed directly by an [AudioRmsAnalyzer].
 */
@Composable
fun MultiChannelVuMeter(
    analyzer: AudioRmsAnalyzer,
    modifier: Modifier = Modifier,
    isReducedMotion: Boolean = isSystemReducedMotionEnabled(),
    segmentCount: Int = 24,
    showScaleTicks: Boolean = true,
    showNumericReadouts: Boolean = true,
    meterHeight: Dp = 190.dp,
    title: String = "MULTI-CHANNEL VU METER",
    onChannelClick: ((String) -> Unit)? = null
) {
    val channelsMap by analyzer.channelsFlow.collectAsState()
    val channelsList = remember(channelsMap) { channelsMap.values.toList() }

    MultiChannelVuMeter(
        channelsProvider = {
            if (channelsList.isNotEmpty()) channelsList else listOf(
                ChannelLevels(channelId = AudioRmsAnalyzer.CHANNEL_LOCAL, name = "Host")
            )
        },
        modifier = modifier,
        isReducedMotion = isReducedMotion,
        segmentCount = segmentCount,
        showScaleTicks = showScaleTicks,
        showNumericReadouts = showNumericReadouts,
        meterHeight = meterHeight,
        title = title,
        onChannelClick = onChannelClick
    )
}

/**
 * Primary lambda-driven [MultiChannelVuMeter] ensuring zero Compose recomposition overhead
 * during 60fps/120fps display rendering.
 */
@Composable
fun MultiChannelVuMeter(
    channelsProvider: () -> List<ChannelLevels>,
    modifier: Modifier = Modifier,
    isReducedMotion: Boolean = isSystemReducedMotionEnabled(),
    segmentCount: Int = 24,
    showScaleTicks: Boolean = true,
    showNumericReadouts: Boolean = true,
    meterHeight: Dp = 190.dp,
    title: String = "MULTI-CHANNEL VU METER",
    onChannelClick: ((String) -> Unit)? = null
) {
    val typography = RoomBeatTheme.typography

    // Display frame tick counter for 60fps/120fps hardware vsync invalidation
    var frameTick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(isReducedMotion) {
        if (!isReducedMotion) {
            while (isActive) {
                withFrameNanos { frameNanos ->
                    frameTick = frameNanos
                }
            }
        }
    }

    val textMeasurer = runCatching { rememberTextMeasurer() }.getOrNull()

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("multi_channel_vu_meter"),
        shape = RoundedCornerShape(8.dp),
        color = SurfacePanel,
        border = BorderStroke(1.dp, BorderMilled)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Module Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = typography.labelSm,
                    color = TextBone
                )

                Text(
                    text = if (isReducedMotion) "REDUCED MOTION · STATIC" else "60 FPS · IEC 60268-10",
                    style = typography.codeXs,
                    color = if (isReducedMotion) SyncAmber else SyncGreen
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Recessed Meter Cavity
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(meterHeight)
                    .background(SurfaceRecessed, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("vu_meter_canvas")
                ) {
                    // Subscribe draw phase to 60fps frame tick without triggering recomposition
                    val drawTick = frameTick

                    val channels = channelsProvider()
                    val activeChannels = if (channels.isNotEmpty()) {
                        channels
                    } else {
                        listOf(
                            ChannelLevels(
                                channelId = AudioRmsAnalyzer.CHANNEL_LOCAL,
                                name = "HOST"
                            )
                        )
                    }

                    drawMultiChannelVu(
                        channels = activeChannels,
                        isReducedMotion = isReducedMotion,
                        segmentCount = segmentCount,
                        showScaleTicks = showScaleTicks,
                        showNumericReadouts = showNumericReadouts,
                        textMeasurer = textMeasurer
                    )
                }
            }

            // Bottom Accessibility / Numeric dB Readout Strip
            if (showNumericReadouts) {
                val currentChannels = channelsProvider()
                val displayChannels = if (currentChannels.isNotEmpty()) currentChannels else listOf(
                    ChannelLevels(channelId = AudioRmsAnalyzer.CHANNEL_LOCAL, name = "HOST")
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (showScaleTicks) {
                        Spacer(modifier = Modifier.width(34.dp))
                    }

                    displayChannels.forEach { ch ->
                        val readColor = when {
                            ch.isClipping -> SyncRed
                            ch.maxRmsDbfs >= AudioRmsAnalyzer.GREEN_AMBER_THRESHOLD_DBFS -> SyncAmber
                            else -> TextBone
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable(enabled = onChannelClick != null) {
                                    onChannelClick?.invoke(ch.channelId)
                                }
                                .semantics {
                                    contentDescription = "${ch.name.ifEmpty { ch.channelId }}: ${ch.formattedDbfs}"
                                },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = (ch.name.ifEmpty { ch.channelId }).uppercase().take(8),
                                style = typography.codeXs,
                                color = TextMuted,
                                maxLines = 1
                            )

                            Text(
                                text = ch.formattedDbfs,
                                style = typography.codeXs.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                                color = readColor,
                                maxLines = 1,
                                modifier = Modifier.testTag("vu_db_readout_${ch.channelId}")
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Draws the scale ticks and channel LED bars onto the Canvas DrawScope.
 */
private fun DrawScope.drawMultiChannelVu(
    channels: List<ChannelLevels>,
    isReducedMotion: Boolean,
    segmentCount: Int,
    showScaleTicks: Boolean,
    showNumericReadouts: Boolean,
    textMeasurer: TextMeasurer?
) {
    val scaleWidthPx = if (showScaleTicks) 34.dp.toPx() else 0f
    val meterTop = 16.dp.toPx()
    val meterBottom = size.height - (if (showNumericReadouts) 22.dp.toPx() else 4.dp.toPx())
    val meterHeightPx = maxOf(10f, meterBottom - meterTop)

    // 1. Draw Calibrated dBFS Scale Ticks on Left Rail
    if (showScaleTicks) {
        val tickStyle = TextStyle(
            fontFamily = JetBrainsMonoFontFamily,
            fontSize = 9.sp,
            color = TextDim
        )

        for (db in SCALE_TICKS_DB) {
            val normalized = AudioRmsAnalyzer.dbfsToNormalized(db)
            val y = meterBottom - (normalized * meterHeightPx)

            val isClipTick = db >= 0f
            val isAmberTick = db == -6f
            val tickColor = when {
                isClipTick -> SyncRed.copy(alpha = 0.8f)
                isAmberTick -> SyncAmber.copy(alpha = 0.8f)
                else -> BorderActive
            }

            // Notch line
            drawLine(
                color = tickColor,
                start = Offset(x = scaleWidthPx - 6.dp.toPx(), y = y),
                end = Offset(x = scaleWidthPx, y = y),
                strokeWidth = if (isClipTick || isAmberTick) 1.5.dp.toPx() else 1.dp.toPx()
            )

            // Text label
            val labelStr = when {
                db > 0f -> "+${db.toInt()}"
                db == -60f -> "-INF"
                else -> "${db.toInt()}"
            }

            if (textMeasurer != null) {
                drawText(
                    textMeasurer = textMeasurer,
                    text = labelStr,
                    topLeft = Offset(x = 2.dp.toPx(), y = y - 6.dp.toPx()),
                    style = tickStyle
                )
            }
        }
    }

    // 2. Draw Channels
    val availableWidthPx = size.width - scaleWidthPx
    val numChannels = maxOf(1, channels.size)
    val channelWidthPx = availableWidthPx / numChannels
    val channelPaddingPx = 6.dp.toPx()

    for ((index, channel) in channels.withIndex()) {
        val channelLeftPx = scaleWidthPx + (index * channelWidthPx) + channelPaddingPx
        val channelRightPx = scaleWidthPx + ((index + 1) * channelWidthPx) - channelPaddingPx
        val usableWidthPx = maxOf(10f, channelRightPx - channelLeftPx)

        // Draw Channel Name Header on Canvas if textMeasurer available
        if (textMeasurer != null) {
            val nameStyle = TextStyle(
                fontFamily = JetBrainsMonoFontFamily,
                fontSize = 9.sp,
                color = TextMuted
            )
            val channelTag = (channel.name.ifEmpty { channel.channelId }).uppercase().take(8)
            drawText(
                textMeasurer = textMeasurer,
                text = channelTag,
                topLeft = Offset(channelLeftPx, 2.dp.toPx()),
                style = nameStyle
            )
        }

        if (isReducedMotion) {
            // =========================================================================
            // REDUCED MOTION MODE: Solid static RMS bar with numeric dB readout
            // =========================================================================
            drawReducedMotionChannel(
                channel = channel,
                leftPx = channelLeftPx,
                widthPx = usableWidthPx,
                topPx = meterTop,
                bottomPx = meterBottom,
                meterHeightPx = meterHeightPx
            )
        } else {
            // =========================================================================
            // HIGH-PERFORMANCE 60FPS MODE: Dual Stereo Segmented LED Ladder
            // =========================================================================
            drawSegmentedLedChannel(
                channel = channel,
                leftPx = channelLeftPx,
                widthPx = usableWidthPx,
                topPx = meterTop,
                bottomPx = meterBottom,
                meterHeightPx = meterHeightPx,
                segmentCount = segmentCount
            )
        }

        // Draw divider between multiple channels
        if (index < numChannels - 1) {
            val dividerX = scaleWidthPx + ((index + 1) * channelWidthPx)
            drawLine(
                color = BorderMilled,
                start = Offset(dividerX, meterTop),
                end = Offset(dividerX, meterBottom),
                strokeWidth = 1.dp.toPx()
            )
        }
    }
}

/**
 * Draws the high-performance segmented hardware LED ladder for stereo Left & Right bars.
 */
private fun DrawScope.drawSegmentedLedChannel(
    channel: ChannelLevels,
    leftPx: Float,
    widthPx: Float,
    topPx: Float,
    bottomPx: Float,
    meterHeightPx: Float,
    segmentCount: Int
) {
    val barGapPx = 3.dp.toPx()
    val barWidthPx = maxOf(4f, (widthPx - barGapPx) / 2f)

    val leftBarX = leftPx
    val rightBarX = leftPx + barWidthPx + barGapPx

    val segmentGapPx = 2.dp.toPx()
    val totalGapsHeight = (segmentCount - 1) * segmentGapPx
    val segmentHeightPx = maxOf(2f, (meterHeightPx - totalGapsHeight) / segmentCount)
    val cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())

    val leftNormRms = AudioRmsAnalyzer.dbfsToNormalized(channel.leftRmsDbfs)
    val rightNormRms = AudioRmsAnalyzer.dbfsToNormalized(channel.rightRmsDbfs)

    val leftNormPeakHold = AudioRmsAnalyzer.dbfsToNormalized(channel.leftPeakHoldDbfs)
    val rightNormPeakHold = AudioRmsAnalyzer.dbfsToNormalized(channel.rightPeakHoldDbfs)

    val leftPeakHoldSegment = (leftNormPeakHold * (segmentCount - 1)).toInt()
    val rightPeakHoldSegment = (rightNormPeakHold * (segmentCount - 1)).toInt()

    for (k in 0 until segmentCount) {
        // k=0 is bottom (-60 dBFS), k=segmentCount-1 is top (+3 dBFS)
        val segNorm = (k + 0.5f) / segmentCount
        val segDbfs = AudioRmsAnalyzer.MIN_DBFS + segNorm * (AudioRmsAnalyzer.MAX_DBFS - AudioRmsAnalyzer.MIN_DBFS)

        // Color coding: Green below -6 dB, Amber from -6 dB to 0 dB, Red above 0 dB / clip
        val segBaseColor = when {
            segDbfs > AudioRmsAnalyzer.CLIP_THRESHOLD_DBFS -> SyncRed
            segDbfs >= AudioRmsAnalyzer.GREEN_AMBER_THRESHOLD_DBFS -> SyncAmber
            else -> SyncGreen
        }

        val segTopY = bottomPx - ((k + 1) * segmentHeightPx + k * segmentGapPx)

        // --- Left Bar ---
        val isLeftLit = (k.toFloat() / segmentCount) <= leftNormRms
        val isLeftPeakHold = (k == leftPeakHoldSegment && channel.leftPeakHoldDbfs > AudioRmsAnalyzer.MIN_DBFS)
        val leftColor = when {
            isLeftPeakHold -> segBaseColor
            isLeftLit -> segBaseColor
            else -> segBaseColor.copy(alpha = 0.12f) // Unlit LED lens
        }

        drawRoundRect(
            color = leftColor,
            topLeft = Offset(leftBarX, segTopY),
            size = Size(barWidthPx, segmentHeightPx),
            cornerRadius = cornerRadius
        )

        // --- Right Bar ---
        val isRightLit = (k.toFloat() / segmentCount) <= rightNormRms
        val isRightPeakHold = (k == rightPeakHoldSegment && channel.rightPeakHoldDbfs > AudioRmsAnalyzer.MIN_DBFS)
        val rightColor = when {
            isRightPeakHold -> segBaseColor
            isRightLit -> segBaseColor
            else -> segBaseColor.copy(alpha = 0.12f)
        }

        drawRoundRect(
            color = rightColor,
            topLeft = Offset(rightBarX, segTopY),
            size = Size(barWidthPx, segmentHeightPx),
            cornerRadius = cornerRadius
        )
    }
}

/**
 * Draws a solid static RMS bar with numeric dB readout for accessibility & reduced motion.
 */
private fun DrawScope.drawReducedMotionChannel(
    channel: ChannelLevels,
    leftPx: Float,
    widthPx: Float,
    topPx: Float,
    bottomPx: Float,
    meterHeightPx: Float
) {
    val barGapPx = 4.dp.toPx()
    val barWidthPx = maxOf(6f, (widthPx - barGapPx) / 2f)

    val leftBarX = leftPx
    val rightBarX = leftPx + barWidthPx + barGapPx
    val cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())

    // Background recessed track for Left & Right
    drawRoundRect(
        color = ChassisBase,
        topLeft = Offset(leftBarX, topPx),
        size = Size(barWidthPx, meterHeightPx),
        cornerRadius = cornerRadius
    )
    drawRoundRect(
        color = ChassisBase,
        topLeft = Offset(rightBarX, topPx),
        size = Size(barWidthPx, meterHeightPx),
        cornerRadius = cornerRadius
    )

    // Solid RMS Bar Left
    val leftNormRms = AudioRmsAnalyzer.dbfsToNormalized(channel.leftRmsDbfs)
    val leftFillHeight = leftNormRms * meterHeightPx
    val leftFillTop = bottomPx - leftFillHeight

    val leftColor = when {
        channel.leftRmsDbfs > AudioRmsAnalyzer.CLIP_THRESHOLD_DBFS -> SyncRed
        channel.leftRmsDbfs >= AudioRmsAnalyzer.GREEN_AMBER_THRESHOLD_DBFS -> SyncAmber
        else -> SyncGreen
    }

    if (leftFillHeight > 0f) {
        drawRoundRect(
            color = leftColor,
            topLeft = Offset(leftBarX, leftFillTop),
            size = Size(barWidthPx, leftFillHeight),
            cornerRadius = cornerRadius
        )
    }

    // Solid RMS Bar Right
    val rightNormRms = AudioRmsAnalyzer.dbfsToNormalized(channel.rightRmsDbfs)
    val rightFillHeight = rightNormRms * meterHeightPx
    val rightFillTop = bottomPx - rightFillHeight

    val rightColor = when {
        channel.rightRmsDbfs > AudioRmsAnalyzer.CLIP_THRESHOLD_DBFS -> SyncRed
        channel.rightRmsDbfs >= AudioRmsAnalyzer.GREEN_AMBER_THRESHOLD_DBFS -> SyncAmber
        else -> SyncGreen
    }

    if (rightFillHeight > 0f) {
        drawRoundRect(
            color = rightColor,
            topLeft = Offset(rightBarX, rightFillTop),
            size = Size(barWidthPx, rightFillHeight),
            cornerRadius = cornerRadius
        )
    }
}

// =============================================================================
// Previews
// =============================================================================

@Preview(name = "Multi-Channel VU Meter (4 Devices)", showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
private fun MultiChannelVuMeterActivePreview() {
    RoomBeatTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            MultiChannelVuMeter(
                channels = listOf(
                    ChannelLevels(channelId = "host", name = "Host (Pixel 8)", leftRmsDbfs = -4.2f, rightRmsDbfs = -3.8f, leftPeakHoldDbfs = -1.2f, rightPeakHoldDbfs = -0.8f),
                    ChannelLevels(channelId = "peer1", name = "Galaxy S24", leftRmsDbfs = -9.5f, rightRmsDbfs = -8.1f, leftPeakHoldDbfs = -4.0f, rightPeakHoldDbfs = -3.5f),
                    ChannelLevels(channelId = "peer2", name = "OnePlus 12", leftRmsDbfs = -18.0f, rightRmsDbfs = -16.5f, leftPeakHoldDbfs = -12.0f, rightPeakHoldDbfs = -10.5f),
                    ChannelLevels(channelId = "peer3", name = "Pixel 7a", leftRmsDbfs = 1.2f, rightRmsDbfs = 0.8f, leftPeakHoldDbfs = 2.0f, rightPeakHoldDbfs = 1.5f, isClipping = true)
                ),
                isReducedMotion = false
            )
        }
    }
}

@Preview(name = "Reduced Motion VU Meter (Static dB Readouts)", showBackground = true, backgroundColor = 0xFF0B0C0E)
@Composable
private fun MultiChannelVuMeterReducedMotionPreview() {
    RoomBeatTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            MultiChannelVuMeter(
                channels = listOf(
                    ChannelLevels(channelId = "host", name = "Host", leftRmsDbfs = -3.2f, rightRmsDbfs = -3.2f),
                    ChannelLevels(channelId = "peer1", name = "Pixel 8", leftRmsDbfs = -12.4f, rightRmsDbfs = -11.8f)
                ),
                isReducedMotion = true
            )
        }
    }
}
