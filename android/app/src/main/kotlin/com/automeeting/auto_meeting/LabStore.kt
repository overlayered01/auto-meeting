package com.automeeting.auto_meeting

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class LabEntry(
    val id: String = UUID.randomUUID().toString(),
    val mode: String,
    val createdAt: Long,
    val scheduledAt: Long,
    val requestedDurationMs: Long,
    var status: String = "scheduled",
    var startedAt: Long? = null,
    var endedAt: Long? = null,
    var actualDurationMs: Long = 0,
    var reason: String? = null,
    var peakAmplitude: Int = 0,
    val extension: String = "m4a",
    val eventKey: String? = null,
    val eventTitle: String? = null,
    val calendarName: String? = null,
    val sourceAccount: String? = null,
    val eventEnd: Long? = null,
    var speechSeen: Boolean = false,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "id" to id, "mode" to mode, "createdAt" to createdAt,
        "scheduledAt" to scheduledAt, "requestedDurationMs" to requestedDurationMs,
        "status" to status, "startedAt" to startedAt, "endedAt" to endedAt,
        "actualDurationMs" to actualDurationMs, "reason" to reason,
        "peakAmplitude" to peakAmplitude,
        "extension" to extension, "eventKey" to eventKey, "eventTitle" to eventTitle,
        "calendarName" to calendarName, "sourceAccount" to sourceAccount,
        "eventEnd" to eventEnd, "speechSeen" to speechSeen,
    )

    companion object {
        fun fromJson(json: JSONObject) = LabEntry(
            id = json.getString("id"), mode = json.getString("mode"),
            createdAt = json.getLong("createdAt"), scheduledAt = json.getLong("scheduledAt"),
            requestedDurationMs = json.getLong("requestedDurationMs"),
            status = json.getString("status"),
            startedAt = if (json.isNull("startedAt")) null else json.getLong("startedAt"),
            endedAt = if (json.isNull("endedAt")) null else json.getLong("endedAt"),
            actualDurationMs = json.optLong("actualDurationMs"),
            reason = if (json.isNull("reason")) null else json.getString("reason"),
            peakAmplitude = json.optInt("peakAmplitude"),
            extension = json.optString("extension", "m4a"),
            eventKey = json.optionalString("eventKey"), eventTitle = json.optionalString("eventTitle"),
            calendarName = json.optionalString("calendarName"), sourceAccount = json.optionalString("sourceAccount"),
            eventEnd = if (json.isNull("eventEnd")) null else json.getLong("eventEnd"),
            speechSeen = json.optBoolean("speechSeen"),
        )
        private fun JSONObject.optionalString(key: String) = if (isNull(key)) null else getString(key)
    }
}

class LabStore(context: Context) {
    val directory = File(context.filesDir, "recordings").apply {
        check(isDirectory || mkdirs()) { "storage_unavailable" }
    }
    // Journals are outside the shareable audio folder.
    private val journals = File(context.noBackupFilesDir, "recording_journals").apply {
        check(isDirectory || mkdirs()) { "storage_unavailable" }
    }
    var unreadableCount: Int = 0
        private set

    private fun validId(id: String): String {
        require(id.matches(Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}"))) { "invalid_id" }
        return id
    }

    fun audio(id: String, extension: String = "m4a"): File {
        require(extension in setOf("m4a", "wav")) { "invalid_format" }
        return File(directory, "${validId(id)}.$extension")
    }
    fun audioFor(id: String) = audio(id, find(id).extension)
    private fun journal(id: String) = AtomicFile(File(journals, "${validId(id)}.json"))

    fun save(entry: LabEntry) {
        val file = journal(entry.id)
        val stream = file.startWrite()
        try {
            stream.write(JSONObject(entry.toMap()).toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    fun entries(): List<LabEntry> {
        unreadableCount = 0
        // AtomicFile may leave a .bak after an interrupted write.
        val ids = journals.listFiles().orEmpty().map { it.name.removeSuffix(".bak") }
            .filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }.distinct()
        return ids.mapNotNull { id ->
            try {
                val entry = LabEntry.fromJson(JSONObject(journal(id).readFully().toString(Charsets.UTF_8)))
                check(entry.id == id)
                entry
            } catch (_: Exception) {
                unreadableCount++
                null
            }
        }.sortedByDescending { it.createdAt }
    }

    fun find(id: String): LabEntry = entries().firstOrNull { it.id == validId(id) }
        ?: throw IllegalArgumentException("not_found")

    fun recoverInterrupted() {
        entries().filter { it.status in setOf("scheduled", "recording", "saving") }.forEach {
            it.status = "interrupted"
            it.reason = "process_ended"
            // Unknown stop time/duration: do not pretend recovery implies valid audio.
            save(it)
        }
    }

    fun delete(id: String) {
        val entry = find(id)
        val file = audio(id, entry.extension)
        check(!file.exists() || file.delete()) { "delete_failed" }
        journal(id).delete()
    }

    fun describe(entry: LabEntry): Map<String, Any?> = entry.toMap() + mapOf(
        "bytes" to audio(entry.id, entry.extension).length(),
        "hasFile" to (audio(entry.id, entry.extension).isFile && audio(entry.id, entry.extension).length() > 0),
    )
}

/** Accessed only on Android's main looper. Does not restore service execution. */
object LabRuntime {
    var activityVisible = false
    var launching = false
    var service: RecordingService? = null
    var current: LabEntry? = null
    var lastError: String? = null
    val busy: Boolean get() = launching || service != null
}
