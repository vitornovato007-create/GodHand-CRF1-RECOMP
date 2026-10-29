package com.crf1.godhand

import android.view.Surface

/**
 * JNI bridge to the integrated God Hand Android runtime.
 *
 * The native library contains the EE recompilation, SNR2 module registry,
 * Android surface bridge and PCSX2 GS graphics backend.
 */
class RuntimeBridge {
    init {
        DiagnosticLog.log("NATIVE_LOAD", "Loading integrated God Hand ARM64 runtime")
        System.loadLibrary("godhand_android")
        DiagnosticLog.log("NATIVE_LOAD", "Integrated JNI library loaded")
    }

    external fun nativeRuntimeStatus(): String

    /** Inspect the ISO9660 root using a seekable open file descriptor. */
    external fun nativeInspectIso(fileDescriptor: Int): String

    /**
     * Extract the game data needed by the runtime. Native code takes ownership
     * of fileDescriptor and closes it before returning.
     *
     * progressListener must expose onNativeProgress(Int, String, String).
     */
    external fun extractAndCacheIsoFromFd(
        fileDescriptor: Int,
        outCacheDir: String,
        progressListener: Any
    ): Boolean

    external fun buildAfsFastIndex(cacheDir: String)

    external fun unpackImsArchive(cacheDir: String): Boolean

    external fun setAudioMuted(muted: Boolean)

    external fun setAudioVolume(volume: Float)

    external fun setAudioConfig(schedulingIndex: Int, bufferSizeIndex: Int)

    /** Start the runtime or attach it to a recreated Android surface. */
    external fun onSurfaceCreated(
        surface: Surface,
        width: Int,
        height: Int,
        rendererIndex: Int,
        resolutionScaleIndex: Int,
        aspectRatioIndex: Int,
        fpsIndex: Int,
        frameskipIndex: Int
    )

    external fun onSurfaceDestroyed()

    external fun updatePadState(
        buttonMask: Int,
        leftX: Float,
        leftY: Float,
        rightX: Float,
        rightY: Float
    )
}
