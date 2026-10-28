cmake_minimum_required(VERSION 3.22.1)
project(godhand_android LANGUAGES C CXX)

include(FetchContent)

# The upstream runtime uses x86 SSE intrinsics. On Android ARM64, sse2neon
# provides the NEON implementation. Pin the upstream sources for reproducible builds.
set(PS2X_ENABLE_DEBUG_UI OFF CACHE BOOL "" FORCE)
set(PS2X_ENABLE_FFMPEG OFF CACHE BOOL "" FORCE)
set(PS2X_ENABLE_SDL_INPUT OFF CACHE BOOL "" FORCE)
set(PS2X_ENABLE_RUNNER_PCH OFF CACHE BOOL "" FORCE)
set(PS2X_ENABLE_RUNNER_UNITY_BUILD OFF CACHE BOOL "" FORCE)
set(PS2X_ENABLE_AGRESSIVE_LOGS OFF CACHE BOOL "" FORCE)
set(PS2X_ENABLE_IOP_RPC_TRACE OFF CACHE BOOL "" FORCE)
set(PS2X_ENABLE_RUNTIME_LOGS ON CACHE BOOL "" FORCE)
set(URBAN_BUILD_RECOMP OFF CACHE BOOL "" FORCE)
set(URBAN_BUILD_TOOLS OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    godhand_upstream
    GIT_REPOSITORY https://github.com/LasVola/godhand-recomp.git
    GIT_TAG 14e956a1274332440273845fbab2c1f1e30c4a5a
    GIT_SHALLOW FALSE
)
FetchContent_GetProperties(godhand_upstream)
if(NOT godhand_upstream_POPULATED)
    FetchContent_Populate(godhand_upstream)
endif()

# Android NDK's libc++ can expose filesystem clock ticks as an integer type
# for which std::to_string has no unique overload. Apply a narrow compatibility
# fix to the pinned upstream source instead of modifying unrelated runtime code.
set(_godhand_pnach_cpp "${godhand_upstream_SOURCE_DIR}/runtime/ps2xRuntime/src/lib/ps2_pnach.cpp")
file(READ "${_godhand_pnach_cpp}" _godhand_pnach_source)
set(_godhand_pnach_original "std::to_string(time.time_since_epoch().count())")
set(_godhand_pnach_fixed "std::to_string(static_cast<long long>(time.time_since_epoch().count()))")
string(FIND "${_godhand_pnach_source}" "${_godhand_pnach_original}" _godhand_pnach_match)
if(_godhand_pnach_match EQUAL -1)
    message(FATAL_ERROR "Expected Android compatibility target was not found in upstream ps2_pnach.cpp; review the pinned upstream revision.")
endif()
string(REPLACE "${_godhand_pnach_original}" "${_godhand_pnach_fixed}" _godhand_pnach_source "${_godhand_pnach_source}")
file(WRITE "${_godhand_pnach_cpp}" "${_godhand_pnach_source}")

FetchContent_Declare(
    sse2neon
    GIT_REPOSITORY https://github.com/DLTcollab/sse2neon.git
    GIT_TAG v1.6.0
    GIT_SHALLOW TRUE
)
FetchContent_GetProperties(sse2neon)
if(NOT sse2neon_POPULATED)
    FetchContent_Populate(sse2neon)
endif()

