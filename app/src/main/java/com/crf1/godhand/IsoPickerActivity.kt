package com.crf1.godhand

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.Locale

/**
 * Launcher flow: select a user's ISO first; only then can the native library be loaded.
 * This intentionally reports that the game runtime is not integrated yet.
 */
class IsoPickerActivity : ComponentActivity() {
    private lateinit var selectedIsoText: TextView
    private lateinit var statusText: TextView
    private var selectedIsoUri: Uri? = null
    private var selectedIsoName: String? = null

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
        DiagnosticLog.log("APP_START", "IsoPickerActivity.onCreate; Android ${android.os.Build.VERSION.RELEASE}; ABI=${android.os.Build.SUPPORTED_ABIS.joinToString()}")
        buildUi()
        restoreSelectedIso()
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
            text = "\nAviso: esta versão ainda não contém o runtime PS2 portado. O log indicará essa condição em vez de fingir que o jogo iniciou."
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
        DiagnosticLog.log("PLAY_CLICK", "User tapped Play")
        val uri = selectedIsoUri
        if (uri == null) {
            DiagnosticLog.log("PLAY_ABORTED", "No ISO selected")
            showError("Primeiro selecione a ISO do jogo.", null)
            return
        }

        try {
            DiagnosticLog.log("PLAY_VALIDATE", "Rechecking selected ISO: ${selectedIsoName ?: uri}")
            val bridge = RuntimeBridge()
            val inspectionResult = contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                val size = descriptor.statSize
                DiagnosticLog.log("PLAY_VALIDATE", "ISO descriptor opened; size=$size bytes; fd=${descriptor.fd}")
                if (size > 0 && size < 1_048_576) {
                    throw IllegalArgumentException("A imagem tem menos de 1 MiB ($size bytes).")
                }
                DiagnosticLog.log("PLAY_NATIVE_ISO_INSPECT", "Checking ISO9660 and expected God Hand boot files")
                bridge.nativeInspectIso(descriptor.fd)
            } ?: throw IllegalStateException("O Android não conseguiu reabrir a ISO selecionada.")

            DiagnosticLog.log("PLAY_NATIVE_RESULT", inspectionResult)
            val runtimeStatus = bridge.nativeRuntimeStatus()
            DiagnosticLog.log("PLAY_RUNTIME_STATUS", runtimeStatus)
            updateStatus(inspectionResult)
            val isExpectedDisc = inspectionResult.startsWith("ISO_OK:")
            AlertDialog.Builder(this)
                .setTitle(if (isExpectedDisc) "Runtime integrado parcialmente" else "ISO não reconhecida")
                .setMessage(
                    "$inspectionResult\n\nEstado do runtime: $runtimeStatus\n\nA verificação confirma a estrutura do disco; ela não inicia o jogo enquanto o código recompilado específico de God Hand não estiver gerado e ligado ao APK."
                )
                .setPositiveButton("OK", null)
                .show()
        } catch (error: Throwable) {
            DiagnosticLog.exception("PLAY_FAILED", error)
            updateStatus("Falha na inicialização: ${error.javaClass.simpleName}: ${error.message}")
            showError("A inicialização falhou. O erro foi gravado no log.", error.message)
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
    }
}
