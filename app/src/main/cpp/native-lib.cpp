#include <jni.h>
#include <android/log.h>
#include "ps2_runtime.h"

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>
#include <unistd.h>

#define GH_LOG_TAG "GodHandCRF1"

namespace {

bool readAt(int fd, void* destination, size_t size, off_t offset) {
    auto* output = static_cast<unsigned char*>(destination);
    size_t total = 0;
    while (total < size) {
        const ssize_t count = pread(fd, output + total, size - total,
                                    offset + static_cast<off_t>(total));
        if (count <= 0) {
            return false;
        }
        total += static_cast<size_t>(count);
    }
    return true;
}

uint32_t readLe32(const unsigned char* data) {
    return static_cast<uint32_t>(data[0]) |
           (static_cast<uint32_t>(data[1]) << 8U) |
           (static_cast<uint32_t>(data[2]) << 16U) |
           (static_cast<uint32_t>(data[3]) << 24U);
}

std::string upperAscii(std::string value) {
    for (char& character : value) {
        if (character >= 'a' && character <= 'z') {
            character = static_cast<char>(character - ('a' - 'A'));
        }
    }
    return value;
}

struct IsoFile {
    uint32_t lba = 0;
    uint32_t size = 0;
    bool found = false;
};

IsoFile findRootFile(const std::vector<unsigned char>& directory,
                     const std::string& wantedName) {
    size_t cursor = 0;
    while (cursor < directory.size()) {
        const unsigned char recordLength = directory[cursor];
        if (recordLength == 0) {
            cursor = ((cursor / 2048U) + 1U) * 2048U;
            continue;
        }
        if (recordLength < 34 || cursor + recordLength > directory.size()) {
            break;
        }

        const unsigned char* record = directory.data() + cursor;
        const unsigned char nameLength = record[32];
        if (33U + nameLength <= recordLength) {
            std::string name(reinterpret_cast<const char*>(record + 33), nameLength);
            const size_t version = name.find(';');
            if (version != std::string::npos) {
                name.erase(version);
            }
            if (upperAscii(name) == wantedName) {
                return IsoFile{readLe32(record + 2), readLe32(record + 10), true};
            }
        }
        cursor += recordLength;
    }
    return {};
}

std::string inspectIso(int fd) {
    if (fd < 0) {
        return "ISO_INVALID: descritor de arquivo inválido.";
    }

    unsigned char pvd[2048]{};
    if (!readAt(fd, pvd, sizeof(pvd), 16 * 2048)) {
        return "ISO_UNREADABLE: não foi possível ler o descritor ISO9660.";
    }
    if (pvd[0] != 1 || std::memcmp(pvd + 1, "CD001", 5) != 0 || pvd[6] != 1) {
        return "ISO_INVALID: o arquivo não contém um descritor primário ISO9660 válido.";
    }

    const unsigned char* rootRecord = pvd + 156;
    if (rootRecord[0] < 34) {
        return "ISO_INVALID: diretório raiz ISO9660 inválido.";
    }
    const uint32_t rootLba = readLe32(rootRecord + 2);
    const uint32_t rootSize = readLe32(rootRecord + 10);
    if (rootSize == 0 || rootSize > 1024U * 1024U) {
        return "ISO_INVALID: tamanho do diretório raiz fora do limite esperado.";
    }

    std::vector<unsigned char> directory(rootSize);
    if (!readAt(fd, directory.data(), directory.size(),
                static_cast<off_t>(rootLba) * 2048)) {
        return "ISO_UNREADABLE: não foi possível ler o diretório raiz.";
    }

    const IsoFile systemCnf = findRootFile(directory, "SYSTEM.CNF");
    const IsoFile bootElf = findRootFile(directory, "SLUS_215.03");
    if (!systemCnf.found || !bootElf.found) {
        return "WRONG_GAME_DISC: SYSTEM.CNF ou SLUS_215.03 não encontrado na raiz. "
               "O runtime oficial espera God Hand USA (SLUS-21503).";
    }
    if (systemCnf.size == 0 || systemCnf.size > 4096) {
        return "ISO_INVALID: tamanho inesperado de SYSTEM.CNF.";
    }

    std::vector<char> systemText(systemCnf.size + 1U, '\0');
    if (!readAt(fd, systemText.data(), systemCnf.size,
                static_cast<off_t>(systemCnf.lba) * 2048)) {
        return "ISO_UNREADABLE: SYSTEM.CNF foi encontrado, mas não pôde ser lido.";
    }
    const std::string bootConfig = upperAscii(std::string(systemText.data()));
    if (bootConfig.find("BOOT2") == std::string::npos ||
        bootConfig.find("SLUS_215.03") == std::string::npos) {
        return "WRONG_GAME_DISC: SYSTEM.CNF não aponta para SLUS_215.03.";
    }

    __android_log_print(ANDROID_LOG_INFO, GH_LOG_TAG,
                        "ISO9660 validada: SYSTEM.CNF e SLUS_215.03 encontrados.");
#if defined(GODHAND_UPSTREAM_RUNTIME_LINKED) && defined(GODHAND_GAME_CODE_LINKED)
    return "ISO_OK: God Hand USA (SLUS-21503) detectado.\n"
           "RUNTIME_CORE_LINKED: núcleo PS2 oficial incluído na biblioteca ARM64.\n"
           "GAME_CODE_LINKED: fontes C++ recompiladas e tabela de funções encontradas no build. "
           "A inicialização completa ainda depende da integração do caminho de boot e do backend gráfico.";
#elif defined(GODHAND_UPSTREAM_RUNTIME_LINKED)
    return "ISO_OK: God Hand USA (SLUS-21503) detectado.\n"
           "RUNTIME_CORE_LINKED: núcleo PS2 oficial incluído na biblioteca ARM64.\n"
           "GAME_CODE_MISSING: não foram encontrados fontes C++ recompilados e register_functions.cpp "
           "em app/src/main/cpp/generated/. O APK ainda não pode executar o jogo.";
#else
    return "ISO_OK: God Hand USA detectado, mas o núcleo PS2 oficial não foi ligado.";
#endif
}

}  // namespace

