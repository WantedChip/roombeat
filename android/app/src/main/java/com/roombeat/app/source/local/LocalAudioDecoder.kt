package com.roombeat.app.source.local

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.roombeat.app.audio.codec.OpusCodec
import com.roombeat.app.audio.codec.OpusConstants
import com.roombeat.app.audio.codec.OpusEncoder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Representation of track audio format decoupled from [android.media.MediaFormat].
 */
data class AudioTrackFormat(
    val mimeType: String,
    val sampleRate: Int = OpusConstants.SAMPLE_RATE,
    val channelCount: Int = OpusConstants.CHANNELS,
    val durationUs: Long = 0L,
    val bitrate: Int = 0
)

/**
 * Buffer metadata container decoupled from [android.media.MediaCodec.BufferInfo].
 */
data class CodecBufferInfo(
    var offset: Int = 0,
    var size: Int = 0,
    var presentationTimeUs: Long = 0L,
    var flags: Int = 0
) {
    val isEndOfStream: Boolean
        get() = (flags and BUFFER_FLAG_END_OF_STREAM) != 0

    companion object {
        const val BUFFER_FLAG_KEY_FRAME = 1
        const val BUFFER_FLAG_CODEC_CONFIG = 2
        const val BUFFER_FLAG_END_OF_STREAM = 4
    }
}

/**
 * Common status codes returned by [MediaCodecFacade.dequeueOutputBuffer].
 */
object MediaCodecStatus {
    const val INFO_TRY_AGAIN_LATER = -1
    const val INFO_OUTPUT_FORMAT_CHANGED = -2
    const val INFO_OUTPUT_BUFFERS_CHANGED = -3
}

/**
 * Abstraction for [android.media.MediaExtractor] for JVM unit testing.
 */
interface MediaExtractorFacade : AutoCloseable {
    fun setDataSource(context: Context, uri: Uri)
    fun getTrackCount(): Int
    fun getTrackFormat(index: Int): AudioTrackFormat
    fun selectTrack(index: Int)
    fun readSampleData(byteBuffer: ByteBuffer, offset: Int): Int
    fun getSampleTime(): Long
    fun getSampleFlags(): Int
    fun advance(): Boolean
    fun seekTo(timeUs: Long, mode: Int)
    override fun close()

    companion object {
        const val SEEK_PREVIOUS_SYNC = 0
        const val SEEK_NEXT_SYNC = 1
        const val SEEK_CLOSEST_SYNC = 2
    }
}

/**
 * Default production [MediaExtractorFacade] delegating to [android.media.MediaExtractor].
 */
class AndroidMediaExtractorFacade : MediaExtractorFacade {
    private val extractor = MediaExtractor()

    override fun setDataSource(context: Context, uri: Uri) {
        extractor.setDataSource(context, uri, null)
    }

    override fun getTrackCount(): Int = extractor.trackCount

    override fun getTrackFormat(index: Int): AudioTrackFormat {
        val format = extractor.getTrackFormat(index)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mpeg"
        val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else {
            OpusConstants.SAMPLE_RATE
        }
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else {
            OpusConstants.CHANNELS
        }
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
            format.getLong(MediaFormat.KEY_DURATION)
        } else {
            0L
        }
        val bitrate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
            format.getInteger(MediaFormat.KEY_BIT_RATE)
        } else {
            0
        }

        return AudioTrackFormat(
            mimeType = mime,
            sampleRate = sampleRate,
            channelCount = channels,
            durationUs = durationUs,
            bitrate = bitrate
        )
    }

    override fun selectTrack(index: Int) {
        extractor.selectTrack(index)
    }

    override fun readSampleData(byteBuffer: ByteBuffer, offset: Int): Int {
        return extractor.readSampleData(byteBuffer, offset)
    }

    override fun getSampleTime(): Long = extractor.sampleTime

    override fun getSampleFlags(): Int = extractor.sampleFlags

    override fun advance(): Boolean = extractor.advance()

    override fun seekTo(timeUs: Long, mode: Int) {
        extractor.seekTo(timeUs, mode)
    }

    override fun close() {
        try {
            extractor.release()
        } catch (_: Throwable) {}
    }
}

/**
 * Abstraction for [android.media.MediaCodec] for JVM unit testing.
 */
