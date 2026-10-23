cmake_minimum_required(VERSION 3.22.1)

project(ncnn LANGUAGES C CXX)
set(JNILIBS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fopenmp")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -fopenmp -static-openmp")
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(
    main
    SHARED
    video.cpp
    helper.cpp
    inference.cpp
    ByteTrack/BYTETracker.cpp
    ByteTrack/KalmanFilter.cpp
    ByteTrack/lapjv.cpp
    ByteTrack/Object.cpp
    ByteTrack/Rect.cpp
    ByteTrack/STrack.cpp
)

target_include_directories(
    main
    PRIVATE
    include
)

add_library(ncnn STATIC IMPORTED)
set_target_properties(ncnn PROPERTIES IMPORTED_LOCATION ${JNILIBS_DIR}/${ANDROID_ABI}/libncnn.so)

add_library(avcodec SHARED IMPORTED)
set_target_properties(avcodec PROPERTIES IMPORTED_LOCATION ${JNILIBS_DIR}/${ANDROID_ABI}/libavcodec.so)

add_library(avformat SHARED IMPORTED)
set_target_properties(avformat PROPERTIES IMPORTED_LOCATION ${JNILIBS_DIR}/${ANDROID_ABI}/libavformat.so)

add_library(swscale SHARED IMPORTED)
set_target_properties(swscale PROPERTIES IMPORTED_LOCATION ${JNILIBS_DIR}/${ANDROID_ABI}/libswscale.so)

add_library(avutil SHARED IMPORTED)
set_target_properties(avutil PROPERTIES IMPORTED_LOCATION ${JNILIBS_DIR}/${ANDROID_ABI}/libavutil.so)

target_link_libraries(
    main
    avformat
    avcodec
    swscale
    avutil
    ncnn
    ${VULKAN_LIBRARY}
    android
    log
)