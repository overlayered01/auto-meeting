package com.automeeting.auto_meeting

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors

/** User-arms while visible. Alarms only wake THIS existing microphone FGS; never resurrect it. */
class CalendarRecordingService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val queries = Executors.newSingleThreadExecutor()
    private lateinit var repository: DeviceCalendars
    private lateinit var store: LabStore
    private lateinit var receipts: CalendarReceipts
    private var audio: SpeechRecorder? = null
    private var active: CalendarOccurrence? = null
    private var record: LabEntry? = null
    private var stopping = false
    private var destroyed = false
    private var initialized = false
    private var deadlineElapsed = 0L
    private var lastAttempt = 0L
    private var nextAlarm = 0L
    private var audioWake: PowerManager.WakeLock? = null
    private var readWake: PowerManager.WakeLock? = null
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) { refresh() }
    }
    private val timeChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            CalendarRuntime.sessionEnd = System.currentTimeMillis() + remainingSession()
            refresh()
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "캘린더 자동 녹음", NotificationManager.IMPORTANCE_LOW))
        repository = DeviceCalendars(this)
        store = LabStore(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { disarm(); return START_NOT_STICKY }
        if (intent?.action != START || initialized) return START_NOT_STICKY
        initialized = true
        CalendarRuntime.service = this
        CalendarRuntime.launching = false
        CalendarRuntime.armedAt = System.currentTimeMillis()
        deadlineElapsed = SystemClock.elapsedRealtime() + CalendarPolicy.SESSION_MS
        CalendarRuntime.sessionEnd = CalendarRuntime.armedAt!! + CalendarPolicy.SESSION_MS
        CalendarRuntime.error = null
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFICATION, notification())
            check(!LabRuntime.busy) { "already_active" }
            receipts = CalendarReceipts(this)
            scheduleNext(emptyList())
            contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(timeChanges, filter, RECEIVER_NOT_EXPORTED)
            else registerReceiver(timeChanges, filter)
            refresh()
            main.post(tick)
        } catch (_: Exception) {
            CalendarRuntime.error = "calendar_arm_failed"
            disarm()
        }
        return START_NOT_STICKY
    }

    private fun remainingSession() = (deadlineElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0)
    fun silenceMs() = audio?.silenceMs ?: 0L
    fun amplitude() = audio?.amplitude ?: 0
    fun speech() = audio?.speech ?: false
    fun isRecording() = audio != null

    private val tick = object : Runnable {
        override fun run() {
            if (destroyed || stopping) return
            if (remainingSession() == 0L) { disarm("session_expired"); return }
            val now = System.currentTimeMillis()
            record?.let { it.actualDurationMs = audio?.durationMs ?: it.actualDurationMs }
            if (SystemClock.elapsedRealtime() - lastAttempt >= 60_000 ||
                (nextAlarm != 0L && now >= nextAlarm && SystemClock.elapsedRealtime() - lastAttempt >= 1_000)) {
                refresh()
            }
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
            main.postDelayed(this, if (audio == null) 1_000 else 500)
        }
    }

    /** Alarm delivery and provider changes revalidate the event before touching the microphone. */
    fun refresh() {
        if (destroyed || stopping || CalendarRuntime.reading) return
        CalendarRuntime.reading = true
        lastAttempt = SystemClock.elapsedRealtime()
        if (readWake?.isHeld != true) {
            readWake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoMeeting:CalendarRead")
                .apply { acquire(15_000) }
        }
        queries.execute {
            val outcome = runCatching { repository.read() }
            main.post {
                CalendarRuntime.reading = false
                if (!destroyed && !stopping) {
                    outcome.onSuccess {
                        CalendarRuntime.lastRead = it
                        CalendarRuntime.error = null
                        try { evaluate(it) } catch (_: Exception) {
                            CalendarRuntime.error = "calendar_schedule_failed"
                            disarm("calendar_schedule_failed")
                        }
                    }.onFailure {
                        CalendarRuntime.error = if (!repository.allowed()) "calendar_permission_required" else "calendar_query_failed"
                        // A failed read is not a deletion. Preserve cache, but stop current capture conservatively.
                        audio?.stop("calendar_query_failed")
                        if (!repository.allowed()) disarm("calendar_permission_required")
                    }
                }
                if (readWake?.isHeld == true) readWake?.release()
                readWake = null
            }
        }
    }

    private fun evaluate(read: CalendarRead) {
        val now = System.currentTimeMillis()
        if (remainingSession() == 0L) { disarm("session_expired"); return }
        active?.let { current ->
            val refreshed = read.events.firstOrNull { it.key == current.key && it.end > now }
            if (refreshed == null) audio?.stop("event_removed_or_ended")
            else {
                active = refreshed
                audio?.endAt = minOf(refreshed.end, now + remainingSession())
            }
        }
        val due = CalendarPolicy.due(read.events, now, CalendarRuntime.armedAt!!, receipts.keys())
        for (event in due) {
            // Durable receipt BEFORE starting: no second recording after silence, restart, or file deletion.
            receipts.consume(event, now)
            when {
                CalendarPolicy.tooLate(event, now) -> skipped(event, "calendar_start_missed")
                audio != null -> skipped(event, "overlapping_event")
                else -> begin(event)
            }
        }
        scheduleNext(read.events)
    }

    private fun newEntry(event: CalendarOccurrence) = LabEntry(mode = "calendar", createdAt = System.currentTimeMillis(),
        scheduledAt = event.begin, requestedDurationMs = minOf(event.end - event.begin, CalendarPolicy.MAX_RECORDING_MS),
        extension = "wav", eventKey = event.key, eventTitle = event.title, calendarName = event.calendarName,
        sourceAccount = event.account, eventEnd = event.end)

    private fun skipped(event: CalendarOccurrence, reason: String) {
        store.save(newEntry(event).apply { status = "missed"; this.reason = reason; endedAt = System.currentTimeMillis() })
    }

    private fun begin(event: CalendarOccurrence) {
        val entry = newEntry(event)
        store.save(entry)
        record = entry
        active = event
        LabRuntime.current = entry
        audioWake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoMeeting:CalendarAudio")
            .apply { acquire(CalendarPolicy.MAX_RECORDING_MS + 15_000) }
        audio = SpeechRecorder(applicationContext, store.audio(entry.id, entry.extension),
            minOf(event.end, System.currentTimeMillis() + remainingSession()),
            onStarted = { started -> main.post {
                entry.startedAt = started; entry.status = "recording"
                try { store.save(entry) } catch (_: Exception) {
                    CalendarRuntime.error = "metadata_write_failed"; audio?.stop("metadata_write_failed")
                }
            } },
            onFinished = { result -> main.post {
                entry.startedAt = result.startedAt
                entry.endedAt = result.endedAt
                entry.actualDurationMs = result.durationMs
                entry.peakAmplitude = result.peak
                entry.speechSeen = result.speechSeen
                entry.reason = result.reason
                entry.status = if (result.failed) "failed" else if (result.startedAt == null) "cancelled" else "saved"
                try { store.save(entry) } catch (_: Exception) { CalendarRuntime.error = "metadata_write_failed" }
                audio = null; active = null; record = null
                if (audioWake?.isHeld == true) audioWake?.release()
                audioWake = null
                if (destroyed) { if (CalendarRuntime.service === this) CalendarRuntime.service = null }
                else if (stopping) finishService()
                else refresh()
            } })
        audio!!.start()
    }

    private fun alarmIntent() = PendingIntent.getBroadcast(this, 302,
        Intent(this, CalendarAlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun scheduleNext(events: List<CalendarOccurrence>) {
        val now = System.currentTimeMillis()
        nextAlarm = listOfNotNull(CalendarPolicy.nextStart(events, now, CalendarRuntime.armedAt!!, receipts.keys()),
            active?.end?.takeIf { it > now }, now + remainingSession()).min()
        val manager = getSystemService(AlarmManager::class.java)
        check(Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) { "exact_alarm_required" }
        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextAlarm, alarmIntent())
    }

    fun stopCurrent() { audio?.stop("user_stop") }
    fun disarm(reason: String = "monitor_stopped") {
        stopping = true
        main.removeCallbacks(tick)
        getSystemService(AlarmManager::class.java).cancel(alarmIntent())
        if (audio != null) audio?.stop(reason) else finishService()
    }
    private fun finishService() { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 301, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 303, Intent(this, CalendarRecordingService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = when {
            stopping -> "녹음을 마무리하고 대기를 종료합니다"
            audio != null -> "말소리 미감지 ${silenceMs() / 1000}초 / 60초 · 기기에 저장"
            CalendarRuntime.error != null -> "일정 확인 실패 · 앱에서 상태를 확인하세요"
            else -> "선택한 일정의 시작을 기다립니다 · 대기 중 녹음 안 함"
        }
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_recording)
            .setContentTitle(if (audio != null) "● 캘린더 녹음 중" else "캘린더 녹음 대기")
            .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, "대기 종료·녹음 저장", stop).build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // User explicitly armed this mode; the notification's Stop action is the off switch.
        super.onTaskRemoved(rootIntent)
    }
    override fun onDestroy() {
        destroyed = true
        audio?.stop("service_ended")
        main.removeCallbacks(tick)
        getSystemService(AlarmManager::class.java).cancel(alarmIntent())
        try { contentResolver.unregisterContentObserver(observer) } catch (_: Exception) { }
        try { unregisterReceiver(timeChanges) } catch (_: Exception) { }
        queries.shutdown()
        if (readWake?.isHeld == true) readWake?.release()
        if (audioWake?.isHeld == true) audioWake?.release()
        if (audio == null && CalendarRuntime.service === this) CalendarRuntime.service = null
        CalendarRuntime.launching = false
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val START = "com.automeeting.calendar.START"
        const val STOP = "com.automeeting.calendar.STOP"
        const val CHANNEL = "calendar_recording"
        const val NOTIFICATION = 301
    }
}

class CalendarAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val service = CalendarRuntime.service
        if (service != null) service.refresh()
        else CalendarRuntime.error = "calendar_rearm_required"
    }
}
