package com.automeeting.auto_meeting

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars as C
import android.provider.CalendarContract.Instances as I
import java.security.MessageDigest

data class DeviceCalendar(val id: String, val providerId: Long, val name: String,
    val account: String, val accountType: String, val owner: Boolean) {
    fun toMap(selected: Set<String>) = mapOf("id" to id, "name" to name, "account" to account,
        "accountType" to accountType, "owner" to owner, "selected" to (id in selected))
}

data class CalendarRead(val calendars: List<DeviceCalendar>, val events: List<CalendarOccurrence>,
    val readAt: Long, val windowEnd: Long)

/** Read-only device provider. Authentication / cloud synchronization remain with the account's sync app. */
class DeviceCalendars(private val context: Context) {
    private val prefs = context.getSharedPreferences("calendar_selection", Context.MODE_PRIVATE)
    fun selected(): Set<String> = prefs.getStringSet("ids", emptySet())!!.toSet()
    fun select(ids: Set<String>) {
        require(ids.size <= 100) { "too_many_calendars" }
        val known = calendars().map { it.id }.toSet()
        require(known.containsAll(ids)) { "calendar_missing" }
        check(prefs.edit().putStringSet("ids", ids).commit()) { "metadata_write_failed" }
    }

    fun allowed() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun calendars(): List<DeviceCalendar> {
        check(allowed()) { "calendar_permission_required" }
        val columns = arrayOf(C._ID, C.CALENDAR_DISPLAY_NAME, C.ACCOUNT_NAME, C.ACCOUNT_TYPE,
            C.CALENDAR_ACCESS_LEVEL, C.VISIBLE, C.SYNC_EVENTS)
        val result = mutableListOf<DeviceCalendar>()
        val cursor = context.contentResolver.query(C.CONTENT_URI, columns, null, null, null)
            ?: error("calendar_query_failed")
        cursor.use {
            while (it.moveToNext()) {
                // Hidden / unsynchronized calendars may not have expanded Instances on the device.
                if (it.getInt(5) == 0 || it.getInt(6) == 0) continue
                val nativeId = it.getLong(0)
                val account = it.getString(2).orEmpty()
                val type = it.getString(3).orEmpty()
                val id = "$nativeId:${digest("$type\u0000$account").take(16)}"
                result += DeviceCalendar(id, nativeId, it.getString(1).orEmpty().ifBlank { "이름 없는 캘린더" },
                    account, type, it.getInt(4) >= C.CAL_ACCESS_OWNER)
            }
        }
        return result.sortedWith(compareBy({ it.account }, { it.name }, { it.id }))
    }

    fun read(now: Long = System.currentTimeMillis()): CalendarRead {
        val sources = calendars()
        val ids = selected()
        val enabled = sources.filter { it.id in ids }.associateBy { it.providerId }
        val end = now + 7 * 24 * 60 * 60_000L
        if (enabled.isEmpty()) return CalendarRead(sources, emptyList(), now, end)
        val builder = I.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, now - CalendarPolicy.MAX_RECORDING_MS)
        ContentUris.appendId(builder, end)
        val columns = arrayOf(I.EVENT_ID, I.CALENDAR_ID, I.TITLE, I.BEGIN, I.END, I.ALL_DAY,
            I.STATUS, I.SELF_ATTENDEE_STATUS, I.UID_2445)
        val placeholders = enabled.keys.joinToString(",") { "?" }
        val cursor = context.contentResolver.query(builder.build(), columns,
            "${I.CALENDAR_ID} IN ($placeholders)", enabled.keys.map { it.toString() }.toTypedArray(),
            "${I.BEGIN} ASC") ?: error("calendar_query_failed")
        val events = mutableListOf<CalendarOccurrence>()
        cursor.use {
            while (it.moveToNext()) {
                // Never silently truncate and pretend missing events were deleted.
                check(events.size < 10_000) { "calendar_result_too_large" }
                val source = enabled[it.getLong(1)] ?: continue
                val begin = it.getLong(3)
                val uid = it.getString(8)?.takeIf { value -> value.isNotBlank() }
                val identity = if (uid != null) "uid:$uid:$begin" else "device:${source.id}:${it.getLong(0)}:$begin"
                val event = CalendarOccurrence(digest(identity), source.id,
                    it.getString(2).orEmpty().ifBlank { "제목 없는 일정" }, source.name, source.account,
                    begin, it.getLong(4), it.getInt(5) != 0,
                    it.getInt(6) == CalendarContract.Events.STATUS_CANCELED,
                    it.getInt(7) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED)
                if (CalendarPolicy.eligible(event)) events += event
            }
        }
        return CalendarRead(sources, events, now, end)
    }

    companion object {
        fun digest(value: String) = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