# The upstream runtime includes Intel intrinsic headers directly (for example
# <xmmintrin.h>). Clang's NDK versions of those headers deliberately reject
# AArch64, so route all runtime intrinsic includes through sse2neon on Android.
# Patch only the fetched, pinned runtime tree; the upstream repository is untouched.
file(GLOB_RECURSE _godhand_runtime_sources LIST_DIRECTORIES FALSE
    "${godhand_upstream_SOURCE_DIR}/runtime/ps2xRuntime/*.c"
    "${godhand_upstream_SOURCE_DIR}/runtime/ps2xRuntime/*.cc"
    "${godhand_upstream_SOURCE_DIR}/runtime/ps2xRuntime/*.cpp"
    "${godhand_upstream_SOURCE_DIR}/runtime/ps2xRuntime/*.h"
    "${godhand_upstream_SOURCE_DIR}/runtime/ps2xRuntime/*.hpp"
    "${godhand_upstream_SOURCE_DIR}/runtime/ps2xRuntime/*.inl"
)
set(_godhand_x86_intrinsic_headers
    xmmintrin.h
    emmintrin.h
    pmmintrin.h
    tmmintrin.h
    smmintrin.h
    nmmintrin.h
    mmintrin.h
)
foreach(_godhand_source IN LISTS _godhand_runtime_sources)
    file(READ "${_godhand_source}" _godhand_source_text)
    set(_godhand_source_original "${_godhand_source_text}")
    foreach(_godhand_intrinsic_header IN LISTS _godhand_x86_intrinsic_headers)
        string(REPLACE "#include <${_godhand_intrinsic_header}>" "#include <sse2neon.h>" _godhand_source_text "${_godhand_source_text}")
        string(REPLACE "#include \"${_godhand_intrinsic_header}\"" "#include <sse2neon.h>" _godhand_source_text "${_godhand_source_text}")
    endforeach()
    if(NOT _godhand_source_text STREQUAL _godhand_source_original)
        file(WRITE "${_godhand_source}" "${_godhand_source_text}")
    endif()
endforeach()

# Import only the official PS2 runtime subproject. Adding the upstream top-level
# project would fail because this Android repo intentionally does not contain
# the ISO-specific generated C++ files under generated/.
add_subdirectory(
    "${godhand_upstream_SOURCE_DIR}/runtime"
    "${CMAKE_CURRENT_BINARY_DIR}/godhand_upstream_runtime"
    EXCLUDE_FROM_ALL
)

# The runtime's generic runner is a desktop/NativeActivity executable, not the
# JNI library used by this app. Explicitly exclude it from Gradle's "all" build:
# the Android runner references desktop entry points and is not our app entry.
if(TARGET ps2EntryRunner)
    set_target_properties(ps2EntryRunner PROPERTIES EXCLUDE_FROM_ALL TRUE)
endif()

# The upstream gamepad backend calls GLFW's private gamepad API. Raylib's
# Android build does not export those symbols, and this app currently uses the
# Android UI/JNI path rather than the upstream desktop controller loop. Provide
# safe no-controller stubs only in the fetched Android source tree.
set(_godhand_gamepads_cpp "${godhand_upstream_SOURCE_DIR}/runtime/ps2xRuntime/src/lib/ps2_host_gamepads.cpp")
file(READ "${_godhand_gamepads_cpp}" _godhand_gamepads_source)
set(_godhand_gamepads_original ${_godhand_gamepads_source})
set(_godhand_glfw_block [=[
        extern "C"
        {
            struct GlfwGamepadState
            {
                unsigned char buttons[15];
                float axes[6];
            };
            int glfwJoystickIsGamepad(int jid);
            int glfwGetGamepadState(int jid, GlfwGamepadState *state);
            const char *glfwGetGamepadName(int jid);
        }
]=])
set(_godhand_glfw_android_block [=[
        extern "C"
        {
            struct GlfwGamepadState
            {
                unsigned char buttons[15];
                float axes[6];
            };
            inline int glfwJoystickIsGamepad(int jid) { (void)jid; return 0; }
            inline int glfwGetGamepadState(int jid, GlfwGamepadState *state) { (void)jid; (void)state; return 0; }
            inline const char *glfwGetGamepadName(int jid) { (void)jid; return nullptr; }
        }
]=])
string(FIND "${_godhand_gamepads_source}" "${_godhand_glfw_block}" _godhand_glfw_block_found)
if(_godhand_glfw_block_found EQUAL -1)
    message(FATAL_ERROR "Expected GLFW gamepad block was not found in pinned upstream ps2_host_gamepads.cpp.")
endif()
string(REPLACE "${_godhand_glfw_block}" "${_godhand_glfw_android_block}" _godhand_gamepads_source "${_godhand_gamepads_source}")
if(_godhand_gamepads_source STREQUAL _godhand_gamepads_original)
    message(FATAL_ERROR "Android gamepad compatibility patch did not modify ps2_host_gamepads.cpp.")
