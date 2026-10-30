package com.crf1.godhand

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.util.Locale

/**
 * Selects a user's ISO, extracts the game data into app-private storage, then
 * opens the Android surface that hosts the native God Hand runtime.
 */
class IsoPickerActivity : ComponentActivity() {
    private lateinit var selectedIsoText: TextView
    private lateinit var statusText: TextView
    private var selectedIsoUri: Uri? = null
    private var selectedIsoName: String? = null

    @Volatile
    private var launchInProgress = false

    @Volatile
    private var lastNativeProgressPercent = -1


    private val isoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            DiagnosticLog.log("ISO_PICKER", "User cancelled document picker")
            updateStatus("Nenhuma ISO selecionada.")
            return@registerForActivityResult
        }
        handleSelectedIso(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticLog.initialize(applicationContext)
        DiagnosticLog.installCrashHandler()
        DiagnosticLog.log(
            "DEVICE",
            "manufacturer=${android.os.Build.MANUFACTURER}; model=${android.os.Build.MODEL}; " +
                "device=${android.os.Build.DEVICE}; product=${android.os.Build.PRODUCT}; " +
                "Android=${android.os.Build.VERSION.RELEASE}; SDK=${android.os.Build.VERSION.SDK_INT}; " +
                "ABIs=${android.os.Build.SUPPORTED_ABIS.joinToString()}"
        )
        DiagnosticLog.log("APP_START", "IsoPickerActivity.onCreate")
        buildUi()
        restoreSelectedIso()
    }

    override fun onResume() {
        super.onResume()
        DiagnosticLog.log("APP_LIFECYCLE", "IsoPickerActivity.onResume")
    }

    override fun onPause() {
        DiagnosticLog.log("APP_LIFECYCLE", "IsoPickerActivity.onPause")
        super.onPause()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(26), dp(22), dp(22))
        }

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val title = TextView(this).apply {
            text = "God Hand\nAndroid ARM64"
            textSize = 26f
            gravity = Gravity.CENTER
        }
        content.addView(title, matchWrap())

        val subtitle = TextView(this).apply {
            text = "Selecione sua ISO para preparar a inicialização."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(18))
        }
        content.addView(subtitle, matchWrap())

        selectedIsoText = TextView(this).apply {
            text = "ISO: nenhuma selecionada"
            textSize = 14f
            setTextIsSelectable(true)
        }
        content.addView(selectedIsoText, matchWrap())

        val selectButton = Button(this).apply {
            text = "Selecionar ISO"
            setOnClickListener {
                DiagnosticLog.log("ISO_PICKER", "Opening Android document picker")
                try {
                    isoPicker.launch(arrayOf("*/*"))
                } catch (error: Exception) {
                    DiagnosticLog.exception("ISO_PICKER_OPEN_FAILED", error)
                    showError("Não foi possível abrir o seletor de arquivos.", error.message)
                }
            }
        }
        content.addView(selectButton, matchWrap())

        val playButton = Button(this).apply {
            text = "Jogar"
            setOnClickListener { onPlayClicked() }
        }
        content.addView(playButton, matchWrap())

        val exportButton = Button(this).apply {
            text = "Exportar log de diagnóstico"
            setOnClickListener { shareLog() }
        }
        content.addView(exportButton, matchWrap())

        val monitorButton = Button(this).apply {
            text = "Monitor de log em tempo real"
            setOnClickListener { showDiagnosticMonitor() }
        }
        content.addView(monitorButton, matchWrap())

        val clearButton = Button(this).apply {
            text = "Limpar log"
            setOnClickListener {
                DiagnosticLog.clear()
                updateStatus("Log limpo. As próximas etapas serão registradas.")
            }
        }
        content.addView(clearButton, matchWrap())

        statusText = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
            setPadding(0, dp(18), 0, 0)
            text = "Aguardando seleção da ISO."
        }
        content.addView(statusText, matchWrap())

        val note = TextView(this).apply {
            text = "\nAo tocar em Jogar, o aplicativo valida a ISO, extrai os arquivos necessários para o armazenamento privado e abre o runtime nativo com backend gráfico Android."
            textSize = 12f
            setPadding(0, dp(18), 0, 0)
        }
        content.addView(note, matchWrap())

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun handleSelectedIso(uri: Uri) {
        DiagnosticLog.log("ISO_PICKER", "Document URI returned: $uri")
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
                DiagnosticLog.log("ISO_PERMISSION", "Persistable read permission granted")
            } catch (error: SecurityException) {
                // Some document providers do not offer persistable permissions. We can still
                // validate this session's URI and report if it becomes unavailable later.
                DiagnosticLog.log("ISO_PERMISSION", "Persistable permission unavailable: ${error.message}")
            }

            val displayName = queryDisplayName(uri)
            DiagnosticLog.log("ISO_METADATA", "Selected document name=$displayName")
            if (!displayName.lowercase(Locale.ROOT).endsWith(".iso")) {
                DiagnosticLog.log("ISO_REJECTED", "File rejected because its name does not end in .iso")
                showError("Selecione um arquivo com extensão .iso.", displayName)
                return
            }

            contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                val size = descriptor.statSize
                DiagnosticLog.log("ISO_READ_TEST", "Opened selected ISO; reported size=$size bytes")
                if (size in 1 until 1_048_576) {
                    DiagnosticLog.log("ISO_REJECTED", "File is smaller than 1 MiB")
                    showError("O arquivo parece pequeno demais para uma imagem de disco.", displayName)
                    return
                }
            } ?: run {
                DiagnosticLog.log("ISO_READ_TEST_FAILED", "ContentResolver returned a null file descriptor")
                showError("O Android não conseguiu abrir esse arquivo.", displayName)
                return
            }

            selectedIsoUri = uri
            selectedIsoName = displayName
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_URI, uri.toString())
                .putString(KEY_NAME, displayName)
                .apply()
            selectedIsoText.text = "ISO selecionada: $displayName"
            updateStatus("ISO selecionada e acessível. Toque em Jogar para executar as verificações de inicialização.")
            DiagnosticLog.log("ISO_SELECTED", "ISO selected and read access verified")
        } catch (error: Exception) {
            DiagnosticLog.exception("ISO_SELECTION_FAILED", error)
            showError("Falha ao validar a ISO.", error.message)
        }
    }

    private fun restoreSelectedIso() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val rawUri = prefs.getString(KEY_URI, null) ?: run {
            DiagnosticLog.log("ISO_RESTORE", "No previously selected ISO")
            return
        }
        val name = prefs.getString(KEY_NAME, "arquivo.iso") ?: "arquivo.iso"
        try {
            val uri = Uri.parse(rawUri)
            contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                DiagnosticLog.log("ISO_RESTORE", "Stored ISO permission still works; size=${descriptor.statSize}")
                selectedIsoUri = uri
                selectedIsoName = name
                selectedIsoText.text = "ISO selecionada: $name"
                updateStatus("ISO anterior restaurada. Toque em Jogar para continuar.")
            } ?: run {
                DiagnosticLog.log("ISO_RESTORE_FAILED", "Stored URI could not be opened; clearing selection")
                clearStoredIso()
            }
        } catch (error: Exception) {
            DiagnosticLog.exception("ISO_RESTORE_FAILED", error)
            clearStoredIso()
        }
    }

    private fun onPlayClicked() {
        DiagnosticLog.log("PLAY_CLICK", "User requested game boot")
        val uri = selectedIsoUri
        if (uri == null) {
            DiagnosticLog.log("PLAY_ABORTED", "No ISO selected")
            showError("Primeiro selecione a ISO do jogo.", null)
            return
        }
        if (launchInProgress) {
            updateStatus("A preparação da ISO já está em andamento.")
            return
        }

        launchInProgress = true
        updateStatus("Validando a ISO e preparando os arquivos do jogo...")
        Thread({
            try {
                DiagnosticLog.log("PLAY_VALIDATE", "Rechecking selected ISO: ${selectedIsoName ?: uri}")
                val bridge = RuntimeBridge()
                val inspectionResult = contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                    val size = descriptor.statSize
                    DiagnosticLog.log("PLAY_VALIDATE", "ISO descriptor opened; size=$size bytes")
                    if (size > 0 && size < 1_048_576) {
                        throw IllegalArgumentException("A imagem tem menos de 1 MiB ($size bytes).")
                    }
                    bridge.nativeInspectIso(descriptor.fd)
                } ?: throw IllegalStateException("O Android não conseguiu reabrir a ISO selecionada.")

                DiagnosticLog.log("PLAY_NATIVE_ISO_INSPECT", inspectionResult)
                if (!inspectionResult.startsWith("ISO_OK:")) {
                    throw IllegalArgumentException(inspectionResult)
                }

                val cacheDir = File(filesDir, "godhand_cache")
                if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                    throw IllegalStateException("Não foi possível criar o diretório privado: ${cacheDir.absolutePath}")
                }

                val cachePrefs = getSharedPreferences(PREFS, MODE_PRIVATE)
                val cacheUri = cachePrefs.getString(KEY_CACHE_URI, null)
                val requiredCacheFiles = listOf(
                    "SYSTEM.CNF",
                    "SLUS_215.03",
                    "_DATA.AFS",
                    "GODHAND.FST",
                    "MODULES/IRX.IMS",
                    "MODULES/IOPRP310.IMG",
                    "MODULES/SDRDRV.IRX"
                ).map { File(cacheDir, it) }
                val cacheReady = cacheUri == uri.toString() &&
                    requiredCacheFiles.all { it.isFile && it.length() > 0L }

                if (cacheReady) {
                    DiagnosticLog.log("ISO_CACHE", "Reusing complete cache for the selected ISO")
                    runOnUiThread { updateStatus("Arquivos do jogo já extraídos. Preparando o runtime...") }
                } else {
                    // When switching ISO, remove only extracted game data. Preserve
                    // shader cache and memory-card data stored in other cache entries.
                    if (cacheUri != uri.toString()) {
                        listOf("SYSTEM.CNF", "SLUS_215.03", "_DATA.AFS", "GODHAND.FST", "0FLISTDV.DIR")
                            .forEach { File(cacheDir, it).delete() }
                        File(cacheDir, "MODULES").deleteRecursively()
                    }

                    val descriptor = contentResolver.openFileDescriptor(uri, "r")
                        ?: throw IllegalStateException("O Android não conseguiu abrir a ISO para extração.")
                    val isoSize = descriptor.statSize
                    if (isoSize > 0 && isoSize < 1_048_576) {
                        descriptor.close()
                        throw IllegalArgumentException("A imagem tem menos de 1 MiB ($isoSize bytes).")
                    }
                    val ownedFd = descriptor.detachFd()
                    DiagnosticLog.log("ISO_EXTRACT", "Starting native extraction to ${cacheDir.absolutePath}; ISO size=$isoSize bytes")
                    val extracted = bridge.extractAndCacheIsoFromFd(ownedFd, cacheDir.absolutePath, this)
                    if (!extracted) {
                        throw IllegalStateException("O extrator nativo não conseguiu concluir a extração da ISO. Consulte o log.")
                    }

                    val missingFiles = requiredCacheFiles.filterNot { it.isFile && it.length() > 0L }
                    if (missingFiles.isNotEmpty()) {
                        throw IllegalStateException(
                            "Extração incompleta. Arquivos ausentes ou vazios: " +
                                missingFiles.joinToString { it.relativeTo(cacheDir).path }
                        )
                    }
                    cachePrefs.edit().putString(KEY_CACHE_URI, uri.toString()).apply()
                    DiagnosticLog.log("ISO_EXTRACT", "Extraction completed and required game files verified")
                }

                runOnUiThread { updateStatus("Arquivos extraídos. Preparando o índice de dados...") }
                bridge.buildAfsFastIndex(cacheDir.absolutePath)
                DiagnosticLog.log("AFS_INDEX", "AFS validation/index stage completed")
                val runtimeStatus = bridge.nativeRuntimeStatus()
                DiagnosticLog.log("PLAY_RUNTIME_STATUS", runtimeStatus)

                runOnUiThread {
                    launchInProgress = false
                    updateStatus("Arquivos preparados. Abrindo o runtime gráfico Android...")
                    startActivity(Intent(this, GameActivity::class.java))
                }
            } catch (error: Throwable) {
                DiagnosticLog.exception("PLAY_FAILED", error)
                runOnUiThread {
                    launchInProgress = false
                    updateStatus("Falha ao iniciar: ${error.javaClass.simpleName}: ${error.message}")
                    showError("A preparação ou inicialização falhou.", error.message)
                }
            }
        }, "GodHandIsoPrepare").start()
    }

    /** Called from JNI while the native ISO extractor reports progress. */
    @Suppress("unused")
    fun onNativeProgress(percent: Int, stage: String, message: String) {
        // The native extractor can report once per ISO file; update only when
        // the percentage changes so large ISOs do not flood the UI queue/log.
        if (percent == lastNativeProgressPercent) return
        lastNativeProgressPercent = percent
        DiagnosticLog.log("NATIVE_EXTRACT", "$percent% [$stage] $message")
        runOnUiThread {
            updateStatus("$percent% — $stage\n$message")
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index) ?: "arquivo.iso"
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "arquivo.iso"
    }

    private fun showDiagnosticMonitor() {
        DiagnosticLog.log("LOG_MONITOR", "Live diagnostic monitor opened")
        val logView = TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val scroll = ScrollView(this).apply {
            addView(logView)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Monitor de diagnóstico")
            .setView(scroll)
            .setPositiveButton("Fechar", null)
            .setNeutralButton("Atualizar", null)
            .setNegativeButton("Compartilhar", null)
            .create()

        val handler = Handler(Looper.getMainLooper())
        val refresh = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                logView.text = DiagnosticLog.readAll()
                scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                handler.postDelayed(this, 1000L)
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                logView.text = DiagnosticLog.readAll()
                scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                shareLog()
            }
            handler.post(refresh)
        }
        dialog.setOnDismissListener {
            handler.removeCallbacks(refresh)
            DiagnosticLog.log("LOG_MONITOR", "Live diagnostic monitor closed")
        }
        dialog.show()
    }

    private fun shareLog() {
        val text = DiagnosticLog.readAll()
        DiagnosticLog.log("LOG_EXPORT", "User requested diagnostic log export")
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "GodHand CRF1 - diagnóstico")
            putExtra(Intent.EXTRA_TEXT, text + "\n\n[LOG_EXPORT] Compartilhamento solicitado pelo usuário.\n")
        }
        try {
            startActivity(Intent.createChooser(sendIntent, "Exportar log de diagnóstico"))
        } catch (error: Exception) {
            DiagnosticLog.exception("LOG_EXPORT_FAILED", error)
            showError("Não foi possível abrir um app para compartilhar o log.", error.message)
        }
    }

    private fun clearStoredIso() {
        selectedIsoUri = null
        selectedIsoName = null
        selectedIsoText.text = "ISO: nenhuma selecionada"
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_URI).remove(KEY_NAME).apply()
        updateStatus("A ISO anterior não está acessível. Selecione novamente.")
    }

    private fun updateStatus(message: String) {
        if (::statusText.isInitialized) statusText.text = message
        DiagnosticLog.log("UI_STATUS", message)
    }

    private fun showError(title: String, detail: String?) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(detail ?: "Confira o log de diagnóstico para mais detalhes.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    companion object {
        private const val PREFS = "godhand_launcher"
        private const val KEY_URI = "selected_iso_uri"
        private const val KEY_NAME = "selected_iso_name"
        private const val KEY_CACHE_URI = "cached_iso_uri"
    }
}
