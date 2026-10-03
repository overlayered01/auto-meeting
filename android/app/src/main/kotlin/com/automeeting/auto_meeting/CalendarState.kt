package com.automeeting.auto_meeting

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Dedupe receipts survive app restarts AND recording-file deletion. */
class CalendarReceipts(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "calendar_receipts.json"))
    private val completed = mutableMapOf<String, Long>()
    init {
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
            val json = JSONObject(file.readFully().toString(Charsets.UTF_8))
            json.keys().forEach { completed[it] = json.getLong(it) }
        }
    }
    fun keys(): Set<String> = completed.keys.toSet()
    fun consume(event: CalendarOccurrence, now: Long) {
        completed.entries.removeAll { it.value < now - 30 * 24 * 60 * 60_000L }
        completed[event.key] = event.end
        val stream = file.startWrite()
        try {
            stream.write(JSONObject(completed.toMap()).toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) { file.failWrite(stream); throw error }
    }
}

/** Main-looper state; the audio worker exposes only volatile numeric values. No credentials. */
object CalendarRuntime {
    var service: CalendarRecordingService? = null
    var launching = false
    var armedAt: Long? = null
    var sessionEnd: Long? = null
    var lastRead: CalendarRead? = null
    var error: String? = null
    var reading = false
    val busy: Boolean get() = launching || service != null
}
