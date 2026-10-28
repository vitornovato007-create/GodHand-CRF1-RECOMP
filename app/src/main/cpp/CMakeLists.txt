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

if(NOT TARGET ps2_runtime)
    message(FATAL_ERROR "The official God Hand PS2 runtime target was not created.")
endif()

target_include_directories(ps2_runtime PUBLIC "${sse2neon_SOURCE_DIR}")
target_compile_definitions(ps2_runtime PUBLIC USE_SSE2NEON=1)

# The official runner normally supplies this table from generated game sources.
# Until those are available, use the upstream empty table so the runtime core links
# honestly without pretending that game functions exist.
add_library(godhand_android SHARED
    native-lib.cpp
    "${godhand_upstream_SOURCE_DIR}/runtime/ps2xRuntime/src/runner/register_functions.cpp"
)
target_compile_features(godhand_android PRIVATE cxx_std_20)

# Keep strict warnings as errors, except for unused parameters in upstream headers
# and no-op virtual methods. Clang/NDK reports these in sse2neon and gs_backend.h.
target_compile_options(godhand_android PRIVATE
    -Wall
    -Wextra
    -Werror
    -Wno-error=unused-parameter
)
target_compile_definitions(godhand_android PRIVATE GODHAND_UPSTREAM_RUNTIME_LINKED=1)
target_link_libraries(godhand_android PRIVATE ps2_runtime)

find_library(log-lib log)
target_link_libraries(godhand_android PRIVATE ${log-lib} android)
