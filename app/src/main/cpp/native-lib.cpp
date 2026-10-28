#include <jni.h>

extern "C" JNIEXPORT jstring JNICALL
Java_com_crf1_godhand_MainActivity_nativeBuildStatus(JNIEnv* env, jobject /* thiz */) {
#if defined(__aarch64__)
    constexpr const char* status = "Biblioteca JNI carregada: ARM64 (aarch64).";
#else
    constexpr const char* status = "Biblioteca JNI carregada: arquitetura diferente de ARM64.";
#endif
    return env->NewStringUTF(status);
}
