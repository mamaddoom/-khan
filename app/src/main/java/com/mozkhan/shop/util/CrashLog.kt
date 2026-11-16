package com.mozkhan.shop.util

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Keeps the last crash (and non-fatal errors) in a small text file so the cause can be shown
 * inside the app on the next launch instead of being lost.
 */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "crash.txt")
    /** non-fatal errors go to their own file: the "app closed with an error" dialog must only appear after a real crash */
    private fun errorFile(ctx: Context) = File(ctx.filesDir, "errors.txt")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { write(file(app), "FATAL on ${t.name}", e) }
            previous?.uncaughtException(t, e)
        }
    }

    fun record(ctx: Context, e: Throwable, label: String = "ERROR") = runCatching { write(errorFile(ctx.applicationContext), label, e) }

    private fun write(f: File, label: String, e: Throwable) {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val text = "[$label] ${java.util.Date()}\n$sw".take(6000)
        f.writeText(text)
    }

    /** Returns and keeps the stored report (null when there is none). */
    fun read(ctx: Context): String? = runCatching { file(ctx).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(ctx: Context) { runCatching { file(ctx).delete() } }
}
