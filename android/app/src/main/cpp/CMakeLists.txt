cmake_minimum_required(VERSION 3.22.1)

project("roombeat_audio" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Locate Google Oboe package provided via Prefab
find_package(oboe REQUIRED CONFIG)

# Define native shared library
add_library(roombeat_audio SHARED
    OboeAudioPlayer.cpp
    AudioEngineBridge.cpp
)

target_include_directories(roombeat_audio PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

find_library(log-lib log)

# Link against Oboe and Android log library
target_link_libraries(roombeat_audio
    PRIVATE
    oboe::oboe
    ${log-lib}
)
