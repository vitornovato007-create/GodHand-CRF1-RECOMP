package com.crf1.godhand

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity

/**
 * Android host surface for the native PCSX2 GS backend.
 *
 * The native runtime owns the rendering/game thread. Surface callbacks only
 * attach, resize or detach the ANativeWindow used by that backend.
 */
class GameActivity : ComponentActivity(), SurfaceHolder.Callback {
    private lateinit var gameSurface: SurfaceView
    private lateinit var bridge: RuntimeBridge
    private var nativeSurfaceAttached = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        DiagnosticLog.log("GAME_ACTIVITY", "Creating native rendering surface")

        bridge = RuntimeBridge()
        gameSurface = SurfaceView(this).apply {
            holder.addCallback(this@GameActivity)
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        setContentView(FrameLayout(this).apply {
            addView(
                gameSurface,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        })
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        DiagnosticLog.log("GS_SURFACE", "Surface created; waiting for valid dimensions")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0 || !holder.surface.isValid) {
            DiagnosticLog.log("GS_SURFACE", "Ignoring invalid surface dimensions ${width}x${height}")
            return
        }

        try {
            DiagnosticLog.log(
                "GAME_BOOT",
                "Attaching Android surface ${width}x${height}; renderer=Vulkan, scale=0.75x, aspect=fullscreen, fps=30"
            )
            // Renderer index 0 = Vulkan; resolution scale index 1 = 0.75x;
            // aspect index 2 = device fullscreen; fps index 1 = 30 FPS.
            bridge.onSurfaceCreated(
                holder.surface,
                width,
                height,
                0,
                1,
                2,
                1,
                0
            )
            nativeSurfaceAttached = true
        } catch (error: Throwable) {
            DiagnosticLog.exception("GAME_BOOT_FAILED", error)
            runOnUiThread {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Falha ao iniciar o jogo")
                    .setMessage(error.message ?: error.javaClass.simpleName)
                    .setPositiveButton("OK") { _, _ -> finish() }
                    .show()
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        DiagnosticLog.log("GS_SURFACE", "Android rendering surface destroyed")
        if (nativeSurfaceAttached) {
            try {
                bridge.onSurfaceDestroyed()
            } catch (error: Throwable) {
                DiagnosticLog.exception("GS_SURFACE_DESTROY_FAILED", error)
            }
            nativeSurfaceAttached = false
        }
    }

    override fun onBackPressed() {
        DiagnosticLog.log("GAME_ACTIVITY", "Back pressed; closing game surface")
        super.onBackPressed()
    }
}
