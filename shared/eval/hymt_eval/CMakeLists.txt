# Host build of the on-device engine (llama/src/main/cpp/engine.cpp) for prompt work on a laptop.
# Built by shared/eval/prompt_eval.py; see its docstring.
cmake_minimum_required(VERSION 3.22)
project(hymt_eval LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)

set(ANDROID ${CMAKE_CURRENT_LIST_DIR}/../../../android)
set(LLAMA_SRC ${ANDROID}/third_party/llama.cpp)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
add_subdirectory(${LLAMA_SRC} build-llama)

set(ENGINE ${ANDROID}/llama/src/main/cpp)
add_executable(hymt_eval main.cpp ${ENGINE}/engine.cpp)
target_include_directories(hymt_eval PRIVATE ${ENGINE} ${LLAMA_SRC}/vendor)
target_link_libraries(hymt_eval llama ggml)