interface MediaCodecFacade : AutoCloseable {
    fun configure(format: AudioTrackFormat)
    fun start()
    fun dequeueInputBuffer(timeoutUs: Long): Int
    fun getInputBuffer(index: Int): ByteBuffer?
    fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int)
    fun dequeueOutputBuffer(info: CodecBufferInfo, timeoutUs: Long): Int
    fun getOutputBuffer(index: Int): ByteBuffer?
    fun getOutputFormat(): AudioTrackFormat
    fun releaseOutputBuffer(index: Int, render: Boolean = false)
    fun flush()
    fun stop()
    override fun close()
}

/**
 * Default production [MediaCodecFacade] delegating to [android.media.MediaCodec].
 */
class AndroidMediaCodecFacade : MediaCodecFacade {
    private var codec: MediaCodec? = null
    private var cachedOutputFormat: AudioTrackFormat? = null

    override fun configure(format: AudioTrackFormat) {
        val mediaFormat = MediaFormat.createAudioFormat(format.mimeType, format.sampleRate, format.channelCount)
        if (format.bitrate > 0) {
            mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, format.bitrate)
        }
        val createdCodec = MediaCodec.createDecoderByType(format.mimeType)
        createdCodec.configure(mediaFormat, null, null, 0)
        codec = createdCodec
        cachedOutputFormat = format
    }

    override fun start() {
        codec?.start()
    }

    override fun dequeueInputBuffer(timeoutUs: Long): Int =
        codec?.dequeueInputBuffer(timeoutUs) ?: MediaCodecStatus.INFO_TRY_AGAIN_LATER

    override fun getInputBuffer(index: Int): ByteBuffer? =
        codec?.getInputBuffer(index)

    override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
        codec?.queueInputBuffer(index, offset, size, presentationTimeUs, flags)
    }

    override fun dequeueOutputBuffer(info: CodecBufferInfo, timeoutUs: Long): Int {
        val c = codec ?: return MediaCodecStatus.INFO_TRY_AGAIN_LATER
        val androidInfo = MediaCodec.BufferInfo()
        val index = c.dequeueOutputBuffer(androidInfo, timeoutUs)
        if (index >= 0) {
            info.offset = androidInfo.offset
            info.size = androidInfo.size
            info.presentationTimeUs = androidInfo.presentationTimeUs
            info.flags = androidInfo.flags
        }
        return index
    }

    override fun getOutputBuffer(index: Int): ByteBuffer? =
        codec?.getOutputBuffer(index)

    override fun getOutputFormat(): AudioTrackFormat {
        val mf = codec?.outputFormat
        if (mf != null) {
            val mime = if (mf.containsKey(MediaFormat.KEY_MIME)) mf.getString(MediaFormat.KEY_MIME) ?: "audio/raw" else "audio/raw"
            val sampleRate = if (mf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) mf.getInteger(MediaFormat.KEY_SAMPLE_RATE) else OpusConstants.SAMPLE_RATE
            val channels = if (mf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) mf.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else OpusConstants.CHANNELS
            val fmt = AudioTrackFormat(mimeType = mime, sampleRate = sampleRate, channelCount = channels)
            cachedOutputFormat = fmt
            return fmt
        }
        return cachedOutputFormat ?: AudioTrackFormat(mimeType = "audio/raw", sampleRate = OpusConstants.SAMPLE_RATE, channelCount = OpusConstants.CHANNELS)
    }

    override fun releaseOutputBuffer(index: Int, render: Boolean) {
        codec?.releaseOutputBuffer(index, render)
    }

    override fun flush() {
        try {
            codec?.flush()
        } catch (_: Throwable) {}
    }

    override fun stop() {
        try {
            codec?.stop()
        } catch (_: Throwable) {}
    }

    override fun close() {
        try {
            codec?.stop()
        } catch (_: Throwable) {}
        try {
            codec?.release()
        } catch (_: Throwable) {}
        codec = null
    }
}

/**
 * Output model for decoded, resampled, and Opus-compressed 20ms audio frames.
 */
