package com.automeeting.auto_meeting

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {
    private lateinit var store: LabStore
    private var permissionsResult: MethodChannel.Result? = null
    private var player: MediaPlayer? = null
    private var playingId: String? = null
    private var playbackState = "stopped"
    private val calendarQueries = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var calendars: DeviceCalendars

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        store = LabStore(this)
        calendars = DeviceCalendars(this)
        if (!LabRuntime.busy && !CalendarRuntime.busy) store.recoverInterrupted()
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "auto_meeting/recording_lab")
            .setMethodCallHandler { call, result ->
                try {
                    when (call.method) {
                        "snapshot" -> result.success(snapshot())
                        "calendarPermission" -> {
                            check(permissionsResult == null) { "already_active" }
                            if (calendars.allowed()) refreshCalendars(result)
                            else {
                                permissionsResult = result
                                requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 72)
                            }
                        }
                        "calendarRefresh" -> refreshCalendars(result)
                        "calendarSelect" -> {
                            check(!CalendarRuntime.busy) { "calendar_disarm_first" }
                            val ids = call.argument<List<String>>("ids")!!.toSet()
                            calendarQueries.execute {
                                val outcome = runCatching { calendars.select(ids); calendars.read() }
                                main.post {
                                    outcome.onSuccess { CalendarRuntime.lastRead = it; result.success(null) }
                                        .onFailure { result.error("calendar_selection_failed", null, null) }
                                }
                            }
                        }
                        "calendarAccountSettings" -> {
                            startActivity(Intent(Settings.ACTION_ADD_ACCOUNT)
                                .putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google")))
                            result.success(null)
                        }
                        "calendarExactAlarmSettings" -> {
                            if (Build.VERSION.SDK_INT >= 31) startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                android.net.Uri.parse("package:$packageName")))
                            result.success(null)
                        }
                        "calendarArm" -> {
                            check(!LabRuntime.busy && !CalendarRuntime.busy) { "already_active" }
                            check(LabRuntime.activityVisible) { "foreground_required" }
                            check(hasMicrophone() && hasNotifications(CalendarRecordingService.CHANNEL)) { "permissions_required" }
                            check(calendars.allowed()) { "calendar_permission_required" }
                            check(canUseExactAlarms()) { "exact_alarm_required" }
                            check(calendars.selected().isNotEmpty()) { "calendar_selection_required" }
                            stopPlayback()
                            CalendarRuntime.launching = true
                            try {
                                startForegroundService(Intent(this, CalendarRecordingService::class.java).setAction(CalendarRecordingService.START))
                            } catch (error: Exception) { CalendarRuntime.launching = false; throw error }
                            result.success(null)
                        }
                        "calendarDisarm" -> {
                            check(!CalendarRuntime.launching) { "starting" }
                            CalendarRuntime.service?.disarm()
                            result.success(null)
                        }
                        "calendarStopRecording" -> {
                            CalendarRuntime.service?.stopCurrent()
                            result.success(null)
                        }
                        "requestPermissions" -> requestLabPermissions(result)
                        "openSettings" -> {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:$packageName")))
                            result.success(null)
                        }
                        "schedule" -> {
                            check(!LabRuntime.busy && !CalendarRuntime.busy) { "already_active" }
                            check(LabRuntime.activityVisible) { "foreground_required" }
                            check(hasMicrophone() && hasNotifications()) { "permissions_required" }
                            val delay = call.argument<Number>("delayMs")!!.toLong()
                            val duration = call.argument<Number>("durationMs")!!.toLong()
                            val mode = call.argument<String>("mode")!!
                            TimingPolicy.validate(delay, duration, mode)
                            stopPlayback()
                            LabRuntime.launching = true
                            try {
                                startForegroundService(Intent(this, RecordingService::class.java)
                                    .setAction(RecordingService.START).putExtra("delayMs", delay)
                                    .putExtra("durationMs", duration).putExtra("mode", mode))
                            } catch (error: Exception) {
                                LabRuntime.launching = false
                                throw error
                            }
                            result.success(null)
                        }
                        "stop" -> {
                            check(!LabRuntime.launching) { "starting" }
                            LabRuntime.service?.let {
                                it.finish(if (LabRuntime.current?.startedAt == null) "cancelled" else "saved", "user_stop")
                            }
                            result.success(null)
                        }
                        "play" -> {
                            check(!LabRuntime.busy && !CalendarRuntime.busy) { "already_active" }
                            val id = call.argument<String>("id")!!
                            store.find(id)
                            stopPlayback()
                            playingId = id
                            playbackState = "loading"
                            player = MediaPlayer().apply {
                                setAudioAttributes(AudioAttributes.Builder()
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .setUsage(AudioAttributes.USAGE_MEDIA).build())
                                setDataSource(store.audioFor(id).absolutePath)
                                setOnPreparedListener {
                                    if (player === it) { playbackState = "playing"; it.start() }
                                }
                                setOnCompletionListener { if (player === it) stopPlayback() }
                                setOnErrorListener { failedPlayer, _, _ ->
                                    if (player === failedPlayer) {
                                        stopPlayback()
                                        LabRuntime.lastError = "playback_failed"
                                    }
                                    true
                                }
                                prepareAsync()
                            }
                            result.success(null)
                        }
                        "stopPlayback" -> { stopPlayback(); result.success(null) }
                        "share" -> {
                            check(!LabRuntime.busy && !CalendarRuntime.busy) { "already_active" }
                            val id = call.argument<String>("id")!!
                            store.find(id)
                            val file = store.audioFor(id)
                            check(file.isFile && file.length() > 0) { "file_missing" }
                            val uri = FileProvider.getUriForFile(this, "$packageName.recordings", file)
                            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND)
                                .setType(if (file.extension == "wav") "audio/wav" else "audio/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "녹음 내보내기"))
                            result.success(null)
                        }
                        "delete" -> {
                            check(!LabRuntime.busy && !CalendarRuntime.busy) { "already_active" }
                            val id = call.argument<String>("id")!!
                            if (playingId == id) stopPlayback()
                            store.delete(id)
                            if (LabRuntime.current?.id == id) LabRuntime.current = null
                            result.success(null)
                        }
                        "clearError" -> { LabRuntime.lastError = null; result.success(null) }
                        else -> result.notImplemented()
                    }
                } catch (error: Exception) {
                    if (call.method == "play") stopPlayback()
                    val known = setOf("already_active", "foreground_required", "permissions_required", "starting",
                        "calendar_permission_required", "exact_alarm_required", "calendar_selection_required", "calendar_disarm_first",
                        "invalid_delay", "invalid_duration", "invalid_mode", "invalid_id", "not_found", "file_missing", "delete_failed")
                    result.error(if (error.message in known) error.message!! else "native_error", null, null)
                }
            }
    }

    private fun hasMicrophone() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun hasNotifications(channel: String = RecordingService.CHANNEL): Boolean {
        val manager = NotificationManagerCompat.from(this)
        return manager.areNotificationsEnabled() && (manager.getNotificationChannel(channel)
            ?.importance != android.app.NotificationManager.IMPORTANCE_NONE)
    }
    private fun requestLabPermissions(result: MethodChannel.Result) {
        check(permissionsResult == null) { "already_active" }
        val missing = mutableListOf<String>()
        if (!hasMicrophone()) missing += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        if (missing.isEmpty()) { result.success(null); return }
        permissionsResult = result
        requestPermissions(missing.toTypedArray(), 71)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 71) { permissionsResult?.success(null); permissionsResult = null }
        if (requestCode == 72) {
            val pending = permissionsResult
            permissionsResult = null
            if (pending != null) {
                if (calendars.allowed()) refreshCalendars(pending) else pending.success(null)
            }
        }
    }
    private fun canUseExactAlarms() = Build.VERSION.SDK_INT < 31 || getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    private fun refreshCalendars(result: MethodChannel.Result) {
        if (CalendarRuntime.service != null) { CalendarRuntime.service!!.refresh(); result.success(null); return }
        if (CalendarRuntime.reading) { result.success(null); return }
        CalendarRuntime.reading = true
        calendarQueries.execute {
            val outcome = runCatching { calendars.read() }
            main.post {
                CalendarRuntime.reading = false
                outcome.onSuccess { CalendarRuntime.lastRead = it; CalendarRuntime.error = null; result.success(null) }
                    .onFailure {
                        CalendarRuntime.error = if (!calendars.allowed()) "calendar_permission_required" else "calendar_query_failed"
                        result.error(CalendarRuntime.error!!, null, null)
                    }
            }
        }
    }
    private fun calendarSnapshot(): Map<String, Any?> {
        val read = CalendarRuntime.lastRead
        val selected = calendars.selected()
        return mapOf("permission" to calendars.allowed(), "exactAlarms" to canUseExactAlarms(),
            "notifications" to hasNotifications(CalendarRecordingService.CHANNEL),
            "armed" to CalendarRuntime.busy, "recording" to (CalendarRuntime.service?.isRecording() == true),
            "launching" to CalendarRuntime.launching, "reading" to CalendarRuntime.reading,
            "armedAt" to CalendarRuntime.armedAt, "sessionEnd" to CalendarRuntime.sessionEnd,
            "readAt" to read?.readAt, "windowEnd" to read?.windowEnd,
            "calendars" to (if (calendars.allowed()) read?.calendars?.map { it.toMap(selected) } else null).orEmpty(),
            "events" to (if (calendars.allowed()) read?.events?.distinctBy { it.key }?.map { it.toMap() } else null).orEmpty(),
            "silenceMs" to (CalendarRuntime.service?.silenceMs() ?: 0L),
            "speech" to (CalendarRuntime.service?.speech() == true), "error" to CalendarRuntime.error)
    }
    private fun snapshot(): Map<String, Any?> {
        val persisted = store.entries().associateBy { it.id }.toMutableMap()
        LabRuntime.current?.let { persisted[it.id] = it }
        return mapOf(
            "supported" to true, "microphone" to hasMicrophone(), "notifications" to hasNotifications(),
            "busy" to (LabRuntime.busy || CalendarRuntime.busy), "launching" to LabRuntime.launching,
            "calendar" to calendarSnapshot(),
            "current" to LabRuntime.current?.let { store.describe(it) },
            "remainingMs" to (LabRuntime.service?.remainingMs() ?: 0L),
            "amplitude" to (CalendarRuntime.service?.amplitude() ?: LabRuntime.service?.amplitude ?: 0),
            "records" to persisted.values.sortedByDescending { it.createdAt }.map { store.describe(it) },
            "playingId" to playingId, "playbackState" to playbackState,
            "lastError" to LabRuntime.lastError, "unreadableCount" to store.unreadableCount,
            "device" to "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "storage" to "앱 전용 files/recordings · 테스트 M4A / 일정 WAV")
    }
    private fun stopPlayback() {
        player?.release(); player = null; playingId = null; playbackState = "stopped"
    }
    override fun onResume() { super.onResume(); LabRuntime.activityVisible = true }
    override fun onPause() {
        LabRuntime.activityVisible = false
        LabRuntime.service?.onUiBackgrounded()
        stopPlayback()
        super.onPause()
    }
    override fun onDestroy() {
        calendarQueries.shutdown()
        stopPlayback()
        permissionsResult?.error("activity_closed", null, null)
        permissionsResult = null
        super.onDestroy()
    }
}
