package com.crf1.godhand

/**
 * Loads the native library only after the user chooses an ISO and taps Play.
 * The current native implementation is a diagnostic probe, not the PS2 runtime.
 */
class RuntimeBridge {
    init {
        DiagnosticLog.log("NATIVE_LOAD", "Calling System.loadLibrary(godhand_android)")
        System.loadLibrary("godhand_android")
        DiagnosticLog.log("NATIVE_LOAD", "Native library loaded successfully")
    }

    external fun nativeRuntimeStatus(): String
}