data class DecodedOpusFrame(
    val seq: Long,
    val opusData: ByteArray,
    val sampleCountPerChannel: Int = OpusConstants.FRAME_SIZE_SAMPLES,
    val presentationTimeUs: Long,
    val durationUs: Long = 20_000L
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecodedOpusFrame) return false
        return seq == other.seq &&
                sampleCountPerChannel == other.sampleCountPerChannel &&
                presentationTimeUs == other.presentationTimeUs &&
                durationUs == other.durationUs &&
                opusData.contentEquals(other.opusData)
    }

    override fun hashCode(): Int {
        var result = seq.hashCode()
        result = 31 * result + opusData.contentHashCode()
        result = 31 * result + sampleCountPerChannel
        result = 31 * result + presentationTimeUs.hashCode()
        result = 31 * result + durationUs.hashCode()
        return result
    }
}

/**
 * Operational states for [LocalAudioDecoder].
 */
enum class DecoderState {
    IDLE,
    PREPARING,
    PLAYING,
    PAUSED,
    STOPPED,
    COMPLETED,
    ERROR
}

/**
 * Asynchronous audio file decoder for RoomBeat.
 *
 * Responsibilities:
 * 1. Reads encoded audio from SAF URIs via [MediaExtractorFacade].
 * 2. Decodes arbitrary audio formats (.mp3, .flac, .wav, .m4a) to PCM via [MediaCodecFacade].
 * 3. Resamples decoded PCM audio to standard 48,000 Hz stereo via [AudioResamplerPipe].
 * 4. Slices the audio stream into exact 20ms frames (1920 interleaved samples) via [AudioFrameChunker].
 * 5. Compresses each 20ms frame using [OpusEncoder] and emits [DecodedOpusFrame]s.
 * 6. Supports playback lifecycle controls: start, pause, resume, seekTo, stop.
 * 7. Memory safe: maintains a steady heap memory footprint well under 30MB via streaming.
 */
