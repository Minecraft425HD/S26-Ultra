# Nativer Teil der lokalen Inferenz.
#
# Wird nur ausgewertet, wenn mit -Pneon.buildNative=true gebaut wird und die
# llama.cpp-Quellen vorhanden sind (scripts/fetch-native-deps.sh).

cmake_minimum_required(VERSION 3.22.1)
project(neon_llama LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/CMakeLists.txt")
    message(FATAL_ERROR
        "Die llama.cpp-Quellen fehlen. Führe scripts/fetch-native-deps.sh aus, "
        "oder baue ohne -Pneon.buildNative=true.")
endif()

# Kein Server, keine Beispiele, keine Tests — davon braucht die App nichts, und jedes
# davon verlängert den Build erheblich.
set(LLAMA_BUILD_TESTS   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS   OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP         OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp EXCLUDE_FROM_ALL)

add_library(neon_llama SHARED neon_llama.cpp)

target_link_libraries(neon_llama
    PRIVATE
        llama
        android
        log)
