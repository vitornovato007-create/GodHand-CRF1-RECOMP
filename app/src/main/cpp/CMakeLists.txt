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

add_library(godhand_android SHARED native-lib.cpp)
target_compile_features(godhand_android PRIVATE cxx_std_20)
target_compile_options(godhand_android PRIVATE -Wall -Wextra -Werror)
target_compile_definitions(godhand_android PRIVATE GODHAND_UPSTREAM_RUNTIME_LINKED=1)
target_link_libraries(godhand_android PRIVATE ps2_runtime)

find_library(log-lib log)
target_link_libraries(godhand_android PRIVATE ${log-lib} android)
