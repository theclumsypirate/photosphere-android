# CMakeLists.txt for OpenCV 360° Equirectangular Stitcher
# Debug-only native module for high-quality 360° panorama stitching

cmake_minimum_required(VERSION 3.10.2)

# Project name
project("opencv_360_stitcher")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# OpenCV via Prefab from Maven AAR (org.opencv:opencv:4.12.0)
# The AAR provides prefab package "OpenCV" with module "opencv_java4"
# Gradle's prefab support automatically exposes this as a CMake package
find_package(OpenCV REQUIRED CONFIG)

if(NOT OpenCV_FOUND)
    message(FATAL_ERROR "OpenCV prefab package not found. Ensure org.opencv:opencv:4.12.0 is in dependencies.")
endif()

# Android log library
find_library(android_log log)

# Source files
set(SOURCES
    opencv_360_stitcher.cpp
)

# Create shared library
add_library(
    opencv_360_stitcher
    SHARED
    ${SOURCES}
)

# Include directories
target_include_directories(opencv_360_stitcher PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Link against OpenCV from prefab (provides OpenCV::opencv_java4 target)
target_link_libraries(opencv_360_stitcher PRIVATE
    OpenCV::opencv_java4
    ${android_log}
)

# Compile options
target_compile_options(opencv_360_stitcher PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
    -fvisibility=hidden
    -fvisibility-inlines-hidden
)

# Set output name
set_target_properties(opencv_360_stitcher PROPERTIES
    OUTPUT_NAME "opencv_360_stitcher"
    ANDROID_SO_UNDEF ON
)

# Install (optional, for debug builds)
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    install(TARGETS opencv_360_stitcher
        LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR}
    )
endif()