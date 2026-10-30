#include <jni.h>
#include <android/log.h>

#include <cerrno>
#include <cstdarg>
#include <csignal>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <time.h>
#include <ucontext.h>

namespace {
constexpr const char* kTag = "GodHandCrash";
volatile sig_atomic_t g_crashLogFd = -1;
volatile sig_atomic_t g_handlersInstalled = 0;
std::mutex g_nativeLogMutex;
constexpr off_t kMaxNativeLogBytes = 2 * 1024 * 1024;
struct sigaction g_previousActions[5]{};
constexpr int kWatchedSignals[] = {SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE};

void appendUnsigned(int fd, unsigned int value) {
    char digits[16];
    size_t count = 0;
    do {
        digits[count++] = static_cast<char>('0' + (value % 10U));
        value /= 10U;
    } while (value != 0U && count < sizeof(digits));
    while (count > 0) {
        const char digit = digits[--count];
        (void)write(fd, &digit, 1);
    }
}

void appendHex(int fd, uintptr_t value) {
    static constexpr char digits[] = "0123456789abcdef";
    char buffer[2 * sizeof(uintptr_t)];
    size_t count = 0;
    do {
        buffer[count++] = digits[value & 0xFU];
        value >>= 4U;
    } while (value != 0U && count < sizeof(buffer));
    while (count > 0) {
        const char digit = buffer[--count];
        (void)write(fd, &digit, 1);
    }
}

int signalIndex(int signalNumber) {
    for (int i = 0; i < 5; ++i) {
        if (kWatchedSignals[i] == signalNumber) return i;
    }
    return -1;
}

void nativeFatalSignalHandler(int signalNumber, siginfo_t* info, void* context) {
    const int fd = static_cast<int>(g_crashLogFd);
    if (fd >= 0) {
        static constexpr char prefix[] = "\n========== NATIVE FATAL SIGNAL ==========\nSignal: ";
        static constexpr char address[] = "\nFault address: 0x";
        static constexpr char codeLabel[] = "\nSignal code: ";
        static constexpr char tidLabel[] = "\nThread ID: ";
        static constexpr char pcLabel[] = "\nProgram counter (PC): 0x";
        static constexpr char lrLabel[] = "\nLink register (LR): 0x";
        static constexpr char spLabel[] = "\nStack pointer (SP): 0x";
        static constexpr char suffix[] = "\nAndroid's original fatal-signal handler will now receive this signal.\n";
        (void)write(fd, prefix, sizeof(prefix) - 1);
        appendUnsigned(fd, static_cast<unsigned int>(signalNumber));
        if (info != nullptr) {
            (void)write(fd, codeLabel, sizeof(codeLabel) - 1);
            appendUnsigned(fd, static_cast<unsigned int>(info->si_code));
            (void)write(fd, address, sizeof(address) - 1);
            appendHex(fd, reinterpret_cast<uintptr_t>(info->si_addr));
        }
        (void)write(fd, tidLabel, sizeof(tidLabel) - 1);
        appendUnsigned(fd, static_cast<unsigned int>(syscall(SYS_gettid)));
#if defined(__aarch64__)
        if (context != nullptr) {
            const auto* machine = static_cast<const ucontext_t*>(context);
            (void)write(fd, pcLabel, sizeof(pcLabel) - 1);
            appendHex(fd, static_cast<uintptr_t>(machine->uc_mcontext.pc));
            (void)write(fd, lrLabel, sizeof(lrLabel) - 1);
            appendHex(fd, static_cast<uintptr_t>(machine->uc_mcontext.regs[30]));
            (void)write(fd, spLabel, sizeof(spLabel) - 1);
            appendHex(fd, static_cast<uintptr_t>(machine->uc_mcontext.sp));
        }
#endif
        (void)write(fd, suffix, sizeof(suffix) - 1);
        // write() is async-signal-safe. fsync is used only to make this short
        // fatal record durable before chaining to Android's debuggerd handler.
        (void)fsync(fd);
    }

    const int index = signalIndex(signalNumber);
    if (index >= 0) {
        const struct sigaction previous = g_previousActions[index];
        if ((previous.sa_flags & SA_SIGINFO) != 0 && previous.sa_sigaction != nullptr) {
            previous.sa_sigaction(signalNumber, info, context);
            return;
        }
        if (previous.sa_handler != nullptr &&
            previous.sa_handler != SIG_DFL &&
            previous.sa_handler != SIG_IGN) {
            previous.sa_handler(signalNumber);
            return;
        }
        if (previous.sa_handler == SIG_IGN) return;
    }
    // Preserve the system's default disposition if no prior Android handler
    // was installed. Do not replace debuggerd's handler when it exists.
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

    // Native messages may arrive from several runtime threads. Serialize the
    // ordinary logger writes so timestamp prefixes and messages cannot interleave.
    // The fatal-signal handler intentionally does not take this mutex because
    // mutex operations are not async-signal-safe.
    const std::lock_guard<std::mutex> guard(g_nativeLogMutex);

    struct stat current {};
    if (fstat(fd, &current) == 0 &&
        current.st_size + static_cast<off_t>(length) + 128 > kMaxNativeLogBytes) {
        // Keep the native journal bounded without replacing its open descriptor:
        // DiagnosticLog.clear() and the crash handler continue using this inode.
        if (ftruncate(fd, 0) == 0) {
            static constexpr char rollover[] =
                "[NATIVE_DIAGNOSTICS] Log limit reached; previous native records were rotated out.\n";
            (void)write(fd, rollover, sizeof(rollover) - 1);
        }
    }

    struct timespec now {};
    if (clock_gettime(CLOCK_MONOTONIC, &now) == 0) {
        static constexpr char timePrefix[] = "[native monotonic=";
        static constexpr char separator[] = ".";
        static constexpr char threadPrefix[] = " tid=";
        static constexpr char closePrefix[] = "] ";
        (void)write(fd, timePrefix, sizeof(timePrefix) - 1);
        appendUnsigned(fd, static_cast<unsigned int>(now.tv_sec));
        (void)write(fd, separator, sizeof(separator) - 1);
        char nanos[9];
        long value = now.tv_nsec;
        for (int i = 8; i >= 0; --i) {
            nanos[i] = static_cast<char>('0' + (value % 10));
            value /= 10;
        }
        (void)write(fd, nanos, sizeof(nanos));
        (void)write(fd, threadPrefix, sizeof(threadPrefix) - 1);
        appendUnsigned(fd, static_cast<unsigned int>(syscall(SYS_gettid)));
        (void)write(fd, closePrefix, sizeof(closePrefix) - 1);
    }

    (void)write(fd, message, length);
    (void)write(fd, "\n", 1);
}
}  // namespace

