#include <jni.h>
#include <android/log.h>

#include <cerrno>
#include <csignal>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <unistd.h>
#include <sys/syscall.h>
#include <sys/types.h>

namespace {
constexpr const char* kTag = "GodHandCrash";
constexpr char kCrashPrefix[] = "\n========== NATIVE FATAL SIGNAL ==========\nSignal: ";
constexpr char kCrashSuffix[] = "\nThe process will be re-raised so Android can create its native tombstone.\n";

volatile sig_atomic_t g_crashLogFd = -1;

void appendSignalNumber(int fd, int signalNumber) {
    char digits[16];
    size_t count = 0;
    unsigned int value = signalNumber < 0
        ? static_cast<unsigned int>(-signalNumber)
        : static_cast<unsigned int>(signalNumber);
    do {
        digits[count++] = static_cast<char>('0' + (value % 10U));
        value /= 10U;
    } while (value != 0U && count < sizeof(digits));

    while (count > 0) {
        const char digit = digits[--count];
        (void)write(fd, &digit, 1);
    }
}

void nativeFatalSignalHandler(int signalNumber, siginfo_t* info, void*) {
    const int fd = static_cast<int>(g_crashLogFd);
    if (fd >= 0) {
        (void)write(fd, kCrashPrefix, sizeof(kCrashPrefix) - 1);
        appendSignalNumber(fd, signalNumber);

        if (info != nullptr) {
            const char addressLabel[] = "\nFault address: 0x";
            (void)write(fd, addressLabel, sizeof(addressLabel) - 1);
            uintptr_t address = reinterpret_cast<uintptr_t>(info->si_addr);
            char hex[2 * sizeof(uintptr_t)];
            size_t count = 0;
            do {
                const unsigned int nibble = static_cast<unsigned int>(address & 0xFU);
                hex[count++] = static_cast<char>(nibble < 10U ? '0' + nibble : 'a' + (nibble - 10U));
                address >>= 4U;
            } while (address != 0U && count < sizeof(hex));
            while (count > 0) {
                const char digit = hex[--count];
                (void)write(fd, &digit, 1);
            }
        }

        (void)write(fd, kCrashSuffix, sizeof(kCrashSuffix) - 1);
    }

    struct sigaction defaultAction {};
    defaultAction.sa_handler = SIG_DFL;
    sigemptyset(&defaultAction.sa_mask);
    (void)sigaction(signalNumber, &defaultAction, nullptr);
    (void)syscall(SYS_tgkill, getpid(), gettid(), signalNumber);
    _exit(128 + signalNumber);
}

void writeNativeLine(const char* message, size_t length) {
    const int fd = static_cast<int>(g_crashLogFd);
    if (fd < 0 || message == nullptr || length == 0) return;
    (void)write(fd, message, length);
    (void)write(fd, "\n", 1);
}
}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_crf1_godhand_RuntimeBridge_nativeConfigureCrashLog(
    JNIEnv* env, jobject, jstring path) {
    if (path == nullptr) return;
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) return;

    const int fd = open(chars, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
    env->ReleaseStringUTFChars(path, chars);
    if (fd < 0) {
        __android_log_print(ANDROID_LOG_ERROR, kTag,
                            "Could not open persistent native crash log: errno=%d", errno);
        return;
    }

    const int oldFd = static_cast<int>(g_crashLogFd);
    g_crashLogFd = fd;
    if (oldFd >= 0 && oldFd != fd) close(oldFd);

    const int watchedSignals[] = {SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE};
    struct sigaction action {};
    action.sa_sigaction = nativeFatalSignalHandler;
    sigemptyset(&action.sa_mask);
    action.sa_flags = SA_SIGINFO | SA_RESETHAND;

    for (int signalNumber : watchedSignals) {
        if (sigaction(signalNumber, &action, nullptr) != 0) {
            __android_log_print(ANDROID_LOG_ERROR, kTag,
                                "Could not install handler for signal %d: errno=%d",
                                signalNumber, errno);
        }
    }

    static constexpr char ready[] =
        "\n[NATIVE_DIAGNOSTICS] Persistent native crash logger installed.\n";
    (void)write(fd, ready, sizeof(ready) - 1);
    __android_log_print(ANDROID_LOG_INFO, kTag,
                        "Persistent native crash logger installed");
}

extern "C" JNIEXPORT void JNICALL
Java_com_crf1_godhand_RuntimeBridge_nativeWriteDiagnostic(
    JNIEnv* env, jobject, jstring message) {
    if (message == nullptr) return;
    const char* chars = env->GetStringUTFChars(message, nullptr);
    if (chars == nullptr) return;
    writeNativeLine(chars, std::strlen(chars));
    env->ReleaseStringUTFChars(message, chars);
}
