package com.roombeat.app.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.roombeat.app.session.PinGenerator
import com.roombeat.app.ui.theme.BorderActive
import com.roombeat.app.ui.theme.BorderMilled
import com.roombeat.app.ui.theme.RoomBeatTheme
import com.roombeat.app.ui.theme.SignalOrange
import com.roombeat.app.ui.theme.SurfaceRecessed
import com.roombeat.app.ui.theme.SyncRed
import com.roombeat.app.ui.theme.TextBone
import com.roombeat.app.ui.theme.TextDim

private val MechanicalShakeEasing = CubicBezierEasing(0.36f, 0.07f, 0.19f, 0.97f)

/**
 * State holder managing character input, deletion, full code submission, and error shake triggers for [PinEntryKeypad].
 */
class PinEntryState(
    initialPin: String = "",
    val maxDigits: Int = PinGenerator.PIN_LENGTH,
    var onPinComplete: ((String) -> Unit)? = null
) {
    var pin by mutableStateOf(initialPin.take(maxDigits))
        internal set

    var isError by mutableStateOf(false)
        internal set

    /**
     * Appends a digit if length is strictly less than [maxDigits] and [digit] is an ASCII decimal digit ('0'..'9').
     * Automatically triggers [onPinComplete] when the PIN reaches [maxDigits].
     */
    fun appendDigit(digit: Char): Boolean {
        if (!digit.isDigit()) return false
        if (pin.length < maxDigits) {
            val nextPin = pin + digit
            pin = nextPin
            if (nextPin.length == maxDigits) {
                onPinComplete?.invoke(nextPin)
            }
            return true
        }
        return false
    }

    /**
     * Appends a digit string.
     */
    fun append(key: String): Boolean {
        return if (key.length == 1) appendDigit(key[0]) else false
    }

    /**
     * Removes the last entered digit.
     */
    fun backspace(): Boolean {
        if (pin.isNotEmpty()) {
            pin = pin.dropLast(1)
            return true
        }
        return false
    }

    /**
     * Clears all entered digits.
     */
    fun clear(): Boolean {
        if (pin.isNotEmpty()) {
            pin = ""
            return true
        }
        return false
    }

    /**
     * Triggers the mechanical error state (shake and red border flash).
     */
    fun triggerError(clearPinOnError: Boolean = true) {
        isError = true
        if (clearPinOnError) {
            pin = ""
        }
    }

    /**
     * Resets the error flag after animation finishes.
     */
    fun resetError() {
        isError = false
    }
}

/**
 * Recessed 6-digit manual PIN entry component adhering to the Tactile Acoustic Industrial design system.
 *
 * Features:
 * - 6 recessed input boxes displaying digits in `code-xl` JetBrains Mono font (`SurfaceRecessed` #07080A with `BorderMilled` #262A35).
 * - Tactile numeric keypad (0–9, backspace, clear) using [TactileKeycapButton] with `CLOCK_TICK` haptics.
 * - 150ms mechanical error shake animation (±5px 3-cycle micro-shake) and red border flash (`SyncRed` #FF334B) on invalid PIN code submission.
 */
