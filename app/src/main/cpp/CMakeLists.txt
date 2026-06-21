# CMakeLists.txt - OfflineAI Native 模块
# 编译 llama.cpp JNI 桥接层 + 轻量向量检索引擎
cmake_minimum_required(VERSION 3.22.1)
project("offlineai_native")

# ─── llama.cpp 集成 ──────────────────────────────────
# 优先使用本地源码（路径：OfflineAI/../llamacpp/），
# 未找到时通过 FetchContent 自动拉取 master 分支。

set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_NATIVE OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENMP OFF CACHE BOOL "" FORCE)

# ─── 16 KB 页对齐（Android 15+ 强制要求） ───────
# 必须设在 add_subdirectory 之前，全局生效到所有 .so
add_link_options(
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
)

# 策略一：本地源码（推荐，可精确控制版本）
set(LLAMACPP_LOCAL "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../llamacpp")
if(EXISTS "${LLAMACPP_LOCAL}/CMakeLists.txt")
    message(STATUS "Using local llama.cpp at ${LLAMACPP_LOCAL}")
    add_subdirectory(${LLAMACPP_LOCAL} ${CMAKE_CURRENT_BINARY_DIR}/llamacpp EXCLUDE_FROM_ALL)
    set(LLAMACPP_INCLUDE_DIR "${LLAMACPP_LOCAL}")
else()
    # 策略二：FetchContent 自动下载
    message(STATUS "llama.cpp not found locally, fetching from GitHub...")
    include(FetchContent)

    FetchContent_Declare(
        llama.cpp
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
        GIT_TAG master
        GIT_SHALLOW TRUE
        GIT_PROGRESS TRUE
    )

    FetchContent_GetProperties(llama.cpp)
    if(NOT llama.cpp_POPULATED)
        FetchContent_Populate(llama.cpp)
    endif()

    add_subdirectory(${llama.cpp_SOURCE_DIR} ${llama.cpp_BINARY_DIR} EXCLUDE_FROM_ALL)
    set(LLAMACPP_INCLUDE_DIR "${llama.cpp_SOURCE_DIR}")
endif()

# ─── JNI 源文件 ──────────────────────────────────────
add_library(offlineai_jni SHARED
    llama-jni.cpp
)

target_include_directories(offlineai_jni PRIVATE
    ${LLAMACPP_INCLUDE_DIR}
    ${LLAMACPP_INCLUDE_DIR}/common
)

target_link_libraries(offlineai_jni
    llama
    android
    log
)

# ─── 编译选项 ────────────────────────────────────────
target_compile_options(offlineai_jni PRIVATE
    -std=c++17
    -O2
    -Wall
    -Wextra
    -Wno-unused-parameter
    -Wno-sign-compare
    -ffast-math
)
