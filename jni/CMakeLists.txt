cmake_minimum_required(VERSION 3.16)
project(vectorreorder_faiss)

set(CMAKE_CXX_STANDARD 17)

# Find JNI
find_package(JNI REQUIRED)

# Use pre-built FAISS from k-NN
set(KNN_JNI_DIR "${CMAKE_SOURCE_DIR}/../../k-NN/jni")
set(FAISS_DIR "${KNN_JNI_DIR}/external/faiss")

# OpenMP setup for macOS (same as k-NN)
if (APPLE)
    if(CMAKE_SYSTEM_PROCESSOR STREQUAL "arm64")
        set(OpenMP_C_FLAGS "-Xpreprocessor -fopenmp")
        set(OpenMP_C_LIB_NAMES "omp")
        set(OpenMP_CXX_FLAGS "-Xpreprocessor -fopenmp -I/opt/homebrew/opt/libomp/include")
        set(OpenMP_CXX_LIB_NAMES "omp")
        set(OpenMP_omp_LIBRARY /opt/homebrew/opt/libomp/lib/libomp.dylib)
    else()
        set(OpenMP_C_FLAGS "-Xpreprocessor -fopenmp")
        set(OpenMP_C_LIB_NAMES "omp")
        set(OpenMP_CXX_FLAGS "-Xpreprocessor -fopenmp -I/usr/local/opt/libomp/include")
        set(OpenMP_CXX_LIB_NAMES "omp")
        set(OpenMP_omp_LIBRARY /usr/local/opt/libomp/lib/libomp.dylib)
    endif()
endif()

find_package(OpenMP REQUIRED)
find_package(BLAS REQUIRED)
find_package(LAPACK REQUIRED)

# Find pre-built faiss library from k-NN build
find_library(FAISS_LIB 
    NAMES faiss faiss_avx2
    PATHS ${KNN_JNI_DIR}/build/external/faiss/faiss
    NO_DEFAULT_PATH
)

if(NOT FAISS_LIB)
    message(FATAL_ERROR "Could not find pre-built FAISS library. Build k-NN JNI first.")
endif()

message(STATUS "Using FAISS library: ${FAISS_LIB}")

# Our JNI library
add_library(vectorreorder_faiss SHARED
    src/faiss_core.cpp
    src/faiss_jni.cpp
)

# Test executable (no JNI dependency)
add_executable(faiss_test 
    src/faiss_core.cpp
    test/faiss_test.cpp
)

target_compile_options(vectorreorder_faiss PRIVATE -Xpreprocessor -fopenmp -I/opt/homebrew/opt/libomp/include)
target_compile_options(faiss_test PRIVATE -Xpreprocessor -fopenmp -I/opt/homebrew/opt/libomp/include -g -O0)

target_include_directories(vectorreorder_faiss PRIVATE
    ${JNI_INCLUDE_DIRS}
    ${CMAKE_SOURCE_DIR}/include
    ${FAISS_DIR}
)

target_include_directories(faiss_test PRIVATE
    ${FAISS_DIR}
    ${CMAKE_SOURCE_DIR}/src
)

target_link_libraries(vectorreorder_faiss PRIVATE 
    ${FAISS_LIB}
    OpenMP::OpenMP_CXX
    ${BLAS_LIBRARIES}
    ${LAPACK_LIBRARIES}
)

target_link_libraries(faiss_test PRIVATE
    ${FAISS_LIB}
    OpenMP::OpenMP_CXX
    ${BLAS_LIBRARIES}
    ${LAPACK_LIBRARIES}
)

# Output to release directory
set_target_properties(vectorreorder_faiss PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY ${CMAKE_SOURCE_DIR}/release
)
