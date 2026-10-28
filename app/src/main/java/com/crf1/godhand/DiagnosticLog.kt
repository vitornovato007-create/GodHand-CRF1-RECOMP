package com.crf1.godhand

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent diagnostics for launcher failures.
 * Logs are stored in the app-private files directory and can be shared from the UI.
 */
object DiagnosticLog {
    private const val TAG = "GodHandCRF1"
    private const val FILE_NAME = "godhand-diagnostics.log"
    private val lock = Any()
    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        log("LOG", "Diagnostic logger initialized")
    }

    fun log(stage: String, message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
        val line = "$timestamp [${Thread.currentThread().name}] [$stage] $message"
        Log.i(TAG, line)
        synchronized(lock) {
            try {
                val context = appContext ?: return
                File(context.filesDir, FILE_NAME).appendText(line + "\n", Charsets.UTF_8)
            } catch (error: Exception) {
                Log.e(TAG, "Could not persist diagnostic log", error)
            }
        }
    }

    fun exception(stage: String, error: Throwable) {
        log(stage, "${error.javaClass.name}: ${error.message ?: "(sem mensagem)"}")
        val stack = Log.getStackTraceString(error)
        synchronized(lock) {
            try {
                val context = appContext ?: return
                File(context.filesDir, FILE_NAME).appendText(stack + "\n", Charsets.UTF_8)
            } catch (writeError: Exception) {
                Log.e(TAG, "Could not persist exception stack trace", writeError)
            }
        }
        Log.e(TAG, "Exception at $stage", error)
    }

    fun readAll(): String {
        val context = appContext ?: return "Logger ainda não inicializado."
        return try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) file.readText(Charsets.UTF_8) else "Nenhum log gravado ainda."
        } catch (error: Exception) {
            "Falha ao ler log: ${error.javaClass.simpleName}: ${error.message}"
        }
    }

    fun clear() {
        synchronized(lock) {
            try {
                val context = appContext ?: return
                File(context.filesDir, FILE_NAME).writeText("", Charsets.UTF_8)
            } catch (error: Exception) {
                Log.e(TAG, "Could not clear diagnostic log", error)
            }
        }
        log("LOG", "Log cleared by user")
    }

    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            exception("UNCAUGHT_CRASH", error)
            log("UNCAUGHT_CRASH", "Thread terminated: ${thread.name}")
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }
}
