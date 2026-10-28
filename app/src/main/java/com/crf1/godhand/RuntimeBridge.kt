package com.crf1.godhand

/**
 * JNI bridge to the Android build of the official PS2 runtime core.
 *
 * The runtime core alone is not a playable port: God Hand's ISO-specific
 * recompiled C++ sources must also be generated and linked before booting.
 */
class RuntimeBridge {
    init {
        DiagnosticLog.log("NATIVE_LOAD", "Loading ARM64 JNI library linked with the official PS2 runtime core")
        System.loadLibrary("godhand_android")
        DiagnosticLog.log("NATIVE_LOAD", "JNI library and linked runtime core loaded successfully")
    }

    external fun nativeRuntimeStatus(): String

    /** Inspect the ISO9660 header and God Hand boot files using an open file descriptor. */
    external fun nativeInspectIso(fileDescriptor: Int): String
}
