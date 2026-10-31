package com.port80.app.audio

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Short history of what happened during the current stream (reconnections,
 * bitrate changes, audio problems), shown in the live panel so the cause of a
 * problem can be seen after the fact.
 */
object SessionLog {
    private const val MAX_ENTRIES = 60

    private var sessionStartMs = SystemClock.elapsedRealtime()
    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    fun startSession() {
        sessionStartMs = SystemClock.elapsedRealtime()
        _entries.value = emptyList()
    }

    fun add(message: String) {
        val elapsedSec = (SystemClock.elapsedRealtime() - sessionStartMs) / 1000
        val stamp = "%02d:%02d".format(elapsedSec / 60, elapsedSec % 60)
        _entries.update { (it + "$stamp  $message").takeLast(MAX_ENTRIES) }
    }
}
