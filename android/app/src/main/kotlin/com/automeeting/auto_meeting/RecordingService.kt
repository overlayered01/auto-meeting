package com.automeeting.auto_meeting

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat

class RecordingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: LabStore
    private var entry: LabEntry? = null
    private var recorder: MediaRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var dueElapsed = 0L
    private var startedElapsed = 0L
    private var finished = false
    private var lastNotificationSecond = -1L
    var amplitude = 0
        private set

    override fun onCreate() {
        super.onCreate()
        store = LabStore(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "녹음 테스트 상태", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            finish(if (entry?.startedAt == null) "cancelled" else "saved", "user_stop")
            return START_NOT_STICKY
        }
        if (intent?.action != START) { stopSelf(); return START_NOT_STICKY }
        if (entry != null) return START_NOT_STICKY
        LabRuntime.service = this
        LabRuntime.launching = false
        LabRuntime.lastError = null
        val delay = intent.getLongExtra("delayMs", 120_000L)
        val duration = intent.getLongExtra("durationMs", 60_000L)
        val mode = intent.getStringExtra("mode") ?: "foreground"
        try {
            TimingPolicy.validate(delay, duration, mode)
            val now = System.currentTimeMillis()
            entry = LabEntry(mode = mode, createdAt = now, scheduledAt = now + delay,
                requestedDurationMs = duration)
            LabRuntime.current = entry
            dueElapsed = SystemClock.elapsedRealtime() + delay
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else { startForeground(NOTIFICATION, notification()) }
            store.save(entry!!)
            // Short, user-initiated lab only; not an all-day keepalive.
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoMeeting:RecordingLab")
                .apply { acquire(delay + duration + 15_000L) }
            handler.post(tick)
        } catch (error: Exception) {
            finish("failed", if (error is SecurityException) "microphone_restricted" else "setup_failed")
        }
        return START_NOT_STICKY
    }

    private val tick = object : Runnable {
        override fun run() {
            val item = entry ?: return
            if (finished) return
            val now = SystemClock.elapsedRealtime()
            if (item.status == "scheduled") {
                when (TimingPolicy.decide(now, dueElapsed, LabRuntime.activityVisible, item.mode)) {
                    TimingPolicy.Decision.CANCEL_BACKGROUND -> finish("cancelled", "left_foreground")
                    TimingPolicy.Decision.MISSED -> finish("missed", "start_deadline_missed")
                    TimingPolicy.Decision.START -> startRecording()
                    TimingPolicy.Decision.WAIT -> Unit
                }
            } else if (item.status == "recording") {
                item.actualDurationMs = (now - startedElapsed).coerceAtLeast(0)
                if (item.actualDurationMs >= item.requestedDurationMs) {
                    finish("saved", "duration_reached")
                } else {
                    amplitude = try { recorder?.maxAmplitude ?: 0 } catch (_: Exception) { 0 }
                    item.peakAmplitude = maxOf(item.peakAmplitude, amplitude)
                }
            }
            if (!finished) {
                val second = now / 1000
                if (second != lastNotificationSecond) {
                    lastNotificationSecond = second
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
                }
                handler.postDelayed(this, 250)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun startRecording() {
        val item = entry ?: return
        try {
            check(store.directory.usableSpace >= 10L * 1024 * 1024) { "storage_low" }
            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()
            recorder!!.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(44_100)
                setAudioEncodingBitRate(96_000)
                setOutputFile(store.audio(item.id).absolutePath)
                setMaxDuration(item.requestedDurationMs.toInt())
                setOnErrorListener { _, _, _ -> finish("failed", "recorder_error") }
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) finish("saved", "duration_reached")
                }
                prepare()
                if (SystemClock.elapsedRealtime() - dueElapsed > 5_000L) {
                    finish("missed", "start_deadline_missed")
                    return
                }
                start()
            }
            startedElapsed = SystemClock.elapsedRealtime()
            item.startedAt = System.currentTimeMillis()
            item.status = "recording"
            store.save(item)
        } catch (error: Exception) {
            finish("failed", when {
                error is SecurityException -> "microphone_restricted"
                error.message == "storage_low" -> "storage_low"
                else -> "recording_start_failed"
            })
        }
    }

    fun finish(status: String, reason: String) {
        if (finished) return
        finished = true
        handler.removeCallbacks(tick)
        val item = entry
        var finalStatus = status
        var finalReason = reason
        if (item != null && item.startedAt != null) {
            item.actualDurationMs = (SystemClock.elapsedRealtime() - startedElapsed).coerceAtLeast(0)
            try { recorder?.stop() } catch (_: Exception) {
                finalStatus = "failed"
                finalReason = "file_finalize_failed"
            }
        }
        try { recorder?.release() } catch (_: Exception) { }
        recorder = null
        amplitude = 0
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        if (item != null) {
            item.endedAt = System.currentTimeMillis()
            if (finalStatus == "saved") {
                val metadata = MediaMetadataRetriever()
                try {
                    metadata.setDataSource(store.audio(item.id).absolutePath)
                    val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    check(duration != null && duration > 0)
                    item.actualDurationMs = duration
                } catch (_: Exception) {
                    finalStatus = "failed"
                    finalReason = "file_finalize_failed"
                } finally { metadata.release() }
            }
            item.status = finalStatus
            item.reason = finalReason
            try { store.save(item) } catch (_: Exception) { LabRuntime.lastError = "metadata_write_failed" }
        } else { LabRuntime.lastError = finalReason }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        val item = entry
        val recording = item?.status == "recording"
        val seconds = if (recording) item!!.actualDurationMs / 1000
            else ((dueElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 999) / 1000
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 2, Intent(this, RecordingService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_recording)
            .setContentTitle(if (recording) "● 녹음 중 · ${seconds}초" else "녹음 예약 · ${seconds}초 후 시작")
            .setContentText(if (recording) "최대 ${item!!.requestedDurationMs / 1000}초 후 자동 종료"
                else if (item?.mode == "standby") "화면 잠금 실험 · 아직 소리를 수집하지 않습니다"
                else "앱을 열어 두세요 · 화면을 벗어나면 예약 취소")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, if (recording) "종료·저장" else "예약 취소", stop).build()
    }

    fun remainingMs(): Long = when (entry?.status) {
        "scheduled" -> (dueElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        "recording" -> (entry!!.requestedDurationMs - (SystemClock.elapsedRealtime() - startedElapsed)).coerceAtLeast(0)
        else -> 0
    }

    fun onUiBackgrounded() {
        if (entry?.status == "scheduled" && entry?.mode == "foreground") {
            finish("cancelled", "left_foreground")
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        finish(if (entry?.startedAt == null) "cancelled" else "saved", "task_removed")
        super.onTaskRemoved(rootIntent)
    }
    override fun onDestroy() {
        if (!finished) finish("interrupted", "service_ended")
        if (LabRuntime.service === this) LabRuntime.service = null
        LabRuntime.launching = false
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val START = "com.automeeting.lab.START"
        const val STOP = "com.automeeting.lab.STOP"
        const val CHANNEL = "recording_lab"
        const val NOTIFICATION = 201
    }
}
