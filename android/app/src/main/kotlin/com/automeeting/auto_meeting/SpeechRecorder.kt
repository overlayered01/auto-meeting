package com.automeeting.auto_meeting

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** One microphone/PCM stream feeds BOTH the WAV writer and on-device Silero VAD. */
class SpeechRecorder(
    private val context: Context,
    private val output: File,
    @Volatile var endAt: Long,
    private val onStarted: (Long) -> Unit,
    private val onFinished: (Result) -> Unit,
) {
    data class Result(val reason: String, val failed: Boolean, val durationMs: Long,
        val startedAt: Long?, val endedAt: Long, val peak: Int, val speechSeen: Boolean)

    private val stopReason = AtomicReference<String?>(null)
    @Volatile var silenceMs = 0L; private set
    @Volatile var amplitude = 0; private set
    @Volatile var speech = false; private set
    @Volatile var durationMs = 0L; private set
    private val worker = Thread({ capture() }, "calendar-audio-vad")

    fun start() = worker.start()
    fun stop(reason: String) { stopReason.compareAndSet(null, reason) }

    private fun capture() {
        var input: AudioRecord? = null
        var vad: VadSilero? = null
        var file: RandomAccessFile? = null
        var pcmBytes = 0L
        var started: Long? = null
        var peak = 0
        var reason = "recorder_error"
        var failed = false
        val policy = SpeechSilencePolicy()
        try {
            check(output.parentFile!!.usableSpace >= 20L * 1024 * 1024) { "storage_low" }
            vad = VadSilero(context, SampleRate.SAMPLE_RATE_16K, FrameSize.FRAME_SIZE_512,
                Mode.NORMAL, speechDurationMs = 64, silenceDurationMs = 0)
            val bufferSize = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT)
            check(bufferSize > 0) { "recording_start_failed" }
            input = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(bufferSize * 2, FRAME * 8)).build()
            check(input.state == AudioRecord.STATE_INITIALIZED) { "recording_start_failed" }
            check(System.currentTimeMillis() < endAt) { "event_ended" }
            if (stopReason.get() != null) { reason = stopReason.get()!!; return }
            file = RandomAccessFile(output, "rw")
            file.setLength(0)
            file.write(wavHeader(0))
            input.startRecording()
            check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "recording_start_failed" }
            started = System.currentTimeMillis()
            onStarted(started)
            val frame = ShortArray(FRAME)
            val pcm = ByteBuffer.allocate(FRAME * 2).order(ByteOrder.LITTLE_ENDIAN)
            var offset = 0
            var lastRead = SystemClock.elapsedRealtime()
            var lastCheck = lastRead
            val maxEnd = lastRead + CalendarPolicy.MAX_RECORDING_MS
            while (true) {
                val requestedStop = stopReason.get()
                if (requestedStop != null) { reason = requestedStop; break }
                val now = SystemClock.elapsedRealtime()
                if (System.currentTimeMillis() >= endAt) { reason = "event_ended"; break }
                if (now >= maxEnd) { reason = "recording_limit"; break }
                if (now - lastCheck >= 1_000) {
                    check(output.parentFile!!.usableSpace >= 20L * 1024 * 1024) { "storage_low" }
                    if (Build.VERSION.SDK_INT >= 29) {
                        check(input.activeRecordingConfiguration?.isClientSilenced != true) { "microphone_silenced" }
                    }
                    lastCheck = now
                }
                val count = input.read(frame, offset, FRAME - offset, AudioRecord.READ_NON_BLOCKING)
                check(count >= 0) { "audio_read_failed" }
                if (count == 0) {
                    check(now - lastRead < 5_000) { "audio_read_stalled" }
                    Thread.sleep(8)
                    continue
                }
                lastRead = now
                offset += count
                if (offset < FRAME) continue
                offset = 0
                amplitude = frame.maxOf { abs(it.toInt()) }.coerceAtMost(32767)
                peak = maxOf(peak, amplitude)
                pcm.clear()
                for (sample in frame) pcm.putShort(sample)
                file.write(pcm.array())
                pcmBytes += FRAME * 2
                durationMs = pcmBytes * 1000 / (RATE * 2)
                speech = vad.isSpeech(frame)
                val stop = policy.accept(FRAME * 1000L / RATE, speech)
                silenceMs = policy.silenceMs
                if (stop) { reason = "speech_absent_60s"; break }
            }
        } catch (error: Exception) {
            failed = true
            reason = when {
                error is SecurityException -> "microphone_restricted"
                error.message in setOf("storage_low", "recording_start_failed", "event_ended",
                    "microphone_silenced", "audio_read_failed", "audio_read_stalled") -> error.message!!
                else -> "speech_capture_failed"
            }
        } finally {
            // Record the microphone stop time, not model cleanup / file flush time.
            try { input?.stop() } catch (_: Exception) { }
            val ended = System.currentTimeMillis()
            try { input?.release() } catch (_: Exception) { }
            try { vad?.close() } catch (_: Exception) { }
            try {
                file?.let { it.seek(0); it.write(wavHeader(pcmBytes)); it.fd.sync() }
            } catch (_: Exception) { failed = true; reason = "file_finalize_failed" }
            try { file?.close() } catch (_: Exception) { failed = true; reason = "file_finalize_failed" }
            if (started != null && pcmBytes == 0L) { failed = true; reason = "audio_read_failed" }
            amplitude = 0
            onFinished(Result(reason, failed, durationMs, started, ended, peak, policy.speechSeen))
        }
    }

    companion object {
        const val RATE = 16_000
        const val FRAME = 512
        fun wavHeader(bytes: Long): ByteArray {
            require(bytes in 0..(Int.MAX_VALUE - 36).toLong())
            return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(bytes.toInt() + 36)
                put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
                putShort(1); putShort(1); putInt(RATE); putInt(RATE * 2)
                putShort(2); putShort(16); put("data".toByteArray(Charsets.US_ASCII)); putInt(bytes.toInt())
            }.array()
        }
    }
}