class LocalAudioDecoder(
    private val context: Context,
    private val uri: Uri,
    private val extractorFactory: () -> MediaExtractorFacade = { AndroidMediaExtractorFacade() },
    private val codecFactory: () -> MediaCodecFacade = { AndroidMediaCodecFacade() },
    private val opusEncoderFactory: () -> OpusEncoder = { OpusCodec.createEncoder() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    var paceToRealtime: Boolean = false
) : AutoCloseable {

    companion object {
        const val TAG = "LocalAudioDecoder"
        const val FRAME_DURATION_US = 20_000L // 20ms in microseconds
    }

    private val _state = MutableStateFlow(DecoderState.IDLE)
    val state: StateFlow<DecoderState> = _state.asStateFlow()

    private val _positionUs = MutableStateFlow(0L)
    val positionUs: StateFlow<Long> = _positionUs.asStateFlow()

    private val _durationUs = MutableStateFlow(0L)
    val durationUs: StateFlow<Long> = _durationUs.asStateFlow()

    private val _frames = MutableSharedFlow<DecodedOpusFrame>(extraBufferCapacity = 64)
    val frames: SharedFlow<DecodedOpusFrame> = _frames.asSharedFlow()

    var onFrameListener: ((DecodedOpusFrame) -> Unit)? = null
    var onCompletionListener: (() -> Unit)? = null
    var onErrorListener: ((Throwable) -> Unit)? = null

    private var extractor: MediaExtractorFacade? = null
    private var codec: MediaCodecFacade? = null
    private var encoder: OpusEncoder? = null
    private val resamplerPipe = AudioResamplerPipe()
    private val chunker = AudioFrameChunker()

    private var decodeScope: CoroutineScope? = null
    private var decodeJob: Job? = null

    @Volatile
    private var pendingSeekUs: Long? = null

    @Volatile
    private var currentFrameSeq = 0L

    @Volatile
    private var currentPresentationTimeUs = 0L

    /**
     * Initializes extractor and codec, loading audio track metadata.
     */
    fun prepare(): Boolean {
        if (_state.value != DecoderState.IDLE) return true
        _state.value = DecoderState.PREPARING

        try {
            val ext = extractorFactory()
            ext.setDataSource(context, uri)

            var audioTrackIndex = -1
            var trackFormat: AudioTrackFormat? = null

            val trackCount = ext.getTrackCount()
            for (i in 0 until trackCount) {
                val fmt = ext.getTrackFormat(i)
                if (fmt.mimeType.startsWith("audio/")) {
                    audioTrackIndex = i
                    trackFormat = fmt
                    break
                }
            }

            if (audioTrackIndex == -1 || trackFormat == null) {
                throw IllegalStateException("No supported audio track found in $uri")
            }

            ext.selectTrack(audioTrackIndex)
            this.extractor = ext

            _durationUs.value = trackFormat.durationUs
            resamplerPipe.configure(trackFormat.sampleRate, trackFormat.channelCount)

            val cod = codecFactory()
            cod.configure(trackFormat)
            cod.start()
            this.codec = cod

            this.encoder = opusEncoderFactory()
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to prepare LocalAudioDecoder for $uri", e)
            _state.value = DecoderState.ERROR
            onErrorListener?.invoke(e)
            close()
            return false
        }
    }

    /**
     * Starts or resumes asynchronous audio decoding.
     */
    fun start() {
        when (_state.value) {
            DecoderState.IDLE -> {
                if (prepare()) {
                    startDecodeLoop()
                }
            }
            DecoderState.PAUSED -> {
                _state.value = DecoderState.PLAYING
            }
            DecoderState.PREPARING -> {
                startDecodeLoop()
            }
            DecoderState.PLAYING -> {
                // Already playing
            }
            DecoderState.STOPPED, DecoderState.COMPLETED, DecoderState.ERROR -> {
                // Reset and re-prepare
                close()
                _state.value = DecoderState.IDLE
                currentFrameSeq = 0L
                currentPresentationTimeUs = 0L
                if (prepare()) {
                    startDecodeLoop()
                }
            }
        }
    }

    /**
     * Pauses decoding. The background decode loop suspends.
     */
    fun pause() {
        if (_state.value == DecoderState.PLAYING) {
            _state.value = DecoderState.PAUSED
        }
    }

    /**
     * Resumes decoding from paused state.
     */
    fun resume() {
        start()
    }

    /**
     * Requests seeking to the specified timestamp in microseconds.
     */
    fun seekTo(positionUs: Long) {
        val target = positionUs.coerceAtLeast(0L)
        pendingSeekUs = target
        _positionUs.value = target
        currentPresentationTimeUs = target
        currentFrameSeq = target / FRAME_DURATION_US
    }

    /**
     * Stops decoding and releases resources.
     */
    fun stop() {
        _state.value = DecoderState.STOPPED
        decodeJob?.cancel()
        decodeJob = null
        close()
    }

    private fun startDecodeLoop() {
        _state.value = DecoderState.PLAYING
        val scope = CoroutineScope(ioDispatcher)
        decodeScope = scope

        decodeJob = scope.launch {
            runDecodeLoop()
        }
    }

    private suspend fun runDecodeLoop() {
        val ext = extractor ?: return
        val cod = codec ?: return
        val enc = encoder ?: return

        val bufferInfo = CodecBufferInfo()
        val opusBuffer = ByteArray(OpusConstants.MAX_PACKET_BYTES)
        var inputEos = false
        var outputEos = false

        try {
            while (decodeScope?.isActive == true &&
                _state.value != DecoderState.STOPPED &&
                _state.value != DecoderState.ERROR &&
                !outputEos
            ) {
                // 1. Handle pending seek request
                val seekTarget = pendingSeekUs
                if (seekTarget != null) {
                    pendingSeekUs = null
                    ext.seekTo(seekTarget, MediaExtractorFacade.SEEK_CLOSEST_SYNC)
                    val sampleTime = ext.getSampleTime()
                    cod.flush()
                    resamplerPipe.reset()
                    chunker.reset()
                    inputEos = false
                    outputEos = false
                    val actualPos = if (sampleTime >= 0) sampleTime else seekTarget
                    _positionUs.value = actualPos
                    currentPresentationTimeUs = actualPos
                    currentFrameSeq = actualPos / FRAME_DURATION_US
                }

                // 2. Handle pause state
                while (_state.value == DecoderState.PAUSED && decodeScope?.isActive == true) {
                    delay(20L)
                    if (pendingSeekUs != null) break
                }

                if (_state.value != DecoderState.PLAYING) {
                    yield()
                    continue
                }

                // 3. Feed input buffers into MediaCodec
                if (!inputEos) {
                    val inIndex = cod.dequeueInputBuffer(1000L)
                    if (inIndex >= 0) {
                        val inBuffer = cod.getInputBuffer(inIndex)
                        if (inBuffer != null) {
                            inBuffer.clear()
                            val bytesRead = ext.readSampleData(inBuffer, 0)
                            if (bytesRead < 0) {
                                cod.queueInputBuffer(
                                    index = inIndex,
                                    offset = 0,
                                    size = 0,
                                    presentationTimeUs = 0L,
                                    flags = CodecBufferInfo.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputEos = true
                            } else {
                                val sampleTimeUs = ext.getSampleTime()
                                val sampleFlags = ext.getSampleFlags()
                                cod.queueInputBuffer(
                                    index = inIndex,
                                    offset = 0,
                                    size = bytesRead,
                                    presentationTimeUs = sampleTimeUs,
                                    flags = sampleFlags
                                )
                                ext.advance()
                            }
                        }
                    }
                }

                // 4. Retrieve decoded output buffers from MediaCodec
                val outIndex = cod.dequeueOutputBuffer(bufferInfo, 1000L)
                when {
                    outIndex == MediaCodecStatus.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFmt = cod.getOutputFormat()
                        resamplerPipe.configure(newFmt.sampleRate, newFmt.channelCount)
                    }
                    outIndex == MediaCodecStatus.INFO_TRY_AGAIN_LATER -> {
                        yield()
                    }
                    outIndex >= 0 -> {
                        val outBuffer = cod.getOutputBuffer(outIndex)
                        if (outBuffer != null && bufferInfo.size > 0) {
                            outBuffer.position(bufferInfo.offset)
                            outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            outBuffer.order(ByteOrder.LITTLE_ENDIAN)

                            val shortCount = bufferInfo.size / 2
                            if (shortCount > 0) {
                                val pcmShorts = ShortArray(shortCount)
                                outBuffer.asShortBuffer().get(pcmShorts)

                                // Resample to 48kHz stereo
                                val resampled = resamplerPipe.resample(pcmShorts)

                                // Slice into exact 20ms frames and Opus encode
                                chunker.pushShort(resampled) { frame ->
                                    val bytes = enc.encode(frame, OpusConstants.FRAME_SIZE_SAMPLES, opusBuffer)
                                    if (bytes > 0) {
                                        val opusPacket = opusBuffer.copyOf(bytes)
                                        val frameTime = currentPresentationTimeUs
                                        currentPresentationTimeUs += FRAME_DURATION_US
                                        val opusFrame = DecodedOpusFrame(
                                            seq = currentFrameSeq++,
                                            opusData = opusPacket,
                                            sampleCountPerChannel = OpusConstants.FRAME_SIZE_SAMPLES,
                                            presentationTimeUs = frameTime
                                        )
                                        _frames.tryEmit(opusFrame)
                                        onFrameListener?.invoke(opusFrame)
                                        _positionUs.value = frameTime
                                    }
                                }
                            }
                        }

                        cod.releaseOutputBuffer(outIndex, false)

                        if (bufferInfo.isEndOfStream) {
                            outputEos = true
                            // Flush trailing chunk
                            chunker.flushShort(zeroPad = true) { finalFrame ->
                                val bytes = enc.encode(finalFrame, OpusConstants.FRAME_SIZE_SAMPLES, opusBuffer)
                                if (bytes > 0) {
                                    val opusPacket = opusBuffer.copyOf(bytes)
                                    val opusFrame = DecodedOpusFrame(
                                        seq = currentFrameSeq++,
                                        opusData = opusPacket,
                                        sampleCountPerChannel = OpusConstants.FRAME_SIZE_SAMPLES,
                                        presentationTimeUs = currentPresentationTimeUs
                                    )
                                    _frames.tryEmit(opusFrame)
                                    onFrameListener?.invoke(opusFrame)
                                }
                            }
                            _state.value = DecoderState.COMPLETED
                            onCompletionListener?.invoke()
                        }

                        if (paceToRealtime) {
                            delay(20L)
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            if (_state.value != DecoderState.STOPPED) {
                Log.e(TAG, "Error in audio decoding loop for $uri", e)
                _state.value = DecoderState.ERROR
                onErrorListener?.invoke(e)
            }
        }
    }

    override fun close() {
        try {
            codec?.close()
        } catch (_: Throwable) {}
        codec = null

        try {
            extractor?.close()
        } catch (_: Throwable) {}
        extractor = null

        try {
            encoder?.close()
        } catch (_: Throwable) {}
        encoder = null

        resamplerPipe.reset()
        chunker.reset()
    }
}
