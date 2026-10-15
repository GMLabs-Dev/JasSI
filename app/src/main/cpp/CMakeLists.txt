cmake_minimum_required(VERSION 3.22.1)
project(jarvis_native)

include(FetchContent)

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)

# The Hexagon backend is upstream llama.cpp code.  It is enabled only when the
# developer provides Qualcomm's Hexagon SDK; the SDK is not redistributable.
# See https://github.com/ggml-org/llama.cpp/tree/master/ggml/src/ggml-hexagon
if (NOT DEFINED ENV{HEXAGON_SDK_ROOT} AND IS_DIRECTORY "/home/gustaw/Qualcomm/6.6.0.0")
    set(HEXAGON_SDK_ROOT "/home/gustaw/Qualcomm/6.6.0.0" CACHE PATH "" FORCE)
    set(ENV{HEXAGON_SDK_ROOT} "/home/gustaw/Qualcomm/6.6.0.0")
elseif (DEFINED ENV{HEXAGON_SDK_ROOT})
    set(HEXAGON_SDK_ROOT "$ENV{HEXAGON_SDK_ROOT}" CACHE PATH "" FORCE)
endif()
if (DEFINED ENV{HEXAGON_SDK_ROOT} AND IS_DIRECTORY "$ENV{HEXAGON_SDK_ROOT}")
    set(GGML_HEXAGON ON CACHE BOOL "" FORCE)
    # Qualcomm SDK 6.6 reads this variable before its ExternalProject passes a
    # per-DSP value. An explicit empty default keeps its string(FIND) probes
    # valid with the Android CMake version shipped by the project.
    set(PREBUILT_LIB_DIR "toolv81" CACHE STRING "" FORCE)
    message(STATUS "JasSI: enabling upstream llama.cpp Hexagon backend")
else()
    set(GGML_HEXAGON OFF CACHE BOOL "" FORCE)
    message(STATUS "JasSI: Hexagon SDK not found; using CPU fallback")
endif()

FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG 47f5a64ae12833b9092c6c47b54aa9f310c4f6f1
)
FetchContent_MakeAvailable(llama)

set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(WHISPER_USE_SYSTEM_GGML ON CACHE BOOL "" FORCE)
FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG v1.7.6
)
FetchContent_MakeAvailable(whisper)
target_compile_definitions(whisper PRIVATE GGML_KQ_MASK_PAD=64)

add_library(jarvis_native SHARED native_bridge.cpp)
target_include_directories(jarvis_native PRIVATE
    ${llama_SOURCE_DIR}/include
    ${llama_SOURCE_DIR}/ggml/include
    ${whisper_SOURCE_DIR}/include
)
target_link_libraries(jarvis_native PRIVATE llama whisper android log)
