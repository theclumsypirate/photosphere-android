# CMakeLists.txt for OpenCV 360° Equirectangular Stitcher
# Debug-only native module for high-quality 360° panorama stitching

cmake_minimum_required(VERSION 3.10.2)

# Project name
project("opencv_360_stitcher")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# OpenCV configuration
# The OpenCV Android SDK provides CMake config files via the Maven artifact
# org.opencv:opencv:4.12.0 which unpacks native libs and CMake config to:
# ${CMAKE_SOURCE_DIR}/../../../build/intermediates/cmake/debug/obj/${ANDROID_ABI}/opencv

# Try to find OpenCV from the Gradle-unpacked AAR
set(OpenCV_DIR 
    "${CMAKE_SOURCE_DIR}/../../../build/intermediates/cmake/debug/obj/${ANDROID_ABI}/opencv"
    CACHE PATH "OpenCV config directory from Maven AAR"
)

# Find OpenCV - required components for 360° stitching pipeline
find_package(OpenCV 4.12 REQUIRED COMPONENTS
    core
    imgcodecs
    imgproc
    stitching
    features2d
    calib3d
    flann
)

if(NOT OpenCV_FOUND)
    message(FATAL_ERROR "OpenCV not found. Please ensure org.opencv:opencv:4.12.0 is in dependencies and the project has been synced.")
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
    ${OpenCV_INCLUDE_DIRS}
)

# Link libraries
target_link_libraries(opencv_360_stitcher PRIVATE
    ${OpenCV_LIBS}
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