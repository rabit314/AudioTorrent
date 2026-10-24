cmake_minimum_required(VERSION 3.22)
project(rivulet_narration LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# Inference must remain optimized in debug APKs used on the phone.
add_compile_options(-O3)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
include(FetchContent)
FetchContent_Declare(whisper
  URL https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.9.4.tar.gz
  URL_HASH SHA256=57e280cee375ab02425b806ad5146b99f6eb9357e3c2b31357c8a6af2e2e44ae
  DOWNLOAD_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../build/native-source"
  DOWNLOAD_NAME whisper-v1.9.4.tar.gz
  DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
FetchContent_MakeAvailable(whisper)
add_library(rivulet_whisper SHARED whisper_jni.cpp)
target_link_libraries(rivulet_whisper PRIVATE whisper android log)
target_compile_options(rivulet_whisper PRIVATE -O3)
target_link_options(rivulet_whisper PRIVATE -Wl,-z,max-page-size=16384)