// raylib's Android platform object references a conventional main() even when
// its static archive is linked into this app's JNI library. This app is a normal
// Activity/JNI application (not a NativeActivity), so this entry point is only
// a link-time compatibility stub and is never used to launch the game.
int main(int argc, char** argv) {
    (void)argc;
    (void)argv;
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_crf1_godhand_MainActivity_nativeBuildStatus(JNIEnv* env, jobject /* thiz */) {
#if defined(__aarch64__)
    constexpr const char* status = "Biblioteca JNI carregada: ARM64 (aarch64).";
#else
    constexpr const char* status = "Biblioteca JNI carregada: arquitetura diferente de ARM64.";
#endif
    return env->NewStringUTF(status);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_crf1_godhand_RuntimeBridge_nativeRuntimeStatus(JNIEnv* env, jobject /* thiz */) {
    // This call forces the official PS2Runtime implementation to be pulled from
    // the static archive into libgodhand_android.so; merely linking the target
    // would otherwise allow the linker to discard every unused archive object.
    const PS2Runtime::IoPaths& paths = PS2Runtime::getIoPaths();
    (void)paths;
#if defined(GODHAND_UPSTREAM_RUNTIME_LINKED) && defined(GODHAND_GAME_CODE_LINKED) && defined(__aarch64__)
    constexpr const char* status =
        "RUNTIME_CORE_LINKED: núcleo PS2 oficial ligado à biblioteca ARM64. "
        "GAME_CODE_LINKED: fontes geradas e tabela de funções foram incluídas no build; "
        "a inicialização e o backend gráfico ainda precisam ser validados.";
#elif defined(GODHAND_UPSTREAM_RUNTIME_LINKED) && defined(__aarch64__)
    constexpr const char* status =
        "RUNTIME_CORE_LINKED: núcleo PS2 oficial ligado à biblioteca ARM64. "
        "GAME_CODE_MISSING: fontes geradas e register_functions.cpp ainda não foram incluídos.";
#else
    constexpr const char* status =
        "RUNTIME_NOT_INTEGRATED: o núcleo PS2 oficial não está ligado à biblioteca ARM64.";
#endif
    __android_log_print(ANDROID_LOG_INFO, GH_LOG_TAG, "%s", status);
    return env->NewStringUTF(status);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_crf1_godhand_RuntimeBridge_nativeInspectIso(
    JNIEnv* env, jobject /* thiz */, jint fileDescriptor) {
    const std::string result = inspectIso(static_cast<int>(fileDescriptor));
    __android_log_print(ANDROID_LOG_INFO, GH_LOG_TAG, "%s", result.c_str());
    return env->NewStringUTF(result.c_str());
}