extern "C" void godhand_native_logf(const char* format, ...) {
    if (format == nullptr) return;
    char buffer[2048];
    va_list args;
    va_start(args, format);
    const int count = vsnprintf(buffer, sizeof(buffer), format, args);
    va_end(args);
    if (count > 0) {
        const size_t length = static_cast<size_t>(count) < sizeof(buffer)
            ? static_cast<size_t>(count) : sizeof(buffer) - 1;
        writeNativeLine(buffer, length);
    }
}

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

    if (!g_handlersInstalled) {
        struct sigaction action {};
        action.sa_sigaction = nativeFatalSignalHandler;
        sigemptyset(&action.sa_mask);
        action.sa_flags = SA_SIGINFO | SA_NODEFER | SA_ONSTACK;

        for (int i = 0; i < 5; ++i) {
            if (sigaction(kWatchedSignals[i], &action, &g_previousActions[i]) != 0) {
                __android_log_print(ANDROID_LOG_ERROR, kTag,
                                    "Could not install chained handler for signal %d: errno=%d",
                                    kWatchedSignals[i], errno);
            }
        }
        g_handlersInstalled = 1;
    }

    static constexpr char ready[] =
        "\n[NATIVE_DIAGNOSTICS] Persistent native crash logger installed; previous Android handlers saved.\n";
    (void)write(fd, ready, sizeof(ready) - 1);
    (void)fsync(fd);
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
