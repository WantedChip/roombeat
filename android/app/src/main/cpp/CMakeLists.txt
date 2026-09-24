cmake_minimum_required(VERSION 3.22.1)

project("roombeat_audio" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Configure libopus options before adding subdirectory
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "Build static libopus" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "Disable libopus tests" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "Disable libopus programs" FORCE)
set(OPUS_ENABLE_FLOAT_API ON CACHE BOOL "Enable float API" FORCE)

add_subdirectory(opus EXCLUDE_FROM_ALL)

# Locate Google Oboe package provided via Prefab
find_package(oboe REQUIRED CONFIG)

# Define native shared library
add_library(roombeat_audio SHARED
    OboeAudioPlayer.cpp
    audio/GainProcessor.cpp
    codec/OpusEncoderWrapper.cpp
    codec/OpusDecoderWrapper.cpp
    codec/OpusCodecJni.cpp
    buffer/AudioJitterBuffer.cpp
    buffer/AudioJitterBufferJni.cpp
    AudioEngineBridge.cpp
)

target_include_directories(roombeat_audio PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/audio
    ${CMAKE_CURRENT_SOURCE_DIR}/codec
    ${CMAKE_CURRENT_SOURCE_DIR}/buffer
)

find_library(log-lib log)

# Link against Oboe, Opus, and Android log library
target_link_libraries(roombeat_audio
    PRIVATE
    oboe::oboe
    opus
    ${log-lib}
)
