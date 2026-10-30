cmake_minimum_required(VERSION 3.22.1)
project(micfx LANGUAGES CXX)

# Oboe comes from the com.google.oboe:oboe AAR via Prefab (buildFeatures.prefab = true).
find_package(oboe REQUIRED CONFIG)

add_library(micfx SHARED
        mic_fx_engine.cpp
        jni_bridge.cpp)

target_compile_options(micfx PRIVATE -Wall -Wextra -Werror=return-type -fno-exceptions -fno-rtti)
target_link_libraries(micfx PRIVATE oboe::oboe log)

# 16 KB page-size compatibility (required by Play for API 35+ targets). NDK r28+ does this
# by default; kept explicit so older NDKs fail loudly instead of shipping a bad binary.
target_link_options(micfx PRIVATE "-Wl,-z,max-page-size=16384")
