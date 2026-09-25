cmake_minimum_required(VERSION 3.22.1)

project("langboard_llama" LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp)
if(NOT EXISTS ${LLAMA_SRC}/CMakeLists.txt)
    message(FATAL_ERROR "llama.cpp is missing. Run tools/fetch_llama_cpp.sh from the android/ directory.")
endif()

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_SYSTEM_ARCH "ARM")
    # KleidiAI kernels for i8mm/SME cores; the CPU variant picked at runtime decides whether they run.
    set(GGML_CPU_KLEIDIAI ON)
else()
    message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
endif()

add_subdirectory(${LLAMA_SRC} build-llama)

add_library(langboard_llama SHARED llama_jni.cpp engine.cpp)

target_link_libraries(langboard_llama llama ggml android log)