endif()
file(WRITE "${_godhand_gamepads_cpp}" "${_godhand_gamepads_source}")

# Gradle treats every SHARED target discovered in externalNativeBuild as an
# Android native library and explicitly asks Ninja to link it. The upstream
# runner is not the JNI library used by this app. Convert only its Android
# target to STATIC in the fetched upstream tree, so Gradle does not try to
# package/link the NativeActivity runner as a second application library.
set(_godhand_runtime_cmake "${godhand_upstream_SOURCE_DIR}/runtime/ps2xRuntime/CMakeLists.txt")
file(READ "${_godhand_runtime_cmake}" _godhand_runtime_cmake_source)
set(_godhand_runner_shared_block [=[
if(PS2X_IS_ANDROID)
    add_library(ps2EntryRunner SHARED
        ${RUNNER_SRC_FILES}
    )

    target_link_options(ps2EntryRunner PRIVATE "-Wl,--undefined=ANativeActivity_onCreate")
]=])
set(_godhand_runner_static_block [=[
if(PS2X_IS_ANDROID)
    add_library(ps2EntryRunner STATIC
        ${RUNNER_SRC_FILES}
    )

    # The Android app uses JNI through libgodhand_android, not NativeActivity.
]=])
string(FIND "${_godhand_runtime_cmake_source}" "${_godhand_runner_shared_block}" _godhand_runner_block_found)
if(_godhand_runner_block_found EQUAL -1)
    message(FATAL_ERROR "Expected Android ps2EntryRunner SHARED target was not found in the pinned upstream CMakeLists.txt.")
endif()
string(REPLACE "${_godhand_runner_shared_block}" "${_godhand_runner_static_block}" _godhand_runtime_cmake_source "${_godhand_runtime_cmake_source}")
file(WRITE "${_godhand_runtime_cmake}" "${_godhand_runtime_cmake_source}")

if(NOT TARGET ps2_runtime)
    message(FATAL_ERROR "The official God Hand PS2 runtime target was not created.")
endif()

target_include_directories(ps2_runtime PUBLIC "${sse2neon_SOURCE_DIR}")
target_compile_definitions(ps2_runtime PUBLIC USE_SSE2NEON=1)

