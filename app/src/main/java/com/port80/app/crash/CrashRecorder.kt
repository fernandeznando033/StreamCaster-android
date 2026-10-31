package com.port80.app.crash

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves the last unexpected crash to a local file so it can be shown on the
 * next launch (useful to know why a live stream ended). Stream keys are
 * redacted with [CredentialSanitizer] before writing. Nothing is sent anywhere.
 */
object CrashRecorder {
    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val text = "Fecha: $date\nHilo: ${thread.name}\n\n$sw"
                File(appContext.filesDir, FILE_NAME).writeText(sanitize(text).take(20_000))
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun readLastCrash(context: Context): String? = try {
        val f = File(context.applicationContext.filesDir, FILE_NAME)
        if (f.exists()) f.readText() else null
    } catch (_: Throwable) {
        null
    }

    fun clear(context: Context) {
        try {
            File(context.applicationContext.filesDir, FILE_NAME).delete()
        } catch (_: Throwable) {
        }
    }

    private fun sanitize(text: String): String = try {
        CredentialSanitizer.sanitize(text)
    } catch (_: Throwable) {
        text
    }
}
