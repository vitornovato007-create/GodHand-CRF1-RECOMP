package com.crf1.godhand
import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticLog {
    private const val TAG = "GodHandCRF1"
    private const val FILE_NAME = "godhand-diagnostics.log"
    private const val NATIVE_FILE_NAME = "godhand-native-crash.log"
    private val lock = Any()
    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        log("APP_START", "Persistent diagnostic journal initialized")
    }

    fun nativeLogPath(): String {
        val context = appContext ?: throw IllegalStateException("DiagnosticLog must be initialized before loading the native runtime")
        return File(context.filesDir, NATIVE_FILE_NAME).absolutePath
    }

    fun log(stage: String, message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
        val line = "\${timestamp} [\${Thread.currentThread().name}] [\${stage}] \${message}"
        Log.i(TAG, line)
        synchronized(lock) {
            try {
                val context = appContext ?: return
                File(context.filesDir, FILE_NAME).appendText(line + "\n", Charsets.UTF_8)
            } catch (error: Exception) {
                Log.e(TAG, "Could not persist diagnostic event", error)
            }
        }
    }

    fun exception(stage: String, error: Throwable) {
        log(stage, "\${error.javaClass.name}: \${error.message ?: "(sem mensagem)"}")
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
            val javaFile = File(context.filesDir, FILE_NAME)
            val nativeFile = File(context.filesDir, NATIVE_FILE_NAME)
            buildString {
                appendLine("========== GODHAND JAVA/KOTLIN DIAGNOSTICS ==========")
                appendLine(if (javaFile.exists()) javaFile.readText(Charsets.UTF_8) else "Nenhum evento Java/Kotlin gravado.")
                appendLine()
                appendLine("========== GODHAND NATIVE RUNTIME DIAGNOSTICS ==========")
                appendLine(if (nativeFile.exists()) nativeFile.readText(Charsets.UTF_8) else "Nenhum registro nativo gravado.")
            }
        } catch (error: Exception) {
            "Falha ao ler logs: \${error.javaClass.simpleName}: \${error.message}"
        }
    }

    fun clear() {
        synchronized(lock) {
            try {
                val context = appContext ?: return
                File(context.filesDir, FILE_NAME).writeText("", Charsets.UTF_8)
                File(context.filesDir, NATIVE_FILE_NAME).writeText("", Charsets.UTF_8)
            } catch (error: Exception) {
                Log.e(TAG, "Could not clear diagnostic logs", error)
            }
        }
        log("LOG", "Diagnostic journal cleared by user")
    }

    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            exception("UNCAUGHT_JAVA_CRASH", error)
            log("UNCAUGHT_JAVA_CRASH", "Uncaught exception on thread \${thread.name}")
            if (previous != null) previous.uncaughtException(thread, error)
            else android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
