package com.roombeat.app.session

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Encapsulated session coordinates embedded within the QR code payload.
 *
 * Conforms to Roadmap §8 / Sub-phase v0.2.3:
 * `{ "code": "...", "host": "192.168.1.5", "port": 8080, "session": "..." }`
 */
@Serializable
data class QrSessionPayload(
    @SerialName("code") val code: String,
    @SerialName("host") val host: String,
    @SerialName("port") val port: Int,
    @SerialName("session") val session: String
)

/**
 * QR Code encoder and payload serializer for RoomBeat local network sessions.
 *
 * Provides:
 * - High-contrast black-and-white [Bitmap] generation for Compose Image rendering.
 * - Pure 2D boolean array [Array<BooleanArray>] generation for JVM unit testing and Canvas rendering.
 * - Fail-safe JSON payload serialization and deserialization.
 */
object QrMatrixGenerator {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /**
     * Serializes a [QrSessionPayload] into a compact JSON string.
     */
    fun encodeSessionPayload(payload: QrSessionPayload): String {
        return json.encodeToString(payload)
    }

    /**
     * Safely deserializes a JSON string into a [QrSessionPayload].
     * Returns null if the JSON is malformed, missing fields, or invalid.
     */
    fun parseSessionPayload(rawJson: String?): QrSessionPayload? {
        if (rawJson.isNullOrBlank()) return null
        return try {
            val payload = json.decodeFromString<QrSessionPayload>(rawJson)
            // Validate essential fields
            if (PinGenerator.isValidPin(payload.code) &&
                payload.host.isNotBlank() &&
                payload.port in 1..65535 &&
                payload.session.isNotBlank()
            ) {
                payload
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Encodes raw text into a ZXing [BitMatrix].
     */
    fun encodeToBitMatrix(
        content: String,
        width: Int = 512,
        height: Int = 512,
        margin: Int = 1
    ): BitMatrix {
        require(content.isNotBlank()) { "QR content must not be blank" }
        require(width > 0 && height > 0) { "QR dimensions must be positive ($width x $height)" }

        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to margin
        )

        val writer = QRCodeWriter()
        return writer.encode(content, BarcodeFormat.QR_CODE, width, height, hints)
    }

    /**
     * Generates a 2D boolean matrix representation of the QR code (true = black module, false = white background).
     * Fully compatible with host JVM unit tests without Android SDK stubs.
     */
    fun generateMatrix(
        payload: QrSessionPayload,
        size: Int = 512
    ): Array<BooleanArray> {
        return generateMatrix(encodeSessionPayload(payload), size)
    }

    /**
     * Generates a 2D boolean matrix for arbitrary string content.
     */
    fun generateMatrix(
        content: String,
        size: Int = 512
    ): Array<BooleanArray> {
        val bitMatrix = encodeToBitMatrix(content, size, size)
        val width = bitMatrix.width
        val height = bitMatrix.height
        return Array(height) { y ->
            BooleanArray(width) { x ->
                bitMatrix.get(x, y)
            }
        }
    }

    /**
     * Generates an Android [Bitmap] representing the QR code.
     *
     * @param payload Session payload to encode.
     * @param size Width and height in pixels.
     * @param onColor Color for dark QR modules (default black: 0xFF000000).
     * @param offColor Color for light QR background (default white: 0xFFFFFFFF).
     */
    fun generateBitmap(
        payload: QrSessionPayload,
        size: Int = 512,
        onColor: Int = 0xFF000000.toInt(),
        offColor: Int = 0xFFFFFFFF.toInt()
    ): Bitmap {
        return generateBitmap(encodeSessionPayload(payload), size, onColor, offColor)
    }

    /**
     * Generates an Android [Bitmap] representing arbitrary string content.
     */
    fun generateBitmap(
        content: String,
        size: Int = 512,
        onColor: Int = 0xFF000000.toInt(),
        offColor: Int = 0xFFFFFFFF.toInt()
    ): Bitmap {
        val bitMatrix = encodeToBitMatrix(content, size, size)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val pixels = IntArray(width * height)

        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                pixels[rowOffset + x] = if (bitMatrix.get(x, y)) onColor else offColor
            }
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
