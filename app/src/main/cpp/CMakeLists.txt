cmake_minimum_required(VERSION 3.22.1)

project("llama-bridge")

# High performance optimization flags for ARM CPU matrix math
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -fno-finite-math-only")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -fno-finite-math-only")

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8.2-a+fp16")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv8.2-a+fp16")
endif()

# Fetch and compile official llama.cpp engine natively
include(FetchContent)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build static llama" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "Disable examples" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "Disable tests" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "Disable server" FORCE)

FetchContent_Declare(
    llamacpp
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
    GIT_TAG        b3600
)
FetchContent_MakeAvailable(llamacpp)

add_library(
        llama-bridge
        SHARED
        llama-bridge.cpp)

find_library(
        log-lib
        log)

target_link_libraries(
        llama-bridge
        llama
        ${log-lib})
