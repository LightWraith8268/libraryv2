package com.inknironapps.libraryiq.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: String,
    val tag: String,
    val message: String
) {
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun formatted(): String {
        val time = dateFormat.format(Date(timestamp))
        return "$time $level/$tag: $message"
    }
}

/**
 * In-memory ring buffer for debug log entries.
 * Captures important app events so the admin can view them in Settings
 * without needing Logcat/ADB.
 */
object DebugLog {
    private const val MAX_ENTRIES = 500

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun d(tag: String, message: String) {
        add("D", tag, message)
        Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        add("I", tag, message)
        Log.i(tag, message)
    }

    fun w(tag: String, message: String) {
        add("W", tag, message)
        Log.w(tag, message)
    }

    fun e(tag: String, message: String) {
        add("E", tag, message)
        Log.e(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable) {
        add("E", tag, "$message: ${throwable.message}")
        Log.e(tag, message, throwable)
    }

    private fun add(level: String, tag: String, message: String) {
        val entry = LogEntry(level = level, tag = tag, message = message)
        _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
