package com.roombeat.app.ui.components

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.roombeat.app.session.QrMatrixGenerator
import com.roombeat.app.session.QrSessionPayload
import com.roombeat.app.ui.theme.BorderActive
import com.roombeat.app.ui.theme.ChassisBase
import com.roombeat.app.ui.theme.RoomBeatTheme
import com.roombeat.app.ui.theme.SurfaceRecessed
import com.roombeat.app.ui.theme.SyncGreen
import com.roombeat.app.ui.theme.TextBone
import com.roombeat.app.ui.theme.TextDim
import java.util.concurrent.Executors

/**
 * CameraX QR Viewfinder composable adhering to the Tactile Acoustic Industrial design system.
 *
 * Features:
 * - Realtime camera preview stream via AndroidX CameraX [PreviewView].
 * - Frame analysis via Google ML Kit [BarcodeScanning] to decode QR codes and parse [QrSessionPayload].
 * - Precision tactile targeting reticle: milled corner brackets (#3E4454) snapping to Phosphor Green (#00E599) on lock.
 * - Preview / mock mode for Compose Previews and headless JVM testing environments.
 */
@Composable
fun QrScannerView(
    onQrCodeScanned: (rawCode: String, payload: QrSessionPayload?) -> Unit,
    modifier: Modifier = Modifier,
    isLocked: Boolean = false,
    isPreviewMode: Boolean = false,
    scanBoxSize: Dp = 240.dp
) {
    val isInspection = LocalInspectionMode.current || isPreviewMode
    var internalLocked by remember { mutableStateOf(false) }
    val lockedState = isLocked || internalLocked

    val reticleColor by animateColorAsState(
        targetValue = if (lockedState) SyncGreen else BorderActive,
        animationSpec = tween(durationMillis = 150),
        label = "ReticleColorAnimation"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ChassisBase),
        contentAlignment = Alignment.Center
    ) {
        if (isInspection) {
            // Simulated Camera Viewfinder for Compose Previews & Unit Tests
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(SurfaceRecessed),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (lockedState) "[QR LOCK ACQUIRED]" else "[CAMERA PREVIEW MOCK — STANDBY]",
                    style = RoomBeatTheme.typography.codeXs,
                    color = if (lockedState) SyncGreen else TextDim
                )
            }
        } else {
            // Live CameraX Preview Stream
            CameraXPreview(
                onBarcodeDetected = { rawValue ->
                    if (!internalLocked) {
                        internalLocked = true
                        val payload = QrMatrixGenerator.parseSessionPayload(rawValue)
                        onQrCodeScanned(rawValue, payload)
                    }
                }
            )
        }

        // Tactile Targeting Reticle & Alignment Overlay
        TargetingReticleOverlay(
            reticleColor = reticleColor,
            isLocked = lockedState,
            boxSizeDp = scanBoxSize
        )

        // Status Readout Beneath Reticle
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (lockedState) "QR SESSION LOCKED" else "ALIGN ROOM QR WITHIN RETICLE",
                style = RoomBeatTheme.typography.labelSm,
                color = if (lockedState) SyncGreen else TextBone
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (lockedState) "CONNECTING TO HOST..." else "CAMERA SCANNER ACTIVE",
                style = RoomBeatTheme.typography.codeXs,
                color = if (lockedState) SyncGreen else TextDim
            )
        }
    }
}

/**
 * CameraX [PreviewView] container with attached ML Kit [ImageAnalysis] analyzer.
 */
@OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraXPreview(
    onBarcodeDetected: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val barcodeScanner = remember { BarcodeScanning.getClient() }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
            barcodeScanner.close()
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }

            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            processImageProxy(barcodeScanner, imageProxy, onBarcodeDetected)
                        }
                    }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } catch (_: Throwable) {
                    // Safe fallback if camera is unavailable
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

@OptIn(ExperimentalGetImage::class)
private fun processImageProxy(
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    imageProxy: ImageProxy,
    onBarcodeDetected: (String) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    if (barcode.valueType == Barcode.TYPE_TEXT ||
                        barcode.format == Barcode.FORMAT_QR_CODE
                    ) {
                        val rawValue = barcode.rawValue
                        if (!rawValue.isNullOrBlank()) {
                            onBarcodeDetected(rawValue)
                            break
                        }
                    }
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    } else {
        imageProxy.close()
    }
}

/**
 * Custom Canvas drawing milled industrial corner brackets and targeting reticle.
 */
@Composable
private fun TargetingReticleOverlay(
    reticleColor: Color,
    isLocked: Boolean,
    boxSizeDp: Dp
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val boxSizePx = boxSizeDp.toPx()
        val cornerArmPx = 28.dp.toPx()
        val strokeWidthPx = 3.dp.toPx()

        val left = (canvasWidth - boxSizePx) / 2f
        val top = (canvasHeight - boxSizePx) / 2f
        val right = left + boxSizePx
        val bottom = top + boxSizePx

        // Subtle dark scrim outside the viewfinder targeting box
        drawRect(
            color = Color(0x6607080A),
            topLeft = Offset.Zero,
            size = Size(canvasWidth, top)
        )
        drawRect(
            color = Color(0x6607080A),
            topLeft = Offset(0f, bottom),
            size = Size(canvasWidth, canvasHeight - bottom)
        )
        drawRect(
            color = Color(0x6607080A),
            topLeft = Offset(0f, top),
            size = Size(left, boxSizePx)
        )
        drawRect(
            color = Color(0x6607080A),
            topLeft = Offset(right, top),
            size = Size(canvasWidth - right, boxSizePx)
        )

        // Corner 1: Top-Left
        val topLeftPath = Path().apply {
            moveTo(left, top + cornerArmPx)
            lineTo(left, top)
            lineTo(left + cornerArmPx, top)
        }
        drawPath(
            path = topLeftPath,
            color = reticleColor,
            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Square)
        )

        // Corner 2: Top-Right
        val topRightPath = Path().apply {
            moveTo(right - cornerArmPx, top)
            lineTo(right, top)
            lineTo(right, top + cornerArmPx)
        }
        drawPath(
            path = topRightPath,
            color = reticleColor,
            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Square)
        )

        // Corner 3: Bottom-Left
        val bottomLeftPath = Path().apply {
            moveTo(left, bottom - cornerArmPx)
            lineTo(left, bottom)
            lineTo(left + cornerArmPx, bottom)
        }
        drawPath(
            path = bottomLeftPath,
            color = reticleColor,
            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Square)
        )

        // Corner 4: Bottom-Right
        val bottomRightPath = Path().apply {
            moveTo(right - cornerArmPx, bottom)
            lineTo(right, bottom)
            lineTo(right, bottom - cornerArmPx)
        }
        drawPath(
            path = bottomRightPath,
            color = reticleColor,
            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Square)
        )

        // Centered Crosshairs (Tactile precision reticle)
        val center = Offset(canvasWidth / 2f, canvasHeight / 2f)
        val crosshairLength = 8.dp.toPx()
        val crosshairColor = if (isLocked) SyncGreen.copy(alpha = 0.8f) else BorderActive.copy(alpha = 0.5f)

        drawLine(
            color = crosshairColor,
            start = Offset(center.x - crosshairLength, center.y),
            end = Offset(center.x + crosshairLength, center.y),
            strokeWidth = 1.5.dp.toPx()
        )
        drawLine(
            color = crosshairColor,
            start = Offset(center.x, center.y - crosshairLength),
            end = Offset(center.x, center.y + crosshairLength),
            strokeWidth = 1.5.dp.toPx()
        )
    }
}
