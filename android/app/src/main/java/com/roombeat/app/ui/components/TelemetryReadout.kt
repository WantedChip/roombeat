package com.roombeat.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.roombeat.app.ui.theme.RoomBeatTheme
import com.roombeat.app.ui.theme.SyncGreen
import com.roombeat.app.ui.theme.TextDim

/**
 * Size variants for [TelemetryReadout].
 */
enum class TelemetrySize {
    LARGE,  // codeXl (36sp) - Room PINs, Primary Telemetry
    MEDIUM, // codeMd (14sp) - Clock Drift, Port Numbers
    SMALL   // codeXs (11sp) - Buffer Depth, Sample Rates, Dropouts
}

/**
 * Technical telemetry readout component utilizing JetBrains Mono.
 * Used for NTP clock drift, sample rates, buffer depth, and room PINs.
 */
@Composable
fun TelemetryReadout(
    value: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    size: TelemetrySize = TelemetrySize.MEDIUM,
    telemetryColor: Color = SyncGreen,
    labelColor: Color = TextDim,
    inline: Boolean = false
) {
    val typography = RoomBeatTheme.typography
    val textStyle = when (size) {
        TelemetrySize.LARGE -> typography.codeXl
        TelemetrySize.MEDIUM -> typography.codeMd
        TelemetrySize.SMALL -> typography.codeXs
    }

    if (inline) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (label != null) {
                Text(
                    text = label,
                    style = typography.labelSm,
                    color = labelColor
                )
            }
            Text(
                text = value,
                style = textStyle,
                color = telemetryColor
            )
        }
    } else {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (label != null) {
                Text(
                    text = label,
                    style = typography.labelSm,
                    color = labelColor
                )
            }
            Text(
                text = value,
                style = textStyle,
                color = telemetryColor
            )
        }
    }
}
