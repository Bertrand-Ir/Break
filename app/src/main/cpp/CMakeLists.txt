cmake_minimum_required(VERSION 3.22.1)
project(break LANGUAGES CXX)

find_package(oboe REQUIRED CONFIG)

add_library(break SHARED
        tsf_impl.cpp
        engine.cpp
        jni_bridge.cpp)

target_include_directories(break PRIVATE third_party)
target_compile_options(break PRIVATE -Wall -Wno-unused-function -Wno-unused-variable)
# 16 KB page-size compatibility (required for Android 15+ devices / Play policy)
target_link_options(break PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(break oboe::oboe android log)

# On Windows hosts whose SDK path contains spaces, AGP invokes clang via its 8.3 short name
# (CLANG_~1.EXE), which hides the "++" clang uses to pick C++ link mode. Force it.
if(CMAKE_HOST_WIN32)
    target_link_options(break PRIVATE "--driver-mode=g++")
endif()
