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

FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG 47f5a64ae12833b9092c6c47b54aa9f310c4f6f1
)
FetchContent_MakeAvailable(llama)

add_library(jarvis_native SHARED native_bridge.cpp)
target_include_directories(jarvis_native PRIVATE
    ${llama_SOURCE_DIR}/include
)
target_link_libraries(jarvis_native PRIVATE llama android log)
