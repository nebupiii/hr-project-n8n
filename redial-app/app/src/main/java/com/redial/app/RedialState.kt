package com.redial.app

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared, in-process state of the redial session. The service writes it,
 * the activity observes it. All access happens on the main thread.
 */
object RedialState {
    data class Snapshot(
        val running: Boolean = false,
        val number: String = "",
        val attempt: Int = 0,
        val maxAttempts: Int = 0,
        val status: String = "",
        val log: List<String> = emptyList(),
    )

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(Snapshot) -> Unit>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    var current = Snapshot()
        private set

    fun update(transform: (Snapshot) -> Snapshot) {
        current = transform(current)
        val snapshot = current
        main.post { listeners.toList().forEach { it(snapshot) } }
    }

    fun log(message: String) = update {
        val line = "${timeFormat.format(Date())}  $message"
        it.copy(log = (it.log + line).takeLast(200))
    }

    fun addListener(listener: (Snapshot) -> Unit) {
        listeners += listener
        listener(current)
    }

    fun removeListener(listener: (Snapshot) -> Unit) {
        listeners -= listener
    }
}
