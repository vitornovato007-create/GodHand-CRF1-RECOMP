cmake_minimum_required(VERSION 3.22.1)
project(GodHandCRF1Android LANGUAGES C CXX)

# Keep full DWARF data while linking Release so a matching external symbol file
# can be generated before stripping the APK copy.
add_compile_options($<$<CONFIG:Release>:-g>)
add_link_options($<$<CONFIG:Release>:-Wl,--build-id=sha1>)

# This app now builds the complete, pinned Android port implementation rather
# than linking only the desktop runtime and reporting that boot is unavailable.
# The workflow checks out the reference C++ tree before Gradle configures CMake.
get_filename_component(GODHAND_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
set(GODHAND_REFERENCE_CPP_DIR
    "${GODHAND_REPO_ROOT}/external/GodHandAndroidPort/app/src/main/cpp")

foreach(_required_file IN ITEMS
    "${GODHAND_REFERENCE_CPP_DIR}/CMakeLists.txt"
    "${GODHAND_REFERENCE_CPP_DIR}/jni_bridge.cpp"
    "${GODHAND_REFERENCE_CPP_DIR}/src/main.cpp"
    "${GODHAND_REFERENCE_CPP_DIR}/generated/register_functions.cpp"
    "${GODHAND_REFERENCE_CPP_DIR}/generated_unity"
    "${GODHAND_REFERENCE_CPP_DIR}/generated_modules_unity"
    "${GODHAND_REFERENCE_CPP_DIR}/thirdparty/oboe/CMakeLists.txt"
    "${GODHAND_REFERENCE_CPP_DIR}/thirdparty/simde/simde")
    if(NOT EXISTS "${_required_file}")
        message(FATAL_ERROR
            "God Hand Android reference component is missing: ${_required_file}. "
            "Run the manual Android workflow's pinned reference checkout step.")
    endif()
endforeach()

# The reference bridge was authored under a different Android application ID.
# Rewrite only its JNI symbol prefix so the current app can call the real bridge.
set(_godhand_jni_bridge "${GODHAND_REFERENCE_CPP_DIR}/jni_bridge.cpp")
file(READ "${_godhand_jni_bridge}" _godhand_jni_source)
set(_godhand_jni_old_prefix "Java_com_owaissseeee_godhand_NativeBridge_")
set(_godhand_jni_new_prefix "Java_com_crf1_godhand_RuntimeBridge_")
string(FIND "${_godhand_jni_source}" "${_godhand_jni_old_prefix}onSurfaceCreated" _godhand_jni_old_match)
string(FIND "${_godhand_jni_source}" "${_godhand_jni_new_prefix}onSurfaceCreated" _godhand_jni_new_match)
if(NOT _godhand_jni_old_match EQUAL -1)
    string(REPLACE "${_godhand_jni_old_prefix}" "${_godhand_jni_new_prefix}"
        _godhand_jni_source "${_godhand_jni_source}")
elseif(_godhand_jni_new_match EQUAL -1)
    message(FATAL_ERROR
        "Pinned Android bridge JNI names changed; review the integration before building.")
endif()
# Persist all Android bridge log levels to the native journal as well as logcat.
set(_godhand_log_decl "extern \\"C\\" void godhand_native_logf(const char*, ...);")
string(REPLACE "#define LOGI(...)" "\${_godhand_log_decl}\n#define LOGI(...)" _godhand_jni_source "\${_godhand_jni_source}")
string(REPLACE
    "#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, \\"GodHandRecomp\\", __VA_ARGS__)"
    "#define LOGI(...) do { __android_log_print(ANDROID_LOG_INFO, \\"GodHandRecomp\\", __VA_ARGS__); godhand_native_logf(__VA_ARGS__); } while (0)"
    _godhand_jni_source "\${_godhand_jni_source}")
string(REPLACE
    "#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, \\"GodHandRecomp\\", __VA_ARGS__)"
    "#define LOGE(...) do { __android_log_print(ANDROID_LOG_ERROR, \\"GodHandRecomp\\", __VA_ARGS__); godhand_native_logf(__VA_ARGS__); } while (0)"
    _godhand_jni_source "\${_godhand_jni_source}")
string(REPLACE
    "#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, \\"GodHandRecomp\\", __VA_ARGS__)"
    "#define LOGW(...) do { __android_log_print(ANDROID_LOG_WARN, \\"GodHandRecomp\\", __VA_ARGS__); godhand_native_logf(__VA_ARGS__); } while (0)"
    _godhand_jni_source "\${_godhand_jni_source}")
string(REPLACE
    "__android_log_print(ANDROID_LOG_INFO, \\"GodHandNative\\", \\"%s\\", line.c_str());"
    "__android_log_print(ANDROID_LOG_INFO, \\"GodHandNative\\", \\"%s\\", line.c_str());\n                            godhand_native_logf(\\"%s\\", line.c_str());"
    _godhand_jni_source "\${_godhand_jni_source}")
file(WRITE "\${_godhand_jni_bridge}" "\${_godhand_jni_source}")

# The upstream Android boot path has historical package IDs in its cache list.
# Add this app's private cache first; it is where the ISO extraction JNI method
# writes SLUS_215.03 and MODULES/IRX.IMS.
set(_godhand_main_cpp "${GODHAND_REFERENCE_CPP_DIR}/src/main.cpp")
file(READ "${_godhand_main_cpp}" _godhand_main_source)
set(_godhand_cache_anchor [=[
    static const char* const s_cacheCandidates[] = {
]=])
set(_godhand_cache_replacement [=[
    static const char* const s_cacheCandidates[] = {
        "/data/data/com.crf1.godhand/files/godhand_cache",
        "/data/user/0/com.crf1.godhand/files/godhand_cache",
]=])
string(FIND "${_godhand_main_source}" "/data/data/com.crf1.godhand/files/godhand_cache" _godhand_cache_already_patched)
if(_godhand_cache_already_patched EQUAL -1)
    string(FIND "${_godhand_main_source}" "${_godhand_cache_anchor}" _godhand_cache_anchor_match)
    if(_godhand_cache_anchor_match EQUAL -1)
        message(FATAL_ERROR
            "Could not locate the Android game-cache candidates in the pinned reference src/main.cpp.")
    endif()
    string(REPLACE "${_godhand_cache_anchor}" "${_godhand_cache_replacement}"
        _godhand_main_source "${_godhand_main_source}")
endif()
file(WRITE "${_godhand_main_cpp}" "${_godhand_main_source}")

# The reference was tuned for a newer Cortex-A78 phone. Keep the build usable on
# older ARM64 devices such as Exynos 9820 (Cortex-A75/A55 + Samsung M4): retain
# the ARMv8.2 baseline needed by the port but do not emit optional dotprod, FP16
# or RCpc instructions unless the device actually guarantees those extensions.
set(_godhand_reference_cmake "${GODHAND_REFERENCE_CPP_DIR}/CMakeLists.txt")
file(READ "${_godhand_reference_cmake}" _godhand_reference_cmake_source)
string(FIND "${_godhand_reference_cmake_source}" "-march=armv8.2-a+dotprod+fp16+rcpc" _godhand_arch_flag_match)
string(FIND "${_godhand_reference_cmake_source}" "-march=armv8.2-a" _godhand_arch_baseline_match)
if(NOT _godhand_arch_flag_match EQUAL -1)
    string(REPLACE "-march=armv8.2-a+dotprod+fp16+rcpc" "-march=armv8.2-a"
        _godhand_reference_cmake_source "${_godhand_reference_cmake_source}")
elseif(_godhand_arch_baseline_match EQUAL -1)
    message(FATAL_ERROR "Reference CPU tuning flag changed; review ARM64 compatibility before building.")
endif()
string(REPLACE "-mtune=cortex-a78" "-mtune=generic"
    _godhand_reference_cmake_source "${_godhand_reference_cmake_source}")
file(WRITE "${_godhand_reference_cmake}" "${_godhand_reference_cmake_source}")

# Reuse the tested Android port's full build graph.
add_subdirectory("${GODHAND_REFERENCE_CPP_DIR}"
    "${CMAKE_CURRENT_BINARY_DIR}/godhand_android_reference")

if(NOT TARGET godhand_android)
    message(FATAL_ERROR "Reference CMake did not create the godhand_android JNI library.")
endif()

if(TARGET oboe)
    target_compile_options(oboe PRIVATE -Wno-error=unused-parameter)
endif()

# Keep the existing project's ISO diagnostics and status JNI methods, but remove
# its obsolete main() link stub from the generated build-tree copy.
set(_godhand_local_native "${CMAKE_CURRENT_SOURCE_DIR}/native-lib.cpp")
file(READ "${_godhand_local_native}" _godhand_local_native_source)
string(FIND "${_godhand_local_native_source}" "int main(int argc, char** argv) {" _godhand_local_main_match)
if(_godhand_local_main_match EQUAL -1)
    message(FATAL_ERROR "Could not locate the old native-lib.cpp main() stub safely.")
endif()
string(REGEX REPLACE "int main\\(int argc, char\\*\\* argv\\) \\{[^}]*\\}" ""
    _godhand_local_native_source "${_godhand_local_native_source}")
set(_godhand_native_compat_cpp "${CMAKE_CURRENT_BINARY_DIR}/native-lib-android.cpp")
file(WRITE "${_godhand_native_compat_cpp}" "${_godhand_local_native_source}")

target_sources(godhand_android PRIVATE
    "${_godhand_native_compat_cpp}"
    "${CMAKE_CURRENT_SOURCE_DIR}/NativeCrashLogger.cpp"
)
target_include_directories(godhand_android PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
target_compile_definitions(godhand_android PRIVATE
    GODHAND_UPSTREAM_RUNTIME_LINKED=1
    GODHAND_GAME_CODE_LINKED=1
)
set_source_files_properties("${_godhand_native_compat_cpp}" PROPERTIES
    COMPILE_OPTIONS "-include;${GODHAND_REFERENCE_CPP_DIR}/simd_arm64_compat.h"
)


# Generate external, version-matched DWARF files before stripping shipped .so files.
# The APK contains executable code and dynamic symbols; build/native-symbols retains
# the full debug information needed to symbolize Android tombstones.
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    set(_godhand_llvm_bin "\${CMAKE_ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin")
    if(NOT CMAKE_OBJCOPY)
        find_program(CMAKE_OBJCOPY NAMES llvm-objcopy HINTS "\${_godhand_llvm_bin}" REQUIRED)
    endif()
    if(NOT CMAKE_STRIP)
        find_program(CMAKE_STRIP NAMES llvm-strip HINTS "\${_godhand_llvm_bin}" REQUIRED)
    endif()

    function(godhand_split_debug_symbols target_name)
        get_target_property(_target_type \${target_name} TYPE)
        if(NOT _target_type STREQUAL "SHARED_LIBRARY")
            return()
        endif()
        set(_symbols_dir "\${GODHAND_REPO_ROOT}/build/native-symbols/arm64-v8a")
        set(_debug_file "\${_symbols_dir}/lib\${target_name}.so.debug")
        add_custom_command(TARGET \${target_name} POST_BUILD
            COMMAND "\${CMAKE_COMMAND}" -E make_directory "\${_symbols_dir}"
            COMMAND "\${CMAKE_OBJCOPY}" --only-keep-debug "$<TARGET_FILE:\${target_name}>" "\${_debug_file}"
            COMMAND "\${CMAKE_STRIP}" --strip-debug "$<TARGET_FILE:\${target_name}>"
            COMMAND "\${CMAKE_OBJCOPY}" --add-gnu-debuglink="\${_debug_file}" "$<TARGET_FILE:\${target_name}>"
            COMMENT "Preserving external debug symbols for \${target_name}"
            VERBATIM
        )
    endfunction()

    godhand_split_debug_symbols(godhand_android)
    if(TARGET pcsx2gs)
        godhand_split_debug_symbols(pcsx2gs)
    endif()
endif()

message(STATUS "God Hand Android integration: full reference runtime plus persistent native crash diagnostics")
