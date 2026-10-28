#include <jni.h>
#include <android/log.h>

#define GH_LOG_TAG "GodHandCRF1"

extern "C" JNIEXPORT jstring JNICALL
Java_com_crf1_godhand_MainActivity_nativeBuildStatus(JNIEnv* env, jobject /* thiz */) {
#if defined(__aarch64__)
    constexpr const char* status = "Biblioteca JNI carregada: ARM64 (aarch64).";
#else
    constexpr const char* status = "Biblioteca JNI carregada: arquitetura diferente de ARM64.";
#endif
    return env->NewStringUTF(status);
}

/*
 * This probe deliberately does not pretend to start the game. The upstream
 * project is currently a Windows desktop port; its PS2 runtime and generated
 * game code have not been ported/linked into this Android bootstrap yet.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_crf1_godhand_RuntimeBridge_nativeRuntimeStatus(JNIEnv* env, jobject /* thiz */) {
#if defined(__aarch64__)
    __android_log_print(ANDROID_LOG_INFO, GH_LOG_TAG,
                        "Runtime probe called on ARM64; game runtime is not linked yet.");
    constexpr const char* status =
        "RUNTIME_NOT_INTEGRATED: a biblioteca ARM64 foi carregada, mas o runtime PS2 "
        "e o código recompilado de God Hand ainda não estão integrados.";
#else
    __android_log_print(ANDROID_LOG_ERROR, GH_LOG_TAG,
                        "Runtime probe called on a non-ARM64 ABI.");
    constexpr const char* status =
        "ABI_INCOMPATIVEL: este APK foi preparado somente para arm64-v8a.";
#endif
    return env->NewStringUTF(status);
}