# Integrate the generated boot-ELF C++ sources already published by the tested
# Android reference port. Pinning is handled by the manual GitHub Actions workflow.
# The generated tree is large, so Actions sparse-checks it out instead of duplicating
# ~185 MB / 10k generated function files in this repository.
get_filename_component(GODHAND_REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
set(GODHAND_REFERENCE_CPP_DIR
    "${GODHAND_REPO_ROOT}/external/GodHandAndroidPort/app/src/main/cpp")
set(GODHAND_REFERENCE_GENERATED_DIR "${GODHAND_REFERENCE_CPP_DIR}/generated")
file(GLOB GODHAND_REFERENCE_UNITY_CPP CONFIGURE_DEPENDS
    "${GODHAND_REFERENCE_CPP_DIR}/generated_unity/gh_unity_batch_*.cpp"
)

set(GODHAND_GENERATED_DIR "${CMAKE_CURRENT_SOURCE_DIR}/generated" CACHE PATH
    "Directory containing locally generated God Hand C++ sources")
set(GODHAND_GAME_CPP "")
set(GODHAND_REGISTER_SOURCE "")
set(GODHAND_GAME_CODE_READY OFF)
set(GODHAND_REFERENCE_CODE_ACTIVE OFF)

if(EXISTS "${GODHAND_REFERENCE_GENERATED_DIR}/register_functions.cpp"
   AND EXISTS "${GODHAND_REFERENCE_GENERATED_DIR}/ps2_recompiled_functions.h"
   AND EXISTS "${GODHAND_REFERENCE_CPP_DIR}/simd_arm64_compat.h"
   AND GODHAND_REFERENCE_UNITY_CPP)
    # The reference publishes 10k+ generated function bodies and a generated
    # dispatch table. Unity wrappers preserve the original relative includes and
    # avoid compiling 10k individual translation units.
    set(GODHAND_GENERATED_DIR "${GODHAND_REFERENCE_GENERATED_DIR}")
    set(GODHAND_GAME_CPP ${GODHAND_REFERENCE_UNITY_CPP})
    set(GODHAND_GAME_CODE_READY ON)
    set(GODHAND_REFERENCE_CODE_ACTIVE ON)
    list(LENGTH GODHAND_REFERENCE_UNITY_CPP _godhand_unity_count)
    message(STATUS "God Hand: linking reference-generated boot code via ${_godhand_unity_count} Unity source files")
else()
    # Allow locally generated output too, useful for a future source-generation run.
    file(GLOB GODHAND_GENERATED_CPP CONFIGURE_DEPENDS
        "${GODHAND_GENERATED_DIR}/*.cpp"
    )
    set(GODHAND_GENERATED_REGISTER "${GODHAND_GENERATED_DIR}/register_functions.cpp")
    foreach(_godhand_generated_source IN LISTS GODHAND_GENERATED_CPP)
        if(NOT "${_godhand_generated_source}" STREQUAL "${GODHAND_GENERATED_REGISTER}")
            list(APPEND GODHAND_GAME_CPP "${_godhand_generated_source}")
        endif()
    endforeach()

    if(EXISTS "${GODHAND_GENERATED_REGISTER}" AND GODHAND_GAME_CPP)
        set(GODHAND_REGISTER_SOURCE "${GODHAND_GENERATED_REGISTER}")
        set(GODHAND_GAME_CODE_READY ON)
        message(STATUS "God Hand: linking ${GODHAND_GAME_CPP} local generated source file(s)")
    else()
        set(GODHAND_REGISTER_SOURCE
            "${godhand_upstream_SOURCE_DIR}/runtime/ps2xRuntime/src/runner/register_functions.cpp")
        message(WARNING
            "God Hand game-specific C++ is not ready: reference sources were not fetched and "
            "no local generated/*.cpp plus generated/register_functions.cpp were found. "
            "The APK will contain diagnostics/runtime only.")
    endif()
endif()

add_library(godhand_android SHARED
    native-lib.cpp
    ${GODHAND_REGISTER_SOURCE}
    ${GODHAND_GAME_CPP}
)
target_compile_features(godhand_android PRIVATE cxx_std_20)
target_include_directories(godhand_android PRIVATE
    "${GODHAND_GENERATED_DIR}"
    "${GODHAND_REFERENCE_CPP_DIR}"
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${godhand_upstream_SOURCE_DIR}/runtime/ps2xRuntime/src/lib"
)

# Keep strict warnings as errors, except for unused parameters in upstream headers
# and no-op virtual methods. Clang/NDK reports these in sse2neon and gs_backend.h.
target_compile_options(godhand_android PRIVATE
    -Wall
    -Wextra
    -Werror
    -Wno-error=unused-parameter
)

# The reference's generated MIPS-to-C++ bodies can produce benign warnings under
# newer NDK Clang versions. Keep -Werror for our JNI glue, but don't make generated
# translation units fail solely on compiler-version warning differences.
if(GODHAND_REFERENCE_CODE_ACTIVE)
    set_source_files_properties(${GODHAND_GAME_CPP} PROPERTIES
        COMPILE_OPTIONS "-Wno-error;-Wno-unused-variable;-Wno-unused-but-set-variable;-Wno-sign-compare"
    )
endif()
target_compile_definitions(godhand_android PRIVATE GODHAND_UPSTREAM_RUNTIME_LINKED=1)
if(GODHAND_GAME_CODE_READY)
    target_compile_definitions(godhand_android PRIVATE GODHAND_GAME_CODE_LINKED=1)
endif()
target_link_libraries(godhand_android PRIVATE ps2_runtime)

find_library(log-lib log)
target_link_libraries(godhand_android PRIVATE ${log-lib} android)
