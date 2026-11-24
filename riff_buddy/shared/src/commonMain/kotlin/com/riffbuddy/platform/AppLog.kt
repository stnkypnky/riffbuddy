package com.riffbuddy.platform


/**
 * Diagnostic logging from shared code. Each app points [sink] at its own log
 * (Android's logcat, the desktop's standard error) when it starts; until then
 * messages are printed. Never pass anything secret: logs are readable
 * by anyone with the device or the terminal.
 */
object AppLog {

    fun interface Sink {
        fun log(level: Char, tag: String, message: String, error: Throwable?)
    }

    @Volatile var sink: Sink = Sink { level, tag, message, error ->
        println("$level/$tag: $message")
        error?.let { println(it.stackTraceToString()) }
    }

    fun i(tag: String, message: String) = sink.log('I', tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = sink.log('W', tag, message, error)
}
