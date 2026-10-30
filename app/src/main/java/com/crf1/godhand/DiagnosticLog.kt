package com.crf1.godhand

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent Android + native diagnostic journal.
 *
 * The current Android log is rotated at 768 KiB and one previous file is kept.
 * The native logger has its own 2 MiB limit in NativeCrashLogger.cpp.
 * The monitor reads only bounded tails so refreshing it cannot load unbounded
 * log files into memory.
 */
object DiagnosticLog {
    private const val TAG = "GodHandCRF1"
    private const val FILE_NAME = "godhand-diagnostics.log"
    private const val PREVIOUS_FILE_NAME = "godhand-diagnostics.log.1"
    private const val NATIVE_FILE_NAME = "godhand-native-crash.log"

    private const val MAX_JAVA_FILE_BYTES = 768 * 1024L
    private const val MAX_JAVA_LINE_BYTES = 32 * 1024
    private const val JAVA_TAIL_BYTES = 96 * 1024
    private const val NATIVE_TAIL_BYTES = 160 * 1024

    private val lock = Any()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var crashHandlerInstalled = false

    fun initialize(context: Context) {
        appContext = context.applicationContext
        log("APP_START", "Persistent diagnostic journal initialized")
    }

    fun nativeLogPath(): String {
        val context = appContext
            ?: throw IllegalStateException("DiagnosticLog must be initialized before loading the native runtime")
        return File(context.filesDir, NATIVE_FILE_NAME).absolutePath
    }

    fun log(stage: String, message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
        val line = "${timestamp} [${Thread.currentThread().name}] [${stage}] ${message}"
        Log.i(TAG, line)
        synchronized(lock) {
            try {
                val context = appContext ?: return
                appendBounded(File(context.filesDir, FILE_NAME), line + "\n")
            } catch (error: Exception) {
                Log.e(TAG, "Could not persist diagnostic event", error)
            }
        }
    }

    fun exception(stage: String, error: Throwable) {
        log(stage, "${error.javaClass.name}: ${error.message ?: "(sem mensagem)"}")
        val stack = Log.getStackTraceString(error)
        synchronized(lock) {
            try {
                val context = appContext ?: return
                appendBounded(File(context.filesDir, FILE_NAME), stack + "\n")
            } catch (writeError: Exception) {
                Log.e(TAG, "Could not persist exception stack trace", writeError)
            }
        }
        Log.e(TAG, "Exception at $stage", error)
    }

    /**
     * Returns a bounded, recent view of both journals. The UI monitor calls this
     * repeatedly, so it must never read an arbitrarily large file.
     */
    fun readAll(): String {
        val context = appContext ?: return "Logger ainda não inicializado."
        return try {
            synchronized(lock) {
                val dir = context.filesDir
                val previousFile = File(dir, PREVIOUS_FILE_NAME)
                val javaFile = File(dir, FILE_NAME)
                val nativeFile = File(dir, NATIVE_FILE_NAME)

                buildString {
                    appendLine("========== GODHAND JAVA/KOTLIN DIAGNOSTICS ==========")
                    if (previousFile.exists()) {
                        appendLine("[Arquivo anterior — trecho final]")
                        append(readTail(previousFile, JAVA_TAIL_BYTES))
                        appendLine()
                    }
                    appendLine("[Arquivo atual — trecho final]")
                    appendLine(if (javaFile.exists()) readTail(javaFile, JAVA_TAIL_BYTES) else "Nenhum evento Java/Kotlin gravado.")
                    appendLine()
                    appendLine("========== GODHAND NATIVE RUNTIME DIAGNOSTICS ==========")
                    appendLine(if (nativeFile.exists()) readTail(nativeFile, NATIVE_TAIL_BYTES) else "Nenhum registro nativo gravado.")
                }
            }
        } catch (error: Exception) {
            "Falha ao ler logs: ${error.javaClass.simpleName}: ${error.message}"
        }
    }

    fun clear() {
        synchronized(lock) {
            try {
                val context = appContext ?: return
                File(context.filesDir, FILE_NAME).writeText("", Charsets.UTF_8)
                File(context.filesDir, PREVIOUS_FILE_NAME).delete()
                // The native logger keeps this file descriptor open. Truncating the
                // same file preserves that descriptor; its next write checks the
                // actual file size and resumes normally.
                File(context.filesDir, NATIVE_FILE_NAME).writeText("", Charsets.UTF_8)
            } catch (error: Exception) {
                Log.e(TAG, "Could not clear diagnostic logs", error)
            }
        }
        log("LOG", "Diagnostic journal cleared by user")
    }

    fun installCrashHandler() {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            exception("UNCAUGHT_JAVA_CRASH", error)
            log("UNCAUGHT_JAVA_CRASH", "Uncaught exception on thread ${thread.name}")
            if (previous != null) previous.uncaughtException(thread, error)
            else android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun appendBounded(file: File, text: String) {
        file.parentFile?.mkdirs()
        var bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_JAVA_LINE_BYTES) {
            bytes = bytes.copyOfRange(bytes.size - MAX_JAVA_LINE_BYTES, bytes.size)
        }

        if (file.exists() && file.length() + bytes.size > MAX_JAVA_FILE_BYTES) {
            val previous = File(file.parentFile, PREVIOUS_FILE_NAME)
            if (previous.exists() && !previous.delete()) {
                Log.w(TAG, "Could not delete previous diagnostic log before rotation")
            }
            if (file.exists() && !file.renameTo(previous)) {
                // Safe fallback: keep the logger working even if rename is denied.
                file.writeText("", Charsets.UTF_8)
            }
        }

        file.appendBytes(bytes)
    }

    private fun readTail(file: File, maxBytes: Int): String {
        if (!file.exists() || file.length() == 0L) return ""
        RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            val start = (length - maxBytes).coerceAtLeast(0L)
            val bytes = ByteArray((length - start).toInt())
            input.seek(start)
            input.readFully(bytes)
            var firstLine = 0
            if (start > 0L) {
                while (firstLine < bytes.size && bytes[firstLine] != '\n'.code.toByte()) {
                    firstLine++
                }
                if (firstLine < bytes.size) firstLine++
            }
            return buildString {
                if (start > 0L) appendLine("[... início omitido; exibindo somente os registros recentes ...]")
                append(String(bytes, firstLine, bytes.size - firstLine, Charsets.UTF_8))
            }
        }
    }
}
