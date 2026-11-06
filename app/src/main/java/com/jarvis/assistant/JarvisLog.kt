package com.jarvis.assistant

/** Tiny in-memory log shown on the main screen. */
object JarvisLog {
    private val lines = ArrayDeque<String>()

    @Volatile
    var listener: ((String) -> Unit)? = null

    fun add(msg: String) {
        synchronized(this) {
            lines.addLast(msg)
            while (lines.size > 60) lines.removeFirst()
        }
        listener?.invoke(text())
    }

    fun text(): String = synchronized(this) { lines.joinToString("\n") }
}