@Composable
fun PinEntryKeypad(
    pin: String,
    onPinChange: (String) -> Unit,
    onPinComplete: (String) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    enabled: Boolean = true,
    onErrorDismissed: (() -> Unit)? = null
) {
    val view = LocalView.current
    val shakeOffset = remember { Animatable(0f) }
    var hasErrorFlash by remember { mutableStateOf(false) }

    // 150ms mechanical error shake animation and red border flash
    LaunchedEffect(isError) {
        if (isError) {
            hasErrorFlash = true
            try {
                view.performHapticFeedback(HapticFeedbackConstants.REJECT)
            } catch (_: Throwable) {
                // Test/mock safe fallback
            }

            shakeOffset.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = 150
                    0f at 0
                    -5f at 25 using MechanicalShakeEasing
                    5f at 50 using MechanicalShakeEasing
                    -5f at 75 using MechanicalShakeEasing
                    5f at 100 using MechanicalShakeEasing
                    -2f at 125 using MechanicalShakeEasing
                    0f at 150
                }
            )

            hasErrorFlash = false
            onErrorDismissed?.invoke()
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Recessed 6-Digit PIN Display Cavity
        Row(
            modifier = Modifier
                .graphicsLayer { translationX = shakeOffset.value }
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (index in 0 until PinGenerator.PIN_LENGTH) {
                val digit = pin.getOrNull(index)
                val isFocused = index == pin.length && !isError

                val boxBorderColor by animateColorAsState(
                    targetValue = when {
                        isError || hasErrorFlash -> SyncRed
                        isFocused -> SignalOrange
                        digit != null -> BorderActive
                        else -> BorderMilled
                    },
                    animationSpec = tween(durationMillis = 120),
                    label = "PinBoxBorderColor_$index"
                )

                Surface(
                    modifier = Modifier.size(width = 46.dp, height = 58.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = SurfaceRecessed,
                    border = BorderStroke(
                        width = if (isError || hasErrorFlash || isFocused) 1.5.dp else 1.dp,
                        color = boxBorderColor
                    )
                ) {
                    Box(
                        modifier = Modifier.size(width = 46.dp, height = 58.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (digit != null) {
                            Text(
                                text = digit.toString(),
                                style = RoomBeatTheme.typography.codeXl.copy(
                                    fontSize = 28.sp,
                                    lineHeight = 28.sp,
                                    letterSpacing = 0.sp
                                ),
                                color = TextBone,
                                textAlign = TextAlign.Center
                            )
                        } else if (isFocused) {
                            Text(
                                text = "_",
                                style = RoomBeatTheme.typography.codeXl.copy(
                                    fontSize = 24.sp,
                                    lineHeight = 24.sp,
                                    letterSpacing = 0.sp
                                ),
                                color = SignalOrange,
                                textAlign = TextAlign.Center
                            )
                        } else {
                            Text(
                                text = "·",
                                style = RoomBeatTheme.typography.codeXl.copy(
                                    fontSize = 20.sp,
                                    lineHeight = 20.sp,
                                    letterSpacing = 0.sp
                                ),
                                color = TextDim,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Tactile Numeric Keypad Grid (3x4)
        Column(
            modifier = Modifier
                .width(280.dp)
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val keyRows = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("CLR", "0", "⌫")
            )

            for (row in keyRows) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (key in row) {
                        val isClear = key == "CLR"
                        val isBackspace = key == "⌫"

                        val variant = when {
                            isClear -> TactileButtonVariant.DESTRUCTIVE
                            else -> TactileButtonVariant.SURFACE
                        }

                        TactileKeycapButton(
                            onClick = {
                                if (!enabled) return@TactileKeycapButton
                                when {
                                    isClear -> {
                                        if (pin.isNotEmpty()) {
                                            onPinChange("")
                                        }
                                    }
                                    isBackspace -> {
                                        if (pin.isNotEmpty()) {
                                            onPinChange(pin.dropLast(1))
                                        }
                                    }
                                    else -> {
                                        if (pin.length < PinGenerator.PIN_LENGTH) {
                                            val nextPin = pin + key
                                            onPinChange(nextPin)
                                            if (nextPin.length == PinGenerator.PIN_LENGTH) {
                                                onPinComplete(nextPin)
                                            }
                                        }
                                    }
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp),
                            variant = variant,
                            enabled = enabled && when {
                                isClear || isBackspace -> pin.isNotEmpty()
                                else -> pin.length < PinGenerator.PIN_LENGTH
                            },
                            cornerRadius = 6.dp
                        ) {
                            Text(
                                text = key,
                                style = if (isClear || isBackspace) {
                                    RoomBeatTheme.typography.labelSm.copy(fontWeight = FontWeight.Bold)
                                } else {
                                    RoomBeatTheme.typography.headingMd.copy(
                                        fontFamily = RoomBeatTheme.typography.codeXl.fontFamily,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Stateful overload of [PinEntryKeypad] utilizing [PinEntryState].
 */
@Composable
fun PinEntryKeypad(
    state: PinEntryState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    PinEntryKeypad(
        pin = state.pin,
        onPinChange = { newPin ->
            state.pin = newPin
        },
        onPinComplete = { completedPin ->
            state.onPinComplete?.invoke(completedPin)
        },
        modifier = modifier,
        isError = state.isError,
        enabled = enabled,
        onErrorDismissed = {
            state.resetError()
        }
    )
}

/**
 * Stateful overload of [PinEntryKeypad] managing internal PIN input state.
 */
@Composable
fun PinEntryKeypad(
    onPinComplete: (String) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    enabled: Boolean = true,
    onPinChange: ((String) -> Unit)? = null,
    onErrorDismissed: (() -> Unit)? = null
) {
    var internalPin by remember { mutableStateOf("") }

    LaunchedEffect(isError) {
        if (isError) {
            internalPin = ""
        }
    }

    PinEntryKeypad(
        pin = internalPin,
        onPinChange = { newPin ->
            internalPin = newPin
            onPinChange?.invoke(newPin)
        },
        onPinComplete = onPinComplete,
        modifier = modifier,
        isError = isError,
        enabled = enabled,
        onErrorDismissed = onErrorDismissed
    )
}
